"""Training frames for the page finder: a page (or none) on a surface, as the camera's analysis
frame would show it, in gray, with the page's mask.

Writes X (N, 128, 96) uint8 frames and Y (N, 128, 96) uint8 masks (255 = page) to an .npz.
Frames are squeezed to 96x128 whatever their shape; outlines are in 0..1 so squeezing keeps them.
"""
import os
import sys

import cv2
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
# Surfaces, pages and the camera's roughening are shared with the detector benchmark.
sys.path.insert(0, os.path.join(os.path.dirname(HERE), 'detector-bench'))
import make_scenes as ms  # noqa: E402

OUT_H, OUT_W = 128, 96


def text_page(rng, w, h):
    """A made-up page: lines of grey "words", sometimes rules, a table, a picture."""
    paper = rng.uniform(205, 255)
    g = np.full((h, w), paper, np.float32)
    margin = int(w * rng.uniform(0.05, 0.12))
    y = margin
    line = rng.uniform(0.012, 0.03) * h
    ink = rng.uniform(10, 90)
    while y < h - margin:
        if rng.random() < 0.08:
            # a picture or logo block
            bh = int(rng.uniform(0.05, 0.2) * h)
            bw = int(rng.uniform(0.2, 0.6) * w)
            x0 = margin + int(rng.uniform(0, w - 2 * margin - bw)) if w - 2 * margin - bw > 0 else margin
            cv2.rectangle(g, (x0, int(y)), (x0 + bw, int(y) + bh), float(rng.uniform(40, 200)), -1)
            y += bh + line
            continue
        x = margin + (rng.uniform(0, 0.1) * w if rng.random() < 0.2 else 0)
        end = w - margin - rng.uniform(0, 0.3) * w
        while x < end:
            ww = rng.uniform(0.02, 0.12) * w
            cv2.rectangle(g, (int(x), int(y)), (int(min(x + ww, end)), int(y + line * 0.6)), float(ink), -1)
            x += ww + line * 0.5
        if rng.random() < 0.15:
            cv2.line(g, (margin, int(y + line)), (w - margin, int(y + line)), float(ink + 40), 1)
        y += line * rng.uniform(1.4, 2.4)
    return np.stack([g] * 3, -1)


def page_image(rng):
    if rng.random() < 0.35:
        p = cv2.imread(ms.PAGES[rng.integers(len(ms.PAGES))]).astype(np.float32)
        return p / max(np.percentile(p, 90), 1) * rng.uniform(205, 252)
    w = int(rng.uniform(600, 900))
    ratio = rng.choice([1.414, 1.294, 1.5, 0.63, 1.0, 1.7])  # A4, Letter, receipt-ish, card, square, long
    return text_page(rng, w, int(w * ratio))


def background(rng, w, h):
    r = rng.random()
    if r < 0.6:
        kind = ['terrazzo', 'granite', 'wood', 'marble', 'carpet', 'plain'][rng.integers(6)]
        img = ms.texture(kind, w, h, rng)
        if rng.random() < 0.35:  # darker surfaces too
            img *= rng.uniform(0.25, 0.7)
        return img
    if r < 0.8:
        # gradient or flat colour
        c1 = rng.uniform(20, 240, 3)
        c2 = rng.uniform(20, 240, 3)
        t = np.linspace(0, 1, h)[:, None, None]
        return (c1 * (1 - t) + c2 * t) * np.ones((h, w, 3)) + rng.normal(0, 3, (h, w, 3))
    # clutter: rectangles and blobs of all sorts, some paper-like
    img = np.full((h, w, 3), rng.uniform(30, 220), np.float32) + rng.normal(0, 4, (h, w, 3))
    for _ in range(int(rng.integers(3, 12))):
        c = rng.uniform(0, 255, 3)
        x, y = rng.integers(0, w), rng.integers(0, h)
        if rng.random() < 0.6:
            cv2.rectangle(img, (int(x), int(y)), (int(x + rng.uniform(20, w / 2)), int(y + rng.uniform(20, h / 3))), c.tolist(), -1)
        else:
            cv2.circle(img, (int(x), int(y)), int(rng.uniform(10, w / 5)), c.tolist(), -1)
    return img


def scene(rng):
    W, H = (480, 640) if rng.random() < 0.6 else (360, 640)
    bg = background(rng, W, H).astype(np.float32)
    mask = np.zeros((H, W), np.float32)
    if rng.random() < 0.08:
        return bg, mask  # no page at all
    page = page_image(rng)
    ph, pw = page.shape[:2]
    fill = rng.uniform(0.15, 0.85)
    scale = np.sqrt(fill * W * H / (pw * ph))
    w, h = pw * scale, ph * scale
    if w > W * 0.98 or h > H * 0.98:
        s = min(W * 0.98 / w, H * 0.98 / h)
        w, h = w * s, h * s
    cx = rng.uniform(w / 2, W - w / 2)
    cy = rng.uniform(h / 2, H - h / 2)
    a = np.deg2rad(rng.uniform(-25, 25) if rng.random() < 0.8 else rng.uniform(-90, 90))
    corners = np.array([[-w / 2, -h / 2], [w / 2, -h / 2], [w / 2, h / 2], [-w / 2, h / 2]])
    rot = np.array([[np.cos(a), -np.sin(a)], [np.sin(a), np.cos(a)]])
    dst = corners @ rot.T + [cx, cy]
    dst += rng.normal(0, w * 0.04, dst.shape)
    if rng.random() < 0.15:  # partly out of the frame
        dst += rng.normal(0, w * 0.15, (1, 2))
    src = np.array([[0, 0], [pw, 0], [pw, ph], [0, ph]], np.float32)
    m = cv2.getPerspectiveTransform(src, dst.astype(np.float32))
    warped = cv2.warpPerspective(page, m, (W, H), flags=cv2.INTER_AREA)
    pm = cv2.warpPerspective(np.ones((ph, pw), np.float32), m, (W, H))
    soft = cv2.GaussianBlur(pm, (3, 3), 0.7)[..., None]
    img = bg * (1 - soft) + warped * soft
    # A hand or finger sometimes holds an edge.
    if rng.random() < 0.15:
        edge = dst[rng.integers(4)]
        cv2.ellipse(img, (int(edge[0]), int(edge[1])), (int(w * 0.06), int(w * 0.15)), float(rng.uniform(0, 180)), 0, 360,
                    [float(rng.uniform(60, 120)), float(rng.uniform(100, 160)), float(rng.uniform(150, 220))], -1)
    mask = pm
    return img, mask


def main(n, out, seed):
    rng = np.random.default_rng(seed)
    X = np.zeros((n, OUT_H, OUT_W), np.uint8)
    Y = np.zeros((n, OUT_H, OUT_W), np.uint8)
    for i in range(n):
        img, mask = scene(rng)
        frame = ms.camera(img, rng)  # light, blur, noise, JPEG; 640 px long side
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        X[i] = cv2.resize(gray, (OUT_W, OUT_H), interpolation=cv2.INTER_AREA)
        Y[i] = (cv2.resize(mask, (OUT_W, OUT_H), interpolation=cv2.INTER_AREA) > 0.5) * 255
        if i % 1000 == 0:
            print(i, flush=True)
    np.savez_compressed(out, X=X, Y=Y)
    print(out, n)


if __name__ == '__main__':
    main(int(sys.argv[1]), sys.argv[2], int(sys.argv[3]))
