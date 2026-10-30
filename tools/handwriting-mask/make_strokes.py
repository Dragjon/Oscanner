"""Handwriting strokes from GNHK pages, for pasting onto printed pages.

For every line of handwriting a page has, writes how dark the pen made each pixel (0 paper, 255
full ink) as a PNG, scaled so the line's median word is 48 px tall. Printed words GNHK marks as
such are left out.

    python make_strokes.py <GNHK train or test dir> <out dir>
"""
import glob
import json
import os
import sys

import cv2
import numpy as np

WORD = 48


def darkness(gray):
    """How far each pixel is below the paper around it, 0 to 1."""
    paper = cv2.medianBlur(cv2.dilate(gray, np.ones((7, 7), np.uint8)), 31).astype(np.float32)
    d = (paper - gray.astype(np.float32)) / np.maximum(paper, 1)
    # The paper's own grain isn't ink.
    return np.clip((d - 0.12) / 0.45, 0, 1)


def main(src, out):
    os.makedirs(out, exist_ok=True)
    n = 0
    for js in sorted(glob.glob(os.path.join(src, '*.json'))):
        img = cv2.imread(js[:-5] + '.jpg')
        if img is None:
            continue
        gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        words = json.load(open(js))
        lines = {}
        for w in words:
            p = w['polygon']
            pts = np.array([[p['x%d' % i], p['y%d' % i]] for i in range(4)], np.int32)
            lines.setdefault(w['line_idx'], []).append((w['type'], pts))
        dark = darkness(gray)
        printed = np.zeros(gray.shape, np.uint8)
        for t, pts in (x for v in lines.values() for x in v):
            if t == 'P':
                cv2.fillPoly(printed, [pts], 1)
        base = os.path.splitext(os.path.basename(js))[0]
        for idx, ws in lines.items():
            hw = [pts for t, pts in ws if t == 'H']
            if len(hw) < 2:
                continue
            keep = np.zeros(gray.shape, np.uint8)
            for pts in hw:
                cv2.fillPoly(keep, [pts], 1)
            keep = cv2.dilate(keep, np.ones((9, 9), np.uint8)) & (1 - printed)
            x, y, bw, bh = cv2.boundingRect(np.concatenate(hw))
            heights = sorted(cv2.boundingRect(p)[3] for p in hw)
            size = heights[len(heights) // 2]
            if size < 12:
                continue
            crop = (dark * keep)[y:y + bh, x:x + bw]
            s = WORD / size
            crop = cv2.resize(crop, None, fx=s, fy=s, interpolation=cv2.INTER_AREA)
            if crop.shape[0] < 20 or crop.shape[1] < 60 or crop.max() < 0.5:
                continue
            # GNHK blacks out names and numbers: a solid block is no pen stroke.
            if cv2.erode((crop > 0.7).astype(np.uint8), np.ones((11, 11), np.uint8)).any():
                continue
            cv2.imwrite(os.path.join(out, '%s_%02d.png' % (base, idx)), (crop * 255).astype(np.uint8))
            n += 1
    print(n, 'lines')


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
