"""GNHK windows: handwritten (type H) and printed (type P) words, straightened from their outlines."""
import glob
import json
import os
import random
import sys

import cv2
import numpy as np

from common import ink_box, windows


def word_crop(gray, poly, grow=0.25):
    pts = np.array([[poly['x%d' % i], poly['y%d' % i]] for i in range(4)], np.float32)
    rect = cv2.minAreaRect(pts)
    (cx, cy), (w, h), a = rect
    if w < h:
        w, h = h, w
        a += 90
    w2, h2 = w + 2 * grow * h, h * (1 + 2 * grow)
    box = cv2.boxPoints(((cx, cy), (w2, h2), a)).astype(np.float32)
    # Order the corners: top-left, top-right, bottom-right, bottom-left.
    s = box.sum(1)
    d = np.diff(box, axis=1).ravel()
    tl, br = box[np.argmin(s)], box[np.argmax(s)]
    tr, bl = box[np.argmin(d)], box[np.argmax(d)]
    W, H = int(round(w2)), int(round(h2))
    if W < 8 or H < 8:
        return None
    m = cv2.getPerspectiveTransform(np.array([tl, tr, br, bl]), np.array([[0, 0], [W, 0], [W, H], [0, H]], np.float32))
    return cv2.warpPerspective(gray, m, (W, H), flags=cv2.INTER_AREA, borderValue=255)


def main(split_dir, out_prefix, seed):
    random.seed(seed)
    hand, printed = [], []
    files = sorted(glob.glob(os.path.join(split_dir, '*.json')))
    for n, jf in enumerate(files):
        img = cv2.imread(jf[:-5] + '.jpg', cv2.IMREAD_GRAYSCALE)
        if img is None:
            continue
        for w in json.load(open(jf)):
            crop = word_crop(img, w['polygon'])
            if crop is None:
                continue
            # Real words are seen at many sizes on a page; so is their scaling here.
            scale = random.uniform(0.15, 0.6)
            crop = cv2.resize(crop, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
            box = ink_box(crop)
            if box is None:
                continue
            top, bottom, left, right = box
            h = bottom - top + 1
            if h < 6:
                continue
            ws, _ = windows(crop, left, right, (top + bottom) // 2, h)
            (hand if w['type'] == 'H' else printed).extend(ws)
        if n % 100 == 0:
            print(n, len(hand), len(printed), flush=True)
    np.save(out_prefix + '_hand.npy', np.array(hand, np.float16))
    np.save(out_prefix + '_print.npy', np.array(printed, np.float16))
    print(out_prefix, len(hand), len(printed))


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2], int(sys.argv[3]))
