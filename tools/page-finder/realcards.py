"""Training frames for the card finder from real photos of cards, the way the camera sees them.

    python realcards.py <n per image> <out.npz> <seed> <dir> [<dir> ...]
    python realcards.py composite <n> <out.npz> <seed> <dir> [<dir> ...]

Each <dir> is laid out as MIDV-2020's photo.tar or clips.tar (images/<type>/..., annotations/
<type>.json or annotations/<type>/<clip>.json, VIA polygons named doc_quad), with each image
shrunk and a <image>.size file beside it giving its original width and height (what
midvstream.py keeps). Only the ID-1 card types are used. Set HOLD_OUT=<type> to leave a type out
(for a held-out set), or ONLY=<type> to use only it.

From each photo, [n] frames: the photo turned upright or on its side at random, cropped to the
camera's 3:4 portrait frame around the card (from filling most of it to a twentieth), sometimes
with the card partly out of view, and now and then a crop of the background alone; then squeezed
to the finder's 96x128 gray input, with the card's mask (255) drawn from its four corners, and
light, blur, noise and JPEG varied as cards.py does.

composite: n of cards.py's drawn scenes (cloth, sheets, desks, decoys, edge lines, thumbs) with
a real card from the photos in place of each drawn one, straightened to card shape and at random
inverted or darkened and tinted: the photos' cards are all light, printed ones. cards.py's
surfaces need BENCH_DATA, as for gen.py.
"""
import glob
import json
import os
import sys

import cv2
import numpy as np

W, H = 96, 128
CARDS = ['alb_id', 'esp_id', 'est_id', 'fin_id', 'svk_id']


def quads(root):
    """(image path, 4x2 corners in that image's own pixels) for every annotated card image."""
    out = []
    for path in glob.glob(os.path.join(root, 'annotations', '**', '*.json'), recursive=True):
        rel = os.path.relpath(path, os.path.join(root, 'annotations'))
        kind = rel.split(os.sep)[0].removesuffix('.json')
        if kind not in CARDS:
            continue
        if os.environ.get('HOLD_OUT') == kind or os.environ.get('ONLY', kind) != kind:
            continue
        # photo.tar: annotations/<type>.json for images/<type>/*.jpg; clips.tar: annotations/<type>/<clip>.json for images/<type>/<clip>/*.jpg
        folder = os.path.join(root, 'images', os.path.splitext(rel)[0])
        meta = json.load(open(path))['_via_img_metadata']
        for item in meta.values():
            img = os.path.join(folder, item['filename'])
            if not os.path.exists(img) or not os.path.exists(img + '.size'):
                continue
            quad = [r['shape_attributes'] for r in item['regions'] if r['region_attributes'].get('field_name') == 'doc_quad']
            if not quad or quad[0].get('name') != 'polygon' or len(quad[0]['all_points_x']) != 4:
                continue
            try:
                ow, oh = map(int, open(img + '.size').read().split())
            except ValueError:
                continue  # cut short by an interrupted download
            pts = np.array([quad[0]['all_points_x'], quad[0]['all_points_y']], np.float32).T
            out.append((img, pts, ow, oh))
    return sorted(out)


def turn(img, pts, k):
    """The image turned k quarter turns clockwise, and its corners with it."""
    h, w = img.shape[:2]
    for _ in range(k % 4):
        img = cv2.rotate(img, cv2.ROTATE_90_CLOCKWISE)
        pts = np.stack([h - 1 - pts[:, 1], pts[:, 0]], 1)
        h, w = w, h
    return img, pts


def frame(img, pts, rng):
    """One 96x128 frame and mask from a photo whose card is at pts, or None if no crop fits."""
    h, w = img.shape[:2]
    lo, hi = pts.min(0), pts.max(0)
    area = cv2.contourArea(pts.astype(np.float32))
    kind = rng.random()
    if kind < 0.12:
        # The background alone: a crop that misses the card.
        for _ in range(20):
            cw = rng.uniform(0.3, 0.7) * min(w, h * 0.75)
            ch = cw / 0.75
            x, y = rng.uniform(0, w - cw), rng.uniform(0, h - ch)
            if x + cw < lo[0] or x > hi[0] or y + ch < lo[1] or y > hi[1]:
                break
        else:
            return None
    else:
        # The card a share of the frame from a twentieth to most of it.
        share = np.exp(rng.uniform(np.log(0.05), np.log(0.6)))
        cw = np.sqrt(area / share * 0.75)
        ch = cw / 0.75
        if cw > w or ch > h:
            cw = min(w, h * 0.75)
            ch = cw / 0.75
        partly = kind > 0.9
        # Where the frame may sit so the card's box is inside it (or, partly, a bit out of it).
        slack = 0.25 * (hi - lo) if partly else -np.array([2.0, 2.0])
        x0, x1 = max(0, hi[0] - cw - slack[0]), min(w - cw, lo[0] + slack[0])
        y0, y1 = max(0, hi[1] - ch - slack[1]), min(h - ch, lo[1] + slack[1])
        if x0 > x1 or y0 > y1:
            return None
        x, y = rng.uniform(x0, x1), rng.uniform(y0, y1)
    m = cv2.getAffineTransform(np.float32([[x, y], [x + cw, y], [x, y + ch]]), np.float32([[0, 0], [W, 0], [0, H]]))
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    f = cv2.warpAffine(gray, m, (W, H), flags=cv2.INTER_AREA, borderMode=cv2.BORDER_REFLECT).astype(np.float32)
    mask = np.zeros((H, W), np.uint8)
    if kind >= 0.12:
        q = cv2.transform(pts[None].astype(np.float32), m)[0]
        cv2.fillConvexPoly(mask, np.round(q * 8).astype(np.int32), 255, cv2.LINE_AA, 3)
        mask = np.where(mask >= 128, 255, 0).astype(np.uint8)
    # The camera: light, contrast, blur, noise, JPEG.
    f = f * rng.uniform(0.6, 1.25) + rng.uniform(-25, 25)
    if rng.random() < 0.3:
        f = cv2.GaussianBlur(f, (0, 0), rng.uniform(0.4, 1.2))
    f += rng.normal(0, rng.uniform(0, 4), f.shape)
    f = np.clip(f, 0, 255).astype(np.uint8)
    if rng.random() < 0.5:
        f = cv2.imdecode(cv2.imencode('.jpg', f, [cv2.IMWRITE_JPEG_QUALITY, int(rng.integers(40, 95))])[1], 0)
    return f, mask


