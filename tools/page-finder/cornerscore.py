"""cornerscore.py <corners .onnx> <scene dir>...: the corner network alone, as CornerNet.kt runs it
(gray, padded to 4:3, 256x256), on each dir's scene_*.png. With a truth.txt there (scene x,y;...
0..1): mean IoU with the truth, shares at 0.95 and 0.9, and frames with no outline. Without one
(frames with no page): how many get an outline, which should be none.
"""
import glob
import os
import sys

import cv2
import numpy as np
import onnxruntime as ort

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from corners import corner  # noqa: E402


def iou(a, b):
    ma, mb = np.zeros((400, 400), np.uint8), np.zeros((400, 400), np.uint8)
    cv2.fillPoly(ma, [np.round(a * 400).astype(np.int32)], 1)
    cv2.fillPoly(mb, [np.round(b * 400).astype(np.int32)], 1)
    return (ma & mb).sum() / max(1, (ma | mb).sum())


def outline(sess, path):
    g = cv2.imread(path, cv2.IMREAD_GRAYSCALE)
    h, w = g.shape
    wide = max(w, (h * 3 + 2) // 4) if h >= w else w
    tall = max(h, (w * 3 + 2) // 4) if w > h else h
    left, top = (wide - w) // 2, (tall - h) // 2
    g = cv2.copyMakeBorder(g, top, tall - h - top, left, wide - w - left, cv2.BORDER_REPLICATE)
    x = cv2.resize(g, (256, 256), interpolation=cv2.INTER_AREA).astype(np.float32) / 255
    hm = sess.run(None, {sess.get_inputs()[0].name: np.repeat(x[None, None], 3, 1)})[0][0]
    pts = [corner(m) for m in hm]
    if any(p is None for p in pts):
        return None
    q = np.array([((a * wide - left) / w, (b * tall - top) / h) for a, b in pts])
    return q[np.argsort(np.arctan2(q[:, 1] - q[:, 1].mean(), q[:, 0] - q[:, 0].mean()))]


sess = ort.InferenceSession(sys.argv[1])
for d in sys.argv[2:]:
    name = f'{os.path.basename(sys.argv[1]):24s} {os.path.basename(d.rstrip("/")):6s}'
    tf = os.path.join(d, 'truth.txt')
    if not os.path.exists(tf):
        scenes = sorted(glob.glob(os.path.join(d, 'scene_*.png')))
        print(f'{name} outlines on empty frames {sum(outline(sess, p) is not None for p in scenes)}/{len(scenes)}')
        continue
    ious, none = [], 0
    for line in open(tf):
        f = line.split()
        if len(f) < 2:
            continue
        q = outline(sess, os.path.join(d, f[0]))
        if q is None:
            none += 1
            ious.append(0)
            continue
        ious.append(iou(q, np.array([[float(v) for v in c.split(',')] for c in f[1].split(';')])))
    i = np.array(ious)
    print(f'{name} mean {i.mean():.3f} >=.95 {(i >= .95).mean():.2f} >=.9 {(i >= .9).mean():.2f} none {none}')
