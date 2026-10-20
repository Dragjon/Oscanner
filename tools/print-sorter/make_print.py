"""Printed text windows: lines in document fonts, damaged like a phone scan."""
import glob
import gzip
import os
import random
import sys

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

from common import ink_box, windows

FONT_PREFIXES = ('times', 'tahoma', 'verdana', 'segoeui', 'seguisb', 'trebuc', 'cambria', 'calibri', 'georgia', 'arial',
                 'pala', 'bookos', 'gara', 'cour', 'consola', 'constan', 'corbel', 'candara', 'century', 'gothic', 'lsans',
                 'lbrite', 'bkant', 'bell', 'baskvill', 'bod_r', 'bod_b', 'bod_i', 'calist', 'censcbk', 'centaur', 'frabk',
                 'fradm', 'framd', 'gil_', 'gilb', 'gili', 'goudos', 'lfax', 'ltype', 'micross', 'per_', 'perb', 'peri', 'rock',
                 'schlbk', 'tcm_', 'tcb_', 'tcmi', 'lucon', 'arialn', 'bahnschrift', 'ebrima', 'gadugi', 'deng', 'leelawui',
                 'malgun', 'mmrtext', 'sitkavf', 'ocraext')


def fonts():
    out = []
    for f in glob.glob('C:/Windows/Fonts/*'):
        name = os.path.basename(f).lower()
        if name.endswith(('.ttf', '.otf')) and name.startswith(FONT_PREFIXES):
            out.append(f)
    return sorted(out)


def words():
    root = 'C:/Users/dragon/Downloads/Oscanner/core/ocr/src/main/assets/paddle/words.dat'
    ws = [l.split(' ')[0].strip() for l in gzip.open(root).read().decode('utf-8').split('\n')[:30000] if l.strip()]
    return ws


LABELS = ["Name:", "Class:", "Date:", "Register Number:", "Candidate's Name :", "Signature:", "Total", "Page", "Section",
          "Question", "Answer", "Marks", "For Examiner's Use", "No.", "Tel:", "Email:", "Address:", "Subject:", "(a)", "(b)",
          "1.", "2.", "3.", "Q1", "A.", "B.", "Invoice", "Amount", "$", "%", "Total:", "Ref:", "ID:", "Form", "Part"]


def phrase(ws):
    r = random.random()
    if r < 0.25:
        return random.choice(LABELS) + ('' if random.random() < 0.5 else ' ' + random.choice(ws))
    n = random.randint(1, 7)
    out = []
    for _ in range(n):
        w = random.choice(ws)
        c = random.random()
        if c < 0.12:
            w = w.upper()
        elif c < 0.35:
            w = w.capitalize()
        elif c < 0.42:
            w = str(random.randint(0, 99999))
        out.append(w)
    s = ' '.join(out)
    if random.random() < 0.3:
        s += random.choice(['.', ',', ':', ';', '?', ')', '!'])
    return s


def render(text, font_path, px):
    font = ImageFont.truetype(font_path, px)
    l, t, r, b = font.getbbox(text)
    pad = px
    img = Image.new('L', (r - l + 2 * pad, int(px * 2.2) + 2 * pad), 255)
    ImageDraw.Draw(img).text((pad - l, pad), text, font=font, fill=random.choice([0, 0, 0, 20, 40, 60]))
    return np.array(img)


def damage(g):
    """Phone-scan look: scale, blur, uneven light, noise, JPEG, a slight tilt."""
    h, w = g.shape
    if random.random() < 0.5:
        a = random.uniform(-1.5, 1.5)
        m = cv2.getRotationMatrix2D((w / 2, h / 2), a, 1.0)
        g = cv2.warpAffine(g, m, (w, h), borderValue=255)
    k = random.choice([0, 0, 1, 1, 2])
    if k:
        g = cv2.GaussianBlur(g, (2 * k + 1, 2 * k + 1), random.uniform(0.3, 1.2))
    paper = random.uniform(150, 250)
    ink = random.uniform(0, min(110, paper - 60))
    gf = g.astype(np.float32) / 255
    gf = ink + (paper - ink) * gf
    X = np.linspace(0, 1, w)[None, :]
    gf *= 1 - random.uniform(0, 0.25) * X if random.random() < 0.5 else 1
    gf += np.random.normal(0, random.uniform(1, 8), gf.shape)
    g = np.clip(gf, 0, 255).astype(np.uint8)
    if random.random() < 0.7:
        ok, enc = cv2.imencode('.jpg', g, [cv2.IMWRITE_JPEG_QUALITY, random.randint(40, 92)])
        g = cv2.imdecode(enc, cv2.IMREAD_GRAYSCALE)
    return g


def run_height(g):
    """The run's height as the app measures it: the ink's row extent."""
    ink = cv2.adaptiveThreshold(g, 255, cv2.ADAPTIVE_THRESH_MEAN_C, cv2.THRESH_BINARY_INV, 31, 15)
    rows = np.nonzero(ink.sum(axis=1) > 0)[0]
    cols = np.nonzero(ink.sum(axis=0) > 0)[0]
    if len(rows) < 3 or len(cols) < 3:
        return None
    return rows[0], rows[-1], cols[0], cols[-1]


def main(n_lines, out_path, seed):
    random.seed(seed)
    np.random.seed(seed)
    fs = fonts()
    ws = words()
    print(len(fs), 'fonts')
    X = []
    while len(X) < n_lines * 6 and n_lines > 0:
        n_lines -= 1
        px = random.randint(14, 64)
        try:
            g = render(phrase(ws), random.choice(fs), px)
        except OSError:
            continue
        g = damage(g)
        box = ink_box(g)
        if box is None:
            continue
        top, bottom, left, right = box
        h = bottom - top + 1
        ws_, _ = windows(g, left, right, (top + bottom) // 2, h)
        X.extend(ws_)
    X = np.array(X, np.float16)
    np.save(out_path, X)
    print(out_path, X.shape)


if __name__ == '__main__':
    main(int(sys.argv[1]), sys.argv[2], int(sys.argv[3]))
