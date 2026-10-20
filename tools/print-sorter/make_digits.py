"""Handwritten numbers: EMNIST digits (NIST Special Database 19) set out like a number on paper."""
import gzip
import random
import sys

import cv2
import numpy as np

from common import ink_box, windows
from make_print import damage

ROOT = 'emnist/gzip/'  # EMNIST's gzip folder: https://www.nist.gov/itl/products-and-services/emnist-dataset


def load(split):
    raw = gzip.open(ROOT + f'emnist-digits-{split}-images-idx3-ubyte.gz').read()
    n = int.from_bytes(raw[4:8], 'big')
    # EMNIST is stored transposed.
    return np.frombuffer(raw[16:], np.uint8).reshape(n, 28, 28).transpose(0, 2, 1)


def glyph(img, height):
    ys, xs = np.nonzero(img > 40)
    g = img[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
    scale = height / g.shape[0]
    g = cv2.resize(g, (max(1, int(g.shape[1] * scale)), height), interpolation=cv2.INTER_AREA)
    # EMNIST strokes are a felt tip; a ballpoint is thinner.
    k = random.choice([0, 0, 1, 1, 2])
    if k and height > 24:
        g = cv2.erode(g, np.ones((k + 1, k + 1), np.uint8))
    return g


def number(digits_imgs):
    height = random.randint(16, 70)
    n = random.choice([1, 1, 1, 2, 2, 2, 3, 4])
    parts = [glyph(digits_imgs[random.randrange(len(digits_imgs))], int(height * random.uniform(0.85, 1.1))) for _ in range(n)]
    gap = [int(height * random.uniform(0.02, 0.35)) for _ in parts]
    W = sum(p.shape[1] for p in parts) + sum(gap) + 2 * height
    H = height * 3
    canvas = np.zeros((H, W), np.uint8)
    x = height
    for p, g in zip(parts, gap):
        y = height + int(height * random.uniform(-0.1, 0.1)) + (height - p.shape[0]) // 2
        h, w = p.shape
        canvas[y:y + h, x:x + w] = np.maximum(canvas[y:y + h, x:x + w], p)
        x += w + g
    if random.random() < 0.5:
        a = random.uniform(-8, 8)
        m = cv2.getRotationMatrix2D((W / 2, H / 2), a, 1.0)
        canvas = cv2.warpAffine(canvas, m, (W, H))
    return damage(255 - canvas)


def main(split, count, out, seed):
    random.seed(seed)
    np.random.seed(seed)
    imgs = load(split)
    X = []
    for _ in range(count):
        g = number(imgs)
        box = ink_box(g)
        if box is None:
            continue
        top, bottom, left, right = box
        ws, _ = windows(g, left, right, (top + bottom) // 2, bottom - top + 1)
        X.extend(ws)
    np.save(out, np.array(X, np.float16))
    print(out, len(X))


if __name__ == '__main__':
    main(sys.argv[1], int(sys.argv[2]), sys.argv[3], int(sys.argv[4]))
