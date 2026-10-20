"""Camera-like frames of white pages on off-white, busy surfaces, each with its true outline.

scene_*.png (640 px long side, like the camera's frames) and truth.txt: name x0,y0;x1,y1;... in
the frame's 0..1 coordinates, corners in the order top-left, top-right, bottom-right, bottom-left.
"""
import os
import random
import sys

import cv2
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
SCR = os.environ.get('BENCH_DATA', HERE)
PHOTO = os.path.join(SCR, 'pdf2', 'p0_0.png')
# The page in the photo (checked by eye against the detector's own answer).
PHOTO_QUAD = np.array([[0.064, 0.169], [0.917, 0.165], [0.912, 0.732], [0.054, 0.726]], np.float32)
PAGES = [os.path.join(SCR, 'flat', 'kt', f'p{i}_flat.png') for i in range(4)] + [os.path.join(SCR, 'clean', 'page.png')]


def texture(kind, w, h, rng):
    base = rng.uniform(185, 232)
    tint = np.array([rng.uniform(-8, 4), rng.uniform(-4, 4), rng.uniform(-2, 10)])  # BGR: warm or cool
    if kind == 'plain':
        g = np.full((h, w), base, np.float32) + rng.normal(0, 3, (h, w))
    elif kind == 'granite':
        g = np.full((h, w), base, np.float32) + rng.normal(0, 4, (h, w))
        n = int(w * h / rng.uniform(60, 250))
        ys, xs = rng.integers(0, h, n), rng.integers(0, w, n)
        vals = rng.uniform(40, 200, n)
        for y, x, v in zip(ys, xs, vals):
            cv2.circle(g, (int(x), int(y)), int(rng.integers(1, 4)), float(v), -1)
    elif kind == 'terrazzo':
        g = np.full((h, w), base, np.float32) + rng.normal(0, 3, (h, w))
        for _ in range(int(w * h / 900)):
            pts = (rng.normal(0, rng.uniform(3, 14), (5, 2)) + [rng.uniform(0, w), rng.uniform(0, h)]).astype(np.int32)
            cv2.fillPoly(g, [cv2.convexHull(pts)], float(rng.choice([rng.uniform(20, 80), rng.uniform(140, 230)])))
    elif kind == 'wood':
        y = np.arange(h)[:, None] + 6 * np.sin(np.arange(w)[None, :] / rng.uniform(40, 120))
        g = base + 10 * np.sin(y / rng.uniform(3, 9)) + rng.normal(0, 3, (h, w))
        g = cv2.GaussianBlur(g.astype(np.float32), (0, 0), 1.2)
    elif kind == 'marble':
        noise = cv2.resize(rng.normal(0, 1, (h // 16 + 1, w // 16 + 1)).astype(np.float32), (w, h), interpolation=cv2.INTER_CUBIC)
        veins = np.abs(np.sin((np.arange(w)[None, :] * 0.02 + np.arange(h)[:, None] * 0.01 + 4 * noise)))
        g = base - 35 * (veins < 0.08) + rng.normal(0, 3, (h, w))
        g = cv2.GaussianBlur(g.astype(np.float32), (0, 0), 1.0)
    else:  # carpet
        g = base + cv2.GaussianBlur(rng.normal(0, 18, (h, w)).astype(np.float32), (0, 0), 0.8)
    img = np.clip(np.stack([g] * 3, -1) + tint, 0, 255)
    return img.astype(np.float32)


def synthetic(rng, kind):
    W, H = 900, 1600
    bg = texture(kind, W, H, rng)
    page = cv2.imread(PAGES[rng.integers(len(PAGES))]).astype(np.float32)
    # Paper only a little brighter than the counter, sometimes barely.
    paper_level = rng.uniform(220, 250)
    page = page / max(np.percentile(page, 90), 1) * paper_level
    ph, pw = page.shape[:2]
    fill = rng.uniform(0.35, 0.75)
    scale = np.sqrt(fill * W * H / (pw * ph))
    w, h = pw * scale, ph * scale
    cx, cy = rng.uniform(w / 2 + 20, W - w / 2 - 20) if W - w > 40 else W / 2, rng.uniform(h / 2 + 20, H - h / 2 - 20) if H - h > 40 else H / 2
    a = np.deg2rad(rng.uniform(-12, 12))
    corners = np.array([[-w / 2, -h / 2], [w / 2, -h / 2], [w / 2, h / 2], [-w / 2, h / 2]])
    rot = np.array([[np.cos(a), -np.sin(a)], [np.sin(a), np.cos(a)]])
    dst = corners @ rot.T + [cx, cy]
    dst += rng.normal(0, w * 0.025, dst.shape)  # perspective
    m = cv2.getPerspectiveTransform(np.array([[0, 0], [pw, 0], [pw, ph], [0, ph]], np.float32), dst.astype(np.float32))
    warped = cv2.warpPerspective(page, m, (W, H), flags=cv2.INTER_AREA)
    mask = cv2.warpPerspective(np.ones((ph, pw), np.float32), m, (W, H))
    mask = cv2.GaussianBlur(mask, (3, 3), 0.7)[..., None]
    img = bg * (1 - mask) + warped * mask
    return img, dst / [W, H]


def photo(rng):
    img = cv2.imread(PHOTO).astype(np.float32)
    H, W = img.shape[:2]
    quad = PHOTO_QUAD * [W, H]
    # Zoom and recentre like a hand moving the phone.
    z = rng.uniform(0.9, 1.0)
    cw, ch = W * z, H * z
    x0 = rng.uniform(0, W - cw)
    y0 = rng.uniform(0, H - ch)
    a = np.deg2rad(rng.uniform(-5, 5))
    src = np.array([[x0, y0], [x0 + cw, y0], [x0 + cw, y0 + ch], [x0, y0 + ch]], np.float32)
    src += rng.normal(0, cw * 0.02, src.shape).astype(np.float32)
    c = src.mean(0)
    rot = np.array([[np.cos(a), -np.sin(a)], [np.sin(a), np.cos(a)]])
    src = ((src - c) @ rot.T + c).astype(np.float32)
    OW, OH = 720, 1600
    m = cv2.getPerspectiveTransform(src, np.array([[0, 0], [OW, 0], [OW, OH], [0, OH]], np.float32))
    out = cv2.warpPerspective(img, m, (OW, OH), flags=cv2.INTER_AREA, borderMode=cv2.BORDER_REFLECT)
    q = cv2.perspectiveTransform(quad[None].astype(np.float32), m)[0]
    return out, q / [OW, OH]


def camera(img, rng):
    h, w = img.shape[:2]
    X, Y = np.meshgrid(np.linspace(0, 1, w), np.linspace(0, 1, h))
    light = 1 - rng.uniform(0, 0.25) * (X if rng.random() < 0.5 else Y) - rng.uniform(0, 0.15) * ((X - 0.5) ** 2 + (Y - 0.5) ** 2)
    if rng.random() < 0.35:  # a soft shadow, like the phone's
        sx, sy = rng.uniform(0.2, 0.8), rng.uniform(0.2, 0.8)
        light *= 1 - rng.uniform(0.1, 0.3) * np.exp(-((X - sx) ** 2 + (Y - sy) ** 2) / rng.uniform(0.02, 0.1))
    img = img * light[..., None] * rng.uniform(0.85, 1.1)
    if rng.random() < 0.4:
        img = cv2.GaussianBlur(img, (0, 0), rng.uniform(0.5, 1.6))
    img = img + rng.normal(0, rng.uniform(1, 5), img.shape)
    img = np.clip(img, 0, 255).astype(np.uint8)
    s = 640 / max(h, w)
    img = cv2.resize(img, (int(w * s), int(h * s)), interpolation=cv2.INTER_AREA)
    ok, enc = cv2.imencode('.jpg', img, [cv2.IMWRITE_JPEG_QUALITY, int(rng.integers(60, 92))])
    return cv2.imdecode(enc, cv2.IMREAD_COLOR)


def main(out_dir, n_photo, n_synth, seed):
    rng = np.random.default_rng(seed)
    os.makedirs(out_dir, exist_ok=True)
    lines = []
    kinds = ['terrazzo', 'granite', 'wood', 'marble', 'carpet', 'plain']
    def inside(q):
        return q.min() >= 0.02 and q.max() <= 0.98
    for i in range(n_photo):
        img, q = photo(rng)
        while not inside(q):
            img, q = photo(rng)
        name = f'scene_photo_{i:03d}.png'
        cv2.imwrite(os.path.join(out_dir, name), camera(img, rng))
        lines.append(name + ' ' + ';'.join(f'{x:.4f},{y:.4f}' for x, y in q))
    for i in range(n_synth):
        kind = kinds[i % len(kinds)]
        img, q = synthetic(rng, kind)
        while not inside(q):
            img, q = synthetic(rng, kind)
        name = f'scene_{kind}_{i:03d}.png'
        cv2.imwrite(os.path.join(out_dir, name), camera(img, rng))
        lines.append(name + ' ' + ';'.join(f'{x:.4f},{y:.4f}' for x, y in q))
    open(os.path.join(out_dir, 'truth.txt'), 'w').write('\n'.join(lines) + '\n')
    print(len(lines), 'scenes')


if __name__ == '__main__':
    main(sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4]))