def main(n, out, seed, roots):
    rng = np.random.default_rng(seed)
    items = [q for r in roots for q in quads(r)]
    X, Y = [], []
    for path, pts, ow, oh in items:
        img = cv2.imread(path)
        pts = pts * [img.shape[1] / ow, img.shape[0] / oh]
        for _ in range(n):
            # Upright or on its side (the phone held either way), rarely upside down.
            k = rng.choice([0, 1, 3, 2], p=[0.4, 0.25, 0.25, 0.1])
            timg, tpts = turn(img, pts.copy(), k)
            if rng.random() < 0.5:
                timg = timg[:, ::-1].copy()
                tpts = np.stack([timg.shape[1] - 1 - tpts[:, 0], tpts[:, 1]], 1)
            got = frame(timg, tpts, rng)
            if got is not None:
                X.append(got[0])
                Y.append(got[1])
    X, Y = np.array(X), np.array(Y)
    np.savez_compressed(out, X=X, Y=Y)
    print(f'{len(items)} photos, {len(X)} frames, card in {np.mean(Y.reshape(len(Y), -1).max(1) > 0):.2f} of them')


def real_face(items, rng):
    """A real card from a photo, its quad straightened to cards.py's CW x CH, BGR float, its light
    and glare kept; at random inverted, or darkened and tinted, into the dark or coloured cards the
    photos don't have. And cards.py's rounded-corner mask."""
    import cards
    path, pts, ow, oh = items[rng.integers(len(items))]
    img = cv2.imread(path)
    pts = (pts * [img.shape[1] / ow, img.shape[0] / oh]).astype(np.float32)
    # Long side across, as the drawn faces are.
    if np.hypot(*(pts[1] - pts[0])) < np.hypot(*(pts[3] - pts[0])):
        pts = np.roll(pts, -1, 0)
    m = cv2.getPerspectiveTransform(pts, np.float32([[0, 0], [cards.CW, 0], [cards.CW, cards.CH], [0, cards.CH]]))
    face = cv2.warpPerspective(img, m, (cards.CW, cards.CH), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE).astype(np.float32)
    r = rng.random()
    if r < 0.15:
        face = 255 - face
    elif r < 0.4:
        tint = rng.uniform(0.25, 1.0, 3)
        face = face * rng.uniform(0.15, 0.55) * tint / tint.max()
    if rng.random() < 0.5:
        face = face[::-1, ::-1].copy()
    _, mask = cards.face_drawn(rng)
    return face, mask


def composite(n, out, seed, roots):
    """n frames of cards.py's scenes (surfaces, cloth, decoys, edge lines, thumbs) with real
    cards in place of its drawn ones."""
    import cards
    import gen
    import make_scenes as ms
    rng = np.random.default_rng(seed)
    items = [q for r in roots for q in quads(r)]
    cards.face_drawn = cards.face
    cards.face = lambda g: real_face(items, g)
    X = np.zeros((n, gen.OUT_H, gen.OUT_W), np.uint8)
    Y = np.zeros((n, gen.OUT_H, gen.OUT_W), np.uint8)
    for i in range(n):
        img, mask = cards.scene(rng)
        frame = ms.camera(img, rng)
        X[i] = cv2.resize(cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY), (gen.OUT_W, gen.OUT_H), interpolation=cv2.INTER_AREA)
        Y[i] = (cv2.resize(mask, (gen.OUT_W, gen.OUT_H), interpolation=cv2.INTER_AREA) > 0.5) * 255
        if i % 1000 == 0:
            print(i, flush=True)
    np.savez_compressed(out, X=X, Y=Y)
    print(out, n)


if __name__ == '__main__':
    if sys.argv[1] == 'composite':
        composite(int(sys.argv[2]), sys.argv[3], int(sys.argv[4]), sys.argv[5:])
    else:
        main(int(sys.argv[1]), sys.argv[2], int(sys.argv[3]), sys.argv[4:])
