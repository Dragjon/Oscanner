"""Harder scenes for the page finder, from photos users sent: pages on patterned cloth (bedsheets,
tablecloths), booklets with their stacked edges showing, and curved pages and open spreads.

scene(rng) returns (BGR float image, page mask 0..1); the mask is the whole visible top sheet,
or both pages of an open spread, never the edges of the pages under it.
"""
import cv2
import numpy as np

import gen


def cloth(w, h, rng):
    """A woven or printed pattern: repeating motifs on a light ground, with folds' soft shading."""
    ground = rng.uniform(150, 225)
    ink = rng.uniform(40, 140)
    tint = np.array([rng.uniform(-15, 15), rng.uniform(-10, 10), rng.uniform(-15, 25)])
    g = np.full((h, w), ground, np.float32)
    kind = rng.integers(4)
    cell = rng.uniform(40, 140)
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    a = rng.uniform(-0.6, 0.6)
    u = xx * np.cos(a) + yy * np.sin(a)
    v = -xx * np.sin(a) + yy * np.cos(a)
    if kind == 0:  # diamonds, like the bedsheet
        d = np.abs((u % cell) - cell / 2) + np.abs((v % cell) - cell / 2)
        g[(d < cell * 0.28) & (d > cell * 0.18)] = ink
        g[d < cell * 0.08] = ink
        g[((u // cell + v // cell) % 2 == 0) & (np.abs((v % cell) - cell / 2) < cell * 0.03)] = ink
    elif kind == 1:  # stripes, several widths
        s = (u % cell) / cell
        g[(s < 0.12) | ((s > 0.4) & (s < 0.46))] = ink
        g[(s > 0.7) & (s < 0.74)] = (ink + ground) / 2
    elif kind == 2:  # checks / gingham
        s, t = (u % cell) / cell < 0.45, (v % cell) / cell < 0.45
        g[s ^ t] = (ink + ground) / 2
        g[s & t] = ink
    else:  # scattered small motifs: dots, crosses
        for _ in range(int(w * h / (cell * cell) * 2)):
            x, y = int(rng.uniform(0, w)), int(rng.uniform(0, h))
            r = int(cell * rng.uniform(0.05, 0.15))
            if rng.random() < 0.5:
                cv2.circle(g, (x, y), r, float(ink), -1)
            else:
                cv2.line(g, (x - r, y), (x + r, y), float(ink), max(1, r // 3))
                cv2.line(g, (x, y - r), (x, y + r), float(ink), max(1, r // 3))
    # Folds: broad soft light and shade.
    folds = cv2.resize(rng.normal(0, 1, (h // 80 + 2, w // 80 + 2)).astype(np.float32), (w, h), interpolation=cv2.INTER_CUBIC)
    g = g * (1 + 0.08 * folds) + rng.normal(0, 3, (h, w))
    return np.clip(np.stack([g] * 3, -1) + tint, 0, 255).astype(np.float32)


def curved(page, rng, bend):
    """[page] with its top and bottom edges bowed by [bend] (share of its height), as a page lifts."""
    ph, pw = page.shape[:2]
    pad = int(abs(bend) * ph) + 2
    out = np.zeros((ph + 2 * pad, pw, 3), np.float32)
    mask = np.zeros((ph + 2 * pad, pw), np.float32)
    x = np.arange(pw)
    # One side of the page (the spine side) lifts most.
    lift = bend * ph * (1 - x / pw) ** 2 if rng.random() < 0.5 else bend * ph * np.sin(np.pi * x / pw)
    map_x = np.tile(x.astype(np.float32), (ph + 2 * pad, 1))
    map_y = (np.arange(ph + 2 * pad)[:, None] - pad + lift[None, :]).astype(np.float32)
    out = cv2.remap(page, map_x, map_y, cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=0)
    mask = cv2.remap(np.ones((ph, pw), np.float32), map_x, map_y, cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=0)
    return out, mask


def booklet(rng):
    """A booklet page: the top sheet, with the edges of the pages under it showing on one side."""
    page = gen.page_image(rng)
    ph, pw = page.shape[:2]
    strips = int(rng.integers(3, 12))
    step = max(1, int(pw * rng.uniform(0.002, 0.006)))
    side = int(rng.integers(2))  # left or right
    W = pw + strips * step + 4
    img = np.zeros((ph + strips * step, W, 3), np.float32)
    alpha = np.zeros(img.shape[:2], np.float32)
    top = np.zeros(img.shape[:2], np.float32)
    for i in range(strips, 0, -1):
        o = i * step
        x0 = (o if side == 0 else 0)
        x0 = strips * step - o if side == 0 else o
        shade = rng.uniform(150, 235)
        cv2.rectangle(img, (x0, o // 2), (x0 + pw, o // 2 + ph), (shade, shade, shade), -1)
        cv2.rectangle(alpha, (x0, o // 2), (x0 + pw, o // 2 + ph), 1.0, -1)
        cv2.line(img, (x0 + (pw if side else 0), o // 2), (x0 + (pw if side else 0), o // 2 + ph), (shade * 0.7,) * 3, 1)
    x0 = strips * step if side == 0 else 0
    img[0:ph, x0:x0 + pw] = page
    alpha[0:ph, x0:x0 + pw] = 1
    top[0:ph, x0:x0 + pw] = 1
    return img, alpha, top


def spread(rng):
    """An open book or booklet: two pages side by side, the gutter shaded, the pages bowed."""
    left = gen.page_image(rng)
    ph, pw = left.shape[:2]
    right = cv2.resize(gen.page_image(rng), (pw, ph))
    gutter = np.linspace(0, 1, pw)[None, :, None]
    shade = rng.uniform(0.25, 0.6)
    left = left * (1 - shade * gutter ** 6)
    right = right * (1 - shade * (1 - gutter) ** 6)
    img = np.concatenate([left, right], 1)
    bend = rng.uniform(0, 0.06)
    img, mask = curved(img, rng, bend)
    return img, mask, mask


def scene(rng):
    W, H = (480, 640) if rng.random() < 0.6 else (360, 640)
    r = rng.random()
    bg = cloth(W, H, rng) if r < 0.7 else gen.background(rng, W, H).astype(np.float32)
    kind = rng.random()
    if kind < 0.35:
        sheet, alpha, top = booklet(rng)
    elif kind < 0.7:
        sheet, alpha, top = spread(rng)
    else:
        page = gen.page_image(rng)
        sheet, alpha = curved(page, rng, rng.uniform(-0.05, 0.05))
        top = alpha
    ph, pw = sheet.shape[:2]
    fill = rng.uniform(0.25, 0.85)
    scale = np.sqrt(fill * W * H / (pw * ph))
    w, h = pw * scale, ph * scale
    if w > W * 0.98 or h > H * 0.98:
        s = min(W * 0.98 / w, H * 0.98 / h)
        w, h = w * s, h * s
    cx = rng.uniform(w / 2, W - w / 2)
    cy = rng.uniform(h / 2, H - h / 2)
    a = np.deg2rad(rng.uniform(-20, 20) if rng.random() < 0.8 else rng.uniform(-90, 90))
    corners = np.array([[-w / 2, -h / 2], [w / 2, -h / 2], [w / 2, h / 2], [-w / 2, h / 2]])
    rot = np.array([[np.cos(a), -np.sin(a)], [np.sin(a), np.cos(a)]])
    dst = corners @ rot.T + [cx, cy]
    dst += rng.normal(0, w * 0.04, dst.shape)
    if rng.random() < 0.2:  # running off the frame, as a spread often does
        dst += rng.normal(0, w * 0.15, (1, 2))
    src = np.array([[0, 0], [pw, 0], [pw, ph], [0, ph]], np.float32)
    m = cv2.getPerspectiveTransform(src, dst.astype(np.float32))
    warped = cv2.warpPerspective(sheet, m, (W, H), flags=cv2.INTER_AREA)
    am = cv2.warpPerspective(alpha, m, (W, H))
    tm = cv2.warpPerspective(top, m, (W, H))
    soft = cv2.GaussianBlur(am, (3, 3), 0.7)[..., None]
    img = bg * (1 - soft) + warped * soft
    return img, tm


def write(n, out, seed):
    """Like gen.main: n frames and masks at the finder's input size, to an .npz."""
    import make_scenes as ms
    rng = np.random.default_rng(seed)
    X = np.zeros((n, gen.OUT_H, gen.OUT_W), np.uint8)
    Y = np.zeros((n, gen.OUT_H, gen.OUT_W), np.uint8)
    for i in range(n):
        img, mask = scene(rng)
        frame = ms.camera(img, rng)
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        X[i] = cv2.resize(gray, (gen.OUT_W, gen.OUT_H), interpolation=cv2.INTER_AREA)
        Y[i] = (cv2.resize(mask, (gen.OUT_W, gen.OUT_H), interpolation=cv2.INTER_AREA) > 0.5) * 255
        if i % 1000 == 0:
            print(i, flush=True)
    np.savez_compressed(out, X=X, Y=Y)
    print(out, n)


if __name__ == '__main__':
    import sys
    if len(sys.argv) > 3:
        write(int(sys.argv[1]), sys.argv[2], int(sys.argv[3]))
        sys.exit()
    rng = np.random.default_rng(int(sys.argv[2]) if len(sys.argv) > 2 else 1)
    import make_scenes as ms
    tiles = []
    for _ in range(int(sys.argv[1]) if len(sys.argv) > 1 else 8):
        img, mask = scene(rng)
        frame = ms.camera(img, rng)
        over = frame.copy()
        over[mask > 0.5] = over[mask > 0.5] * 0.6 + np.array([0, 0, 255]) * 0.4
        tiles.append(cv2.resize(np.hstack([frame, over]), (480, 320)))
    cv2.imwrite('hard_preview.png', np.vstack(tiles))
