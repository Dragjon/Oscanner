"""Draws truth (green) and detection (red) for scenes matching a filter: show.py <dir> <out.png> [wrong|missed] [kind]"""
import math
import sys

import cv2
import numpy as np

d, out = sys.argv[1], sys.argv[2]
which = sys.argv[3] if len(sys.argv) > 3 else 'wrong'
kind = sys.argv[4] if len(sys.argv) > 4 else ''
truth = {l.split()[0]: [tuple(map(float, c.split(','))) for c in l.split()[1].split(';')] for l in open(d + '/truth.txt') if l.strip()}
res = {}
for l in open(d + '/kotlin_results.txt'):
    n, ms, q = l.split(' ', 2)
    q = q.strip()
    res[n] = None if q == 'none' else [tuple(map(float, c.split(','))) for c in q.split(';')]
tiles = []
for n, t in truth.items():
    if kind and kind not in n:
        continue
    r = res.get(n)
    if which == 'missed' and r is not None:
        continue
    if which == 'wrong':
        if r is None or max(min(math.dist(a, b) for b in r) for a in t) <= 0.02:
            continue
    im = cv2.imread(d + '/' + n)
    h, w = im.shape[:2]
    cv2.polylines(im, [np.array([[x * w, y * h] for x, y in t], np.int32)], True, (0, 200, 0), 2)
    if r:
        cv2.polylines(im, [np.array([[x * w, y * h] for x, y in r], np.int32)], True, (0, 0, 255), 2)
    s = 300 / h
    tiles.append(cv2.resize(im, (int(w * s), 300)))
    if len(tiles) >= 10:
        break
if tiles:
    rows = [np.hstack(tiles[i:i + 5]) for i in range(0, len(tiles), 5)]
    W = max(r.shape[1] for r in rows)
    rows = [cv2.copyMakeBorder(r, 0, 0, 0, W - r.shape[1], cv2.BORDER_CONSTANT) for r in rows]
    cv2.imwrite(out, np.vstack(rows))
print(len(tiles), 'shown')
