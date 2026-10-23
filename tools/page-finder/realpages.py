"""Training frames for the page finder from real photos of pages, the way the camera sees them.

    python realpages.py <set> <n per image> <out.npz> <seed> <dir>

<set> is smartdoc, a folder as sdstream.py leaves SmartDoc 2015 Challenge 1 (<background>/<doc>/
<frame>.jpg with corners.txt), or mendeley, the "New Image Dataset for Document Corner
Localization" as unzipped (each <name>.jpg shrunk, with <name>.jpg.size giving its original width
and height, and <name>.csv its corners in those pixels: top left, bottom left, bottom right, top
right, then the phone).

Held out to check on: SmartDoc's background05 (set HOLD=only to use only it, HOLD=out to leave it
out), and Mendeley's photos numbered up to 130, "(n).jpg" and "(n).jpg 2" alike (shots taken
one after another look alike, so a block of them is held out, not a random pick).

From each image, [n] frames: turned upright or on its side, cropped to the camera's 3:4 portrait
frame around the page (from filling most of it to a tenth), sometimes partly out of view, now and
then the background alone; then the finder's 96x128 gray input and the page's mask, with light,
blur, noise and JPEG varied (realcards.frame).
"""
import csv
import glob
import os
import re
import sys

import cv2
import numpy as np

from realcards import frame, turn

SHARE = (0.1, 0.85)


def held(name, hold):
    """Whether an item is kept under HOLD (only: just the held-out ones; out: all but them)."""
    return hold == 'all' or (hold == 'only') == name


def smartdoc(root, hold):
    out = []
    for path in sorted(glob.glob(os.path.join(root, '*', '*', 'corners.txt'))):
        doc = os.path.dirname(path)
        if not held(os.path.basename(os.path.dirname(doc)) == 'background05', hold):
            continue
        for line in open(path):
            f = line.split()
            if len(f) != 5:
                continue
            pts = np.array([[float(v) for v in p.split(',')] for p in f[1:]], np.float32)
            out.append((os.path.join(doc, f'{int(f[0]):04d}.jpg'), pts))
    return out


def mendeley(root, hold):
    out = []
    for path in sorted(glob.glob(os.path.join(root, '**', '*.csv*'), recursive=True)):
        m = re.match(r'\((\d+)\)\.csv( 2)?$', os.path.basename(path))
        if not m:
            continue
        img = os.path.join(os.path.dirname(path), f'({m.group(1)}).jpg' + (m.group(2) or ''))
        if not held(int(m.group(1)) <= 130, hold) or not os.path.exists(img + '.size'):
            continue
        try:
            row = next(csv.reader(open(path)))
            p = np.array([float(v) for v in row[:8]], np.float32).reshape(4, 2)
            ow, oh = map(int, open(img + '.size').read().split())
        except (ValueError, StopIteration):
            continue
        out.append((img, p[[0, 3, 2, 1]], ow, oh))  # to top left, top right, bottom right, bottom left
    return out


def main(kind, n, out, seed, root):
    rng = np.random.default_rng(seed)
    hold = os.environ.get('HOLD', 'out')
    items = smartdoc(root, hold) if kind == 'smartdoc' else mendeley(root, hold)
    X, Y = [], []
    for item in items:
        img = cv2.imread(item[0])
        if img is None:
            continue
        pts = item[1] if kind == 'smartdoc' else item[1] * [img.shape[1] / item[2], img.shape[0] / item[3]]
        for _ in range(n):
            # Held so the page is upright on the screen, or the photo taken the other way round.
            wide = img.shape[1] > img.shape[0]
            k = rng.choice([1, 3, 0, 2], p=[0.45, 0.45, 0.05, 0.05]) if wide else rng.choice([0, 1, 3, 2], p=[0.7, 0.1, 0.1, 0.1])
            timg, tpts = turn(img, pts.copy(), k)
            if rng.random() < 0.5:
                timg = timg[:, ::-1].copy()
                tpts = np.stack([timg.shape[1] - 1 - tpts[:, 0], tpts[:, 1]], 1)
            got = frame(timg, tpts, rng, SHARE)
            if got is not None:
                X.append(got[0])
                Y.append(got[1])
    X, Y = np.array(X), np.array(Y)
    np.savez_compressed(out, X=X, Y=Y)
    print(f'{len(items)} images, {len(X)} frames, page in {np.mean(Y.reshape(len(Y), -1).max(1) > 0):.2f} of them')


if __name__ == '__main__':
    main(sys.argv[1], int(sys.argv[2]), sys.argv[3], int(sys.argv[4]), sys.argv[5])
