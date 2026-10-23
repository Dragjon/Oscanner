"""corners.py <corners.onnx> <scene dir> <out dir>: the corner network (CornerNet.kt) on every scene_*.png,
as the app runs it (gray, padded out to 4:3 if longer, 256x256; each corner the heat-weighted middle of its largest patch over
0.3; none unless every corner peaks at 0.5 or more), one <scene>.txt per scene with x,y;x,y;x,y;x,y
(0..1, in heatmap order) or none. With DET_CORNERS=<out dir> the detector benchmark uses them, so
the JVM test needs no ONNX Runtime.

corners.onnx is DocAligner's lcnet100_h_e_bifpn_256_fp32.onnx (DocsaidLab, Apache-2.0), from
https://github.com/DocsaidLab/DocAligner (its heatmap_reg model list)."""
import glob
import os
import sys

import cv2
import numpy as np
import onnxruntime as ort

SURE, PATCH = 0.5, 0.3
SIDE = int(os.environ.get('SIDE', 256))


def corner(hm):
    if hm.max() < SURE:
        return None
    n, lab = cv2.connectedComponents((hm >= PATCH).astype(np.uint8), connectivity=4)
    if n < 2:
        return None
    best = 1 + np.argmax([np.sum(lab == k) for k in range(1, n)])
    ys, xs = np.nonzero(lab == best)
    v = hm[ys, xs]
    h, w = hm.shape
    return (np.sum(v * (xs + 0.5)) / v.sum() / w, np.sum(v * (ys + 0.5)) / v.sum() / h)


def main(model, scenes, out):
    sess = ort.InferenceSession(model)
    os.makedirs(out, exist_ok=True)
    for f in sorted(glob.glob(os.path.join(scenes, 'scene_*.png'))):
        g = cv2.imread(f, cv2.IMREAD_GRAYSCALE)
        h, w = g.shape
        # Padded out to 4:3 when longer, as CornerNet.kt does.
        wide = max(w, (h * 3 + 2) // 4) if h >= w else w
        tall = max(h, (w * 3 + 2) // 4) if w > h else h
        left, top = (wide - w) // 2, (tall - h) // 2
        g = cv2.copyMakeBorder(g, top, tall - h - top, left, wide - w - left, cv2.BORDER_REPLICATE)
        g = cv2.resize(g, (SIDE, SIDE), interpolation=cv2.INTER_AREA)
        x = np.repeat(g[None, None].astype(np.float32) / 255, 3, axis=1)
        hm = sess.run(None, {sess.get_inputs()[0].name: x})[0][0]
        pts = [corner(m) for m in hm]
        text = 'none' if any(p is None for p in pts) else ';'.join(f'{(a * wide - left) / w:.5f},{(b * tall - top) / h:.5f}' for a, b in pts)
        open(os.path.join(out, os.path.basename(f)[:-4] + '.txt'), 'w').write(text)


if __name__ == '__main__':
    main(*sys.argv[1:4])
