"""Cards for the card finder (card.onnx): ID-1 cards (85.6 × 54 mm) on desks, cloth and paper.

From what real cards look like in a camera: often no brighter than what they lie on (a white card
on a pale sheet), so that only the thin dark line of their edge shows; a colored band across the
top or down one side; a photo, a chip, text and barcodes; rounded corners; a thumb over an edge.
The mask is the card alone, never a page or a patch of cloth around it, and some frames have no
card at all.

    python cards.py <n> <out.npz> <seed>     frames and masks at the finder's input size
    python cards.py <n>                      cards_preview.png, frames beside their masks
"""
import sys

import cv2
import numpy as np

import gen
import hard
import make_scenes as ms

CW, CH = 856, 540
# Drawn at half a camera frame's size: the finder sees it at 96x128 anyway.
W, H = 450, 800


def face(rng):
    """A card's face, CW × CH, BGR float, and its rounded-corner mask."""
    kind = rng.random()
    if kind < 0.35:
        ground = rng.uniform(215, 250, 3)
    elif kind < 0.75:
        ground = rng.uniform(150, 240, 3)
    else:
        ground = rng.uniform(30, 160, 3)
    t = np.linspace(0, 1, CW)[None, :, None]
    img = (ground * (1 - t) + np.clip(ground + rng.normal(0, 30, 3), 0, 255) * t) * np.ones((CH, CW, 3))
    yy, xx = np.mgrid[0:CH, 0:CW].astype(np.float32)
    if rng.random() < 0.6:  # fine wavy security lines
        w = np.sin(yy * rng.uniform(0.02, 0.06) + 6 * np.sin(xx * rng.uniform(0.005, 0.02)))
        img -= (np.abs(w) < 0.15)[..., None] * rng.uniform(8, 30)
    ink = np.clip(ground - rng.uniform(80, 170), 0, 255) if ground.mean() > 120 else np.clip(ground + 120, 0, 255)
    band = rng.uniform(0, 255, 3)
    r = rng.random()
    if r < 0.4:  # a band across the top
        h = int(CH * rng.uniform(0.12, 0.25))
        img[:h] = img[:h] * 0.25 + band * 0.75
    elif r < 0.7:  # a band down one side, as on a library card
        w = int(CW * rng.uniform(0.2, 0.35))
        sl = slice(CW - w, CW) if rng.random() < 0.5 else slice(0, w)
        img[:, sl] = img[:, sl] * 0.2 + band * 0.8
    if rng.random() < 0.7:  # a photo with a face-ish blob
        px, py, pw, ph = int(CW * rng.uniform(0.04, 0.1)), int(CH * rng.uniform(0.25, 0.32)), int(CW * 0.25), int(CH * 0.55)
        if rng.random() < 0.5:
            px = CW - px - pw
        img[py:py + ph, px:px + pw] = rng.uniform(110, 230, 3)
        cv2.ellipse(img, (px + pw // 2, py + ph // 2 - ph // 10), (pw // 4, ph // 4), 0, 0, 360, rng.uniform(60, 200, 3).tolist(), -1)
        cv2.ellipse(img, (px + pw // 2, py + ph), (pw // 2 - 8, ph // 3), 0, 180, 360, rng.uniform(10, 120, 3).tolist(), -1)
    for i in range(int(rng.integers(2, 8))):  # text
        y = int(CH * rng.uniform(0.2, 0.9))
        x = int(CW * rng.uniform(0.05, 0.5))
        while x < CW * rng.uniform(0.5, 0.9):
            wl = int(rng.uniform(15, 70))
            cv2.rectangle(img, (x, y), (x + wl, y + int(rng.uniform(8, 18))), ink.tolist(), -1)
            x += wl + 10
    if rng.random() < 0.4:  # a barcode
        x0, y0 = int(CW * rng.uniform(0.1, 0.5)), int(CH * rng.uniform(0.5, 0.75))
        x = x0
        while x < x0 + CW * 0.35:
            bw = int(rng.integers(2, 7))
            cv2.rectangle(img, (x, y0), (x + bw, y0 + int(CH * 0.15)), (20, 20, 20), -1)
            x += bw + int(rng.integers(2, 7))
    if rng.random() < 0.3:  # a chip
        cx, cy = int(CW * 0.1), int(CH * 0.42)
        cv2.rectangle(img, (cx, cy), (cx + 90, cy + 70), (60, 170, 210), -1)
    img = np.clip(img + rng.normal(0, 2, img.shape), 0, 255).astype(np.float32)
    r = int(CW * 0.037)
    mask = np.zeros((CH, CW), np.float32)
    cv2.rectangle(mask, (r, 0), (CW - r, CH), 1, -1)
    cv2.rectangle(mask, (0, r), (CW, CH - r), 1, -1)
    for cx, cy in [(r, r), (CW - r, r), (r, CH - r), (CW - r, CH - r)]:
        cv2.circle(mask, (cx, cy), r, 1, -1)
    return img, mask


def plain_sheet(rng):
    """A pale bedsheet or tablecloth with only its folds' shading, maybe a few faint motifs."""
    g = np.full((H, W), rng.uniform(140, 225), np.float32)
    folds = cv2.resize(rng.normal(0, 1, (H // 60 + 2, W // 60 + 2)).astype(np.float32), (W, H), interpolation=cv2.INTER_CUBIC)
    g = g * (1 + rng.uniform(0.04, 0.12) * folds) + rng.normal(0, 3, (H, W))
    tint = np.array([rng.uniform(-10, 10), rng.uniform(-8, 8), rng.uniform(-10, 15)])
    return np.clip(np.stack([g] * 3, -1) + tint, 0, 255).astype(np.float32)


def surface(rng):
    r = rng.random()
    if r < 0.35:
        return hard.cloth(W, H, rng)
    if r < 0.55:
        return plain_sheet(rng)
    return gen.background(rng, W, H).astype(np.float32)


def place(rng, w, h, fill_range):
    """Four corners for a w × h thing turned any way, in view, filling about fill_range of the frame."""
    fill = rng.uniform(*fill_range)
    s = np.sqrt(fill * W * H / (w * h))
    w, h = w * s, h * s
    a = rng.uniform(0, 2 * np.pi) if rng.random() < 0.5 else np.deg2rad(rng.choice([0, 90, 180, 270]) + rng.uniform(-15, 15))
    rot = np.array([[np.cos(a), -np.sin(a)], [np.sin(a), np.cos(a)]])
    corners = np.array([[-w / 2, -h / 2], [w / 2, -h / 2], [w / 2, h / 2], [-w / 2, h / 2]]) @ rot.T
    span = corners.max(0) - corners.min(0)
    k = min(1.0, 0.92 * W / span[0], 0.92 * H / span[1])
    corners *= k
    span *= k
    c = [rng.uniform(span[0] / 2 + 10, W - span[0] / 2 - 10), rng.uniform(span[1] / 2 + 10, H - span[1] / 2 - 10)]
    return corners + c + rng.normal(0, w * k * 0.025, (4, 2))


def paste(img, sheet, alpha, dst):
    sh, sw = alpha.shape
    m = cv2.getPerspectiveTransform(np.array([[0, 0], [sw, 0], [sw, sh], [0, sh]], np.float32), dst.astype(np.float32))
    warped = cv2.warpPerspective(sheet, m, (W, H), flags=cv2.INTER_AREA)
    am = cv2.warpPerspective(alpha, m, (W, H))
    soft = cv2.GaussianBlur(am, (3, 3), 0.7)[..., None]
    return img * (1 - soft) + warped * soft, am


def edge_line(img, inside, rng):
    """The card's edge: its thickness and the shadow it casts, a thin darker line, on the sides
    away from the light or all round."""
    u8 = inside.astype(np.uint8)
    width = int(rng.integers(2, 5))
    rim = (cv2.dilate(u8, np.ones((width, width), np.uint8)) - cv2.erode(u8, np.ones((3, 3), np.uint8))).astype(bool)
    ys, xs = np.nonzero(rim)
    iy, ix = np.nonzero(inside)
    facing = np.cos(np.arctan2(ys - iy.mean(), xs - ix.mean()) - rng.uniform(0, 2 * np.pi))
    keep = facing < (rng.uniform(-0.2, 0.6) if rng.random() < 0.7 else 2)
    dark = np.zeros(inside.shape, np.float32)
    dark[ys[keep], xs[keep]] = rng.uniform(0.2, 0.5)
    return img * (1 - cv2.GaussianBlur(dark, (3, 3), 0.8)[..., None])


def decoy(rng):
    """Something rectangular that isn't a card: a screen showing a pattern, a framed box, a
    shelf of books, a slab of wood or cloth. Some are card-shaped; none goes in the mask."""
    w = int(rng.uniform(120, 420))
    h = int(w / rng.uniform(1.2, 2.0)) if rng.random() < 0.6 else int(rng.uniform(80, 420))
    kind = rng.integers(5)
    if kind == 0:  # a screen: dark bezel round a checkerboard or stripes
        cell = int(rng.uniform(8, 30))
        yy, xx = np.mgrid[0:h, 0:w]
        pat = ((yy // cell + xx // cell) % 2) if rng.random() < 0.6 else ((xx // cell) % 2)
        g = np.where(pat, rng.uniform(140, 220), rng.uniform(0, 40)).astype(np.float32)
        b = max(3, int(min(w, h) * 0.04))
        g[:b], g[-b:], g[:, :b], g[:, -b:] = 15, 15, 15, 15
        img = np.stack([g] * 3, -1)
    elif kind == 1:  # shelves: dark frame, vertical bars of books
        img = np.full((h, w, 3), rng.uniform(120, 200), np.float32)
        x = 0
        while x < w:
            bw = int(rng.uniform(4, 14))
            img[:, x:x + bw] = rng.uniform(30, 160)
            x += bw + int(rng.uniform(0, 4))
        for y in range(0, h, max(20, h // int(rng.integers(2, 5)))):
            img[y:y + 4] = rng.uniform(150, 220)
    elif kind == 2:  # a box or frame: plain face, a darker border
        img = np.full((h, w, 3), rng.uniform(60, 230, 3), np.float32)
        b = max(3, int(min(w, h) * rng.uniform(0.03, 0.12)))
        img[:b], img[-b:], img[:, :b], img[:, -b:] = [rng.uniform(0, 255, 3)] * 4
    elif kind == 3:  # a slab of wood
        img = ms.texture('wood', w, h, rng) * rng.uniform(0.5, 1.0)
    else:  # a patch of cloth
        img = hard.cloth(w, h, rng)
    return img.astype(np.float32), np.ones((h, w), np.float32)


def scene(rng):
    """(BGR float frame, card mask 0..1)."""
    img = surface(rng)
    if rng.random() < 0.45:  # things that aren't cards
        for _ in range(int(rng.integers(1, 4))):
            d, a = decoy(rng)
            img, _ = paste(img, d, a, place(rng, d.shape[1], d.shape[0], (0.04, 0.35)))
    if rng.random() < 0.2:  # on a sheet of paper
        page = gen.page_image(rng).astype(np.float32)
        img, _ = paste(img, page, np.ones(page.shape[:2], np.float32), place(rng, page.shape[1], page.shape[0], (0.3, 0.7)))
    if rng.random() < 0.12:  # no card
        return img, np.zeros((H, W), np.float32)
    card, alpha = face(rng)
    dst = place(rng, CW, CH, (0.04, 0.45))
    luma = lambda im: im[..., 0] * 0.114 + im[..., 1] * 0.587 + im[..., 2] * 0.299
    if rng.random() < 0.5:
        # About as bright as what it lies on: then only its edge shows.
        m = cv2.getPerspectiveTransform(np.array([[0, 0], [CW, 0], [CW, CH], [0, CH]], np.float32), dst.astype(np.float32))
        where = cv2.warpPerspective(alpha, m, (W, H)) > 0.5
        ring = cv2.dilate(where.astype(np.uint8), np.ones((21, 21), np.uint8)).astype(bool) & ~where
        card = card * (luma(img)[ring].mean() * rng.uniform(0.95, 1.1) / max(1.0, luma(card)[alpha > 0.5].mean()))
        card = np.clip(card, 0, 255)
    img, am = paste(img, card, alpha, dst)
    img = edge_line(img, am > 0.5, rng)
    if rng.random() < 0.3:  # a thumb over an edge
        edge = cv2.Canny((am > 0.5).astype(np.uint8) * 255, 50, 150)
        ey, ex = np.nonzero(edge)
        j = rng.integers(len(ey))
        skin = np.array([rng.uniform(90, 170), rng.uniform(120, 190), rng.uniform(170, 235)])
        t = np.zeros((H, W), np.float32)
        s = rng.uniform(20, 45)
        cv2.ellipse(t, (int(ex[j]), int(ey[j])), (int(s * 2.2), int(s)), rng.uniform(0, 180), 0, 360, 1, -1)
        t = cv2.GaussianBlur(t, (0, 0), 1.5)
        img = img * (1 - t[..., None]) + skin * t[..., None]
        am = am * (1 - (t > 0.5))
    return img, am


def write(n, out, seed):
    rng = np.random.default_rng(seed)
    X = np.zeros((n, gen.OUT_H, gen.OUT_W), np.uint8)
    Y = np.zeros((n, gen.OUT_H, gen.OUT_W), np.uint8)
    for i in range(n):
        img, mask = scene(rng)
        frame = ms.camera(img, rng)
        X[i] = cv2.resize(cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY), (gen.OUT_W, gen.OUT_H), interpolation=cv2.INTER_AREA)
        Y[i] = (cv2.resize(mask, (gen.OUT_W, gen.OUT_H), interpolation=cv2.INTER_AREA) > 0.5) * 255
        if i % 1000 == 0:
            print(i, flush=True)
    np.savez_compressed(out, X=X, Y=Y)
    print(out, n)


if __name__ == '__main__':
    if len(sys.argv) > 3:
        write(int(sys.argv[1]), sys.argv[2], int(sys.argv[3]))
        sys.exit()
    rng = np.random.default_rng(1)
    tiles = []
    for _ in range(int(sys.argv[1]) if len(sys.argv) > 1 else 8):
        img, mask = scene(rng)
        frame = ms.camera(img, rng)
        over = frame.copy()
        over[cv2.resize(mask, (frame.shape[1], frame.shape[0])) > 0.5] = over[cv2.resize(mask, (frame.shape[1], frame.shape[0])) > 0.5] * 0.6 + np.array([0, 0, 255]) * 0.4
        tiles.append(cv2.resize(np.hstack([frame, over]), (360, 320)))
    cv2.imwrite('cards_preview.png', np.vstack([np.hstack(tiles[i:i + 4]) for i in range(0, len(tiles) - len(tiles) % 4, 4)]))
