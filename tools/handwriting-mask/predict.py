"""Runs the handwriting mask on whole pages, for checking by eye.

    python predict.py <hand.pt or hand.onnx> <out dir> <page images...>

Writes <name>_mask.png (red where it found handwriting) and <name>_clean.png (that taken out,
filled with the paper around it, roughly as the app does).
"""
import os
import sys

import cv2
import numpy as np


def runner(path):
    if path.endswith('.onnx'):
        import onnxruntime as ort
        s = ort.InferenceSession(path, providers=['CPUExecutionProvider'])
        return lambda x: s.run(None, {'rgb': x})[0]
    import torch
    from net import HandNet
    dev = 'cuda' if torch.cuda.is_available() else 'cpu'
    net = HandNet().to(dev).eval()
    net.load_state_dict(torch.load(path, map_location=dev))

    def run(x):
        with torch.no_grad():
            return net(torch.from_numpy(x).to(dev)).cpu().numpy()
    return run


def probability(run, rgb):
    h, w = rgb.shape[:2]
    # Sizes divisible by 16 for the network's four halvings.
    ph, pw = (16 - h % 16) % 16, (16 - w % 16) % 16
    x = cv2.copyMakeBorder(rgb, 0, ph, 0, pw, cv2.BORDER_REPLICATE)
    x = x.astype(np.float32).transpose(2, 0, 1)[None] / 255
    logit = run(x)[0, 0, :h, :w]
    return 1 / (1 + np.exp(-logit))


def clean(rgb, mask):
    gray = cv2.cvtColor(rgb, cv2.COLOR_RGB2GRAY)
    ink = cv2.adaptiveThreshold(gray, 255, cv2.ADAPTIVE_THRESH_MEAN_C, cv2.THRESH_BINARY_INV, 31, 6)
    holes = cv2.dilate(ink | mask, np.ones((5, 5), np.uint8))
    k = 600 / max(rgb.shape[:2])
    small = cv2.resize(rgb, None, fx=k, fy=k, interpolation=cv2.INTER_AREA)
    sh = cv2.resize(holes, (small.shape[1], small.shape[0]), interpolation=cv2.INTER_AREA)
    sh = (sh > 0).astype(np.uint8) * 255
    paper = cv2.inpaint(small, sh, 3, cv2.INPAINT_TELEA)
    paper = cv2.resize(cv2.GaussianBlur(paper, (5, 5), 0), (rgb.shape[1], rgb.shape[0]))
    out = rgb.copy()
    out[mask > 0] = paper[mask > 0]
    return out


def main():
    run = runner(sys.argv[1])
    out = sys.argv[2]
    os.makedirs(out, exist_ok=True)
    for p in sys.argv[3:]:
        rgb = cv2.imread(p)[..., ::-1].copy()
        prob = probability(run, rgb)
        # Sure strokes, and the less sure parts joined to them (the rest of a stroke the
        # network was sure of somewhere along it).
        sure = (prob > 0.5).astype(np.uint8)
        maybe = (prob > 0.15).astype(np.uint8)
        n, ids = cv2.connectedComponents(maybe, connectivity=8)
        keep = np.zeros(n, bool)
        keep[np.unique(ids[sure > 0])] = True
        keep[0] = False
        mask = (keep[ids] * 255).astype(np.uint8)
        # The stroke's soft rim goes too, but never ink it is sure is not handwriting (as Cleanup.kt).
        mask = cv2.dilate(mask, np.ones((3, 3), np.uint8))
        gray = cv2.cvtColor(rgb, cv2.COLOR_RGB2GRAY)
        ink = cv2.adaptiveThreshold(gray, 255, cv2.ADAPTIVE_THRESH_MEAN_C, cv2.THRESH_BINARY_INV,
                                    max(15, max(rgb.shape[:2]) // 70 | 1), 12) > 0
        mask[ink & (prob < 0.1)] = 0
        vis = rgb.copy()
        vis[mask > 0] = (vis[mask > 0] * 0.3 + np.array([255, 0, 0]) * 0.7).astype(np.uint8)
        name = os.path.splitext(os.path.basename(p))[0]
        cv2.imwrite(os.path.join(out, name + '_mask.png'), vis[..., ::-1])
        cv2.imwrite(os.path.join(out, name + '_clean.png'), clean(rgb, mask)[..., ::-1])
        print(name, round(float((mask > 0).mean()), 4))


if __name__ == '__main__':
    main()
