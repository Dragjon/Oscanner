"""What the print/handwriting classifier sees, shared by training and by the on-phone code.

A run of text (a line or part of one) of height H is cut into windows 1.25*H tall and 2.5*H
wide, centered on the run's middle row, stepping H across. Each window is grayscale, scaled
to 32x64, and normalized so paper is ~0 and the darkest ink ~1.
"""
import cv2
import numpy as np

WIN_H, WIN_W = 32, 64
TALL, WIDE, STEP = 1.25, 2.5, 1.0


def normalize(gray):
    """Paper to 0, ink to 1, from the window's own levels."""
    g = gray.astype(np.float32)
    hi = np.percentile(g, 90)
    lo = min(np.percentile(g, 2), hi - 25)
    return np.clip((hi - g) / max(hi - lo, 1.0), 0, 1)


def windows(gray, x0, x1, cy, h):
    """Windows across the run from x0 to x1 around row cy, for run height h."""
    wh = int(round(h * TALL))
    ww = int(round(h * WIDE))
    step = max(1, int(round(h * STEP)))
    H, W = gray.shape
    out, centers = [], []
    xs = list(range(x0 + ww // 2 - ww // 4, max(x0 + ww // 2 - ww // 4, x1 - ww // 4) + 1, step)) or [(x0 + x1) // 2]
    for cx in xs:
        top = cy - wh // 2
        left = cx - ww // 2
        crop = np.full((wh, ww), 255, np.uint8)
        sy0, sy1 = max(0, top), min(H, top + wh)
        sx0, sx1 = max(0, left), min(W, left + ww)
        if sy1 <= sy0 or sx1 <= sx0:
            continue
        crop[sy0 - top:sy1 - top, sx0 - left:sx1 - left] = gray[sy0:sy1, sx0:sx1]
        small = cv2.resize(crop, (WIN_W, WIN_H), interpolation=cv2.INTER_AREA)
        out.append(normalize(small))
        centers.append(cx)
    return out, centers


def ink_box(gray, block=31, c=15):
    """The box around the run's real ink marks: specks left out, as the app finds them."""
    ink = cv2.adaptiveThreshold(gray, 255, cv2.ADAPTIVE_THRESH_MEAN_C, cv2.THRESH_BINARY_INV, block, c)
    n, lab, st, _ = cv2.connectedComponentsWithStats(ink, 8)
    big = [i for i in range(1, n) if st[i, 4] >= 6 and st[i, 3] >= 4]
    if not big:
        return None
    hs = np.median([st[i, 3] for i in big])
    big = [i for i in big if st[i, 3] >= 0.3 * hs]
    x0 = min(st[i, 0] for i in big); y0 = min(st[i, 1] for i in big)
    x1 = max(st[i, 0] + st[i, 2] for i in big) - 1; y1 = max(st[i, 1] + st[i, 3] for i in big) - 1
    return y0, y1, x0, x1
