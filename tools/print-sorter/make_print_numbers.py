"""Printed numbers alone, in the same mix of typefaces and scan damage: the counterpart to the
handwritten numbers, so neither kind of number is taken for the other."""
import random
import sys

import numpy as np

import make_print as mp
from common import ink_box, windows


def text():
    r = random.random()
    if r < 0.3:
        return str(random.randint(0, 9))
    if r < 0.55:
        return str(random.randint(10, 99))
    if r < 0.75:
        return str(random.randint(100, 99999))
    if r < 0.85:
        return f"{random.randint(1, 31)}/{random.randint(1, 12)}/{random.randint(2000, 2030)}"
    if r < 0.93:
        return f"{random.randint(0, 999)}.{random.randint(0, 99):02d}"
    return random.choice(["(", "", "Q", "No. ", "$"]) + str(random.randint(1, 50)) + random.choice([")", ".", "", "%", ""])


def main(count, out, seed):
    random.seed(seed)
    np.random.seed(seed)
    fs = mp.fonts()
    X = []
    for _ in range(count):
        try:
            g = mp.damage(mp.render(text(), random.choice(fs), random.randint(14, 64)))
        except OSError:
            continue
        b = ink_box(g)
        if b is None:
            continue
        ws, _ = windows(g, b[2], b[3], (b[0] + b[1]) // 2, b[1] - b[0] + 1)
        X.extend(ws)
    np.save(out, np.array(X, np.float16))
    print(out, len(X))


if __name__ == '__main__':
    main(int(sys.argv[1]), sys.argv[2], int(sys.argv[3]))
