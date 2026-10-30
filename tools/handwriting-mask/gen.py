"""Training pieces for the handwriting mask: printed page fragments with handwriting on them.

Each fragment is drawn as layers, so every pixel's answer is known: paper, print (text, answer
lines, ruled paper, tables, graphs, boxes) and then handwriting over it (GNHK lines from
make_strokes.py, plus drawn rings, ticks, underlines and crossings-out), in pen or pencil, and
finally roughed up like a phone photo or a scanning app's cleaned page.

sample() gives (rgb uint8 HxWx3, target uint8 HxW): 1 handwriting, 0 not, 255 don't score
(the soft rim of a stroke). Where pen crosses print, the print wins: that pixel stays.

    python gen.py <fonts dir> <strokes dir> <out.png>   draws a sheet of samples to look at
"""
import glob
import gzip
import math
import os
import random
import sys

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

LABELS = ['Name:', 'Class:', 'Date:', 'Index No.', "Candidate's Name", 'Signature', 'Total', 'Section A',
          'Question', 'Answer', 'Marks', "For Examiner's Use", 'Fig. 2.1', 'Table 8.1', '[Total: 7]', 'Practice',
          '(a)', '(b)', '(c)', '(i)', '(ii)', '(iii)', '1', '2', '3', '4', '10', 'A', 'B', 'C', 'D', 'Explain',
          'Calculate', 'State', 'Describe', 'Suggest', 'Give your answer in', 'acceleration =', 'force =',
          'current rating =', 'energy =', 'pressure =', 'Pa', 'N', 'J', 'm/s', 'kg', '%', 'cm', 'mol']
MATH = ['x', 'y', '=', '+', '-', '×', '÷', '(', ')', '²', '½', 'sin', 'cos', 'tan', 'θ', 'Δ', '√', '<', '>', '/',
        '0.5', '1.6', '10³', '9.8', 'mol', 'dm³', '→', '%']


class Assets:
    def __init__(self, fonts_dir, strokes_dir, words_path=None):
        self.fonts = sorted(glob.glob(os.path.join(fonts_dir, '*.tt[fc]')) + glob.glob(os.path.join(fonts_dir, '*.otf')))
        self.strokes = sorted(glob.glob(os.path.join(strokes_dir, '*.png')))
        self.words = ['the', 'of', 'and', 'to', 'in', 'is', 'that', 'for', 'it', 'as', 'with', 'was', 'on', 'be', 'by',
                      'this', 'are', 'from', 'or', 'an', 'which', 'their', 'has', 'one', 'all', 'were', 'when']
        if words_path and os.path.exists(words_path):
            ws = gzip.open(words_path).read().decode('utf-8').split('\n')[:20000]
            self.words = [w.split(' ')[0].strip() for w in ws if w.strip()]
        self._font_cache = {}
        self._stroke_cache = {}

    def font(self, path, px):
        key = (path, px)
        f = self._font_cache.get(key)
        if f is None:
            if len(self._font_cache) > 600:
                self._font_cache.clear()
            f = self._font_cache[key] = ImageFont.truetype(path, px)
        return f

    def stroke(self, path):
        s = self._stroke_cache.get(path)
        if s is None:
            if len(self._stroke_cache) > 1500:
                self._stroke_cache.clear()
            s = self._stroke_cache[path] = cv2.imread(path, cv2.IMREAD_GRAYSCALE)
        return s


def number():
    """Numbers as tables and forms print them: counts, money, decimals, percents, dates."""
    r = random.random()
    if r < 0.3:
        return str(random.randint(0, 9999))
    if r < 0.5:
        return '%.*f' % (random.randint(1, 3), random.uniform(0, 1000))
    if r < 0.65:
        return '%.1f%%' % random.uniform(0, 100) if random.random() < 0.6 else '%d%%' % random.randint(0, 100)
    if r < 0.8:
        return random.choice(['£', '$', '€', 'S$']) + '{:,}'.format(random.randint(1, 99999))
    return '%04d-%02d-%02d' % (random.randint(1990, 2030), random.randint(1, 12), random.randint(1, 28))


def phrase(a, n=None):
    out = []
    for _ in range(n or random.randint(2, 12)):
        r = random.random()
        if r < 0.08:
            out.append(random.choice(LABELS))
        elif r < 0.13:
            out.append(random.choice(MATH))
        elif r < 0.24:
            out.append(number())
        else:
            w = random.choice(a.words)
            out.append(w.capitalize() if random.random() < 0.15 else w)
    s = ' '.join(out)
    if random.random() < 0.3:
        s += random.choice(['.', ',', ':', '?', ')', ';'])
    return s


# ---------------------------------------------------------------- print


class Layer:
    """Ink coverage (0..1) plus the color it was drawn in, composited later as a multiply."""

    def __init__(self, h, w):
        self.alpha = np.zeros((h, w), np.float32)
        self.color = np.zeros((h, w, 3), np.float32)

    def add(self, alpha, color):
        color = np.asarray(color, np.float32) / 255
        a = np.clip(alpha, 0, 1)
        # Later ink sits over earlier ink: its color takes over where it covers.
        self.color = self.color * (1 - a[..., None]) + color * a[..., None]
        self.alpha = 1 - (1 - self.alpha) * (1 - a)


def mask_text(h, w, a, x, y, text, font_path, px):
    img = Image.new('L', (w, h), 0)
    ImageDraw.Draw(img).text((x, y), text, font=a.font(font_path, px), fill=255)
    return np.asarray(img, np.float32) / 255


def lines_mask(h, w, fn):
    img = np.zeros((h, w), np.uint8)
    fn(img)
    return img.astype(np.float32) / 255


def print_layer(a, h, w):
    """A fragment of a printed page, as a Layer, and where its answer lines run (y, x0, x1)."""
    L = Layer(h, w)
    font = random.choice(a.fonts)
    px = int(random.choice([random.uniform(8, 14), random.uniform(12, 22), random.uniform(18, 34)]))
    lead = px * random.uniform(1.25, 2.2)
    ink = random.choice([(0, 0, 0)] * 4 + [(30, 30, 30), (55, 55, 55), (20, 20, 60), (70, 40, 30)])
    # Printed color, in the same shades as pens: red notices, blue links, green headings. Told
    # from handwriting by its shapes, not its color.
    accent = random.choice([(200, 30, 30), (170, 20, 20), (220, 60, 40), (30, 70, 200), (20, 50, 150),
                            (40, 120, 60), (110, 40, 150), (230, 120, 20), (20, 130, 140)])
    if random.random() < 0.08:
        ink = accent
    answer_lines = []
    kind = random.random()
    if kind < 0.12:
        # Ruled paper: light rules, maybe a margin rule, little print.
        gap = random.randint(26, 50)
        rule = random.choice([(150, 180, 220), (170, 170, 170), (120, 160, 210), (190, 200, 210)])
        y0 = random.randint(0, gap)
        m = np.zeros((h, w), np.uint8)
        for y in range(y0, h, gap):
            cv2.line(m, (0, y), (w, y), 255, random.choice([1, 1, 2]), cv2.LINE_AA)
            answer_lines.append((y, 0, w))
        L.add(m.astype(np.float32) / 255 * random.uniform(0.5, 1), rule)
        if random.random() < 0.5:
            mx = random.randint(20, w // 3)
            m = lines_mask(h, w, lambda im: cv2.line(im, (mx, 0), (mx, h), 255, random.choice([1, 2]), cv2.LINE_AA))
            L.add(m * random.uniform(0.5, 1), (220, 90, 90))
        return L, answer_lines
    y = random.uniform(-lead, lead)
    x_left = random.randint(-20, 60)
    while y < h:
        r = random.random()
        if r < 0.45:
            # A line of text, maybe with a question number and marks at the right.
            x = x_left + (random.randint(20, 60) if random.random() < 0.3 else 0)
            text = phrase(a, random.randint(3, 14))
            if random.random() < 0.25:
                text = random.choice(['1', '2', '3', '(a)', '(b)', '(i)', '(ii)', 'A', 'B', 'C', 'D']) + '   ' + text
            f = font if random.random() < 0.85 else random.choice(a.fonts)
            L.add(mask_text(h, w, a, x, y, text, f, px), accent if random.random() < 0.15 else ink)
            if random.random() < 0.15:
                L.add(mask_text(h, w, a, w - px * 3, y, random.choice(['[1]', '[2]', '[3]', '[4]', '(2 marks)']), font, px), ink)
            y += lead
        elif r < 0.62:
            # Answer lines: dotted or solid, one to four.
            for _ in range(random.randint(1, 4)):
                y += lead * random.uniform(1.0, 1.6)
                x0 = random.randint(0, w // 3)
                x1 = random.randint(w * 2 // 3, w + 20)
                yy = int(y)
                m = np.zeros((h, w), np.uint8)
                if random.random() < 0.55:
                    step = random.randint(3, 7)
                    rad = random.choice([0, 1])
                    for xx in range(x0, x1, step):
                        cv2.circle(m, (xx, yy), rad, 255, -1, cv2.LINE_AA)
                else:
                    cv2.line(m, (x0, yy), (x1, yy), 255, random.choice([1, 1, 2]), cv2.LINE_AA)
                L.add(m.astype(np.float32) / 255, ink)
                answer_lines.append((yy, x0, x1))
                if random.random() < 0.2:
                    L.add(mask_text(h, w, a, x1 + 4, yy - px, random.choice(['[1]', '[2]', 'N', 'J', 'm/s²', 'A']), font, px), ink)
            y += lead
        elif r < 0.72:
            # A table.
            rows, cols = random.randint(2, 6), random.randint(2, 6)
            ch = int(lead * random.uniform(1.1, 2.5))
            tw = random.randint(w // 2, w + 40)
            tx = random.randint(-20, max(0, w - tw))
            m = np.zeros((h, w), np.uint8)
            th = rows * ch
            for i in range(rows + 1):
                cv2.line(m, (tx, int(y + i * ch)), (tx + tw, int(y + i * ch)), 255, 1, cv2.LINE_AA)
            for j in range(cols + 1):
                xx = tx + j * tw // cols
                cv2.line(m, (xx, int(y)), (xx, int(y + th)), 255, 1, cv2.LINE_AA)
            L.add(m.astype(np.float32) / 255, ink)
            if random.random() < 0.3:
                fill = np.zeros((h, w), np.float32)
                fill[max(0, int(y)):max(0, int(y + ch)), max(0, tx):max(0, tx + tw)] = random.uniform(0.1, 0.3)
                L.add(fill, (90, 90, 90))
            for i in range(rows):
                for j in range(cols):
                    if random.random() < 0.7:
                        cell = number() if random.random() < 0.5 else phrase(a, random.randint(1, 2))
                        L.add(mask_text(h, w, a, tx + j * tw // cols + 4, y + i * ch + (ch - px) / 2,
                                        cell, font, int(px * 0.9)), ink)
            y += th + lead
        elif r < 0.8:
            # A graph: axes, maybe a light grid, a curve, tick labels.
            gw, gh = random.randint(w // 3, w), random.randint(int(lead * 4), int(lead * 10))
            gx = random.randint(0, max(1, w - gw))
            m = np.zeros((h, w), np.uint8)
            if random.random() < 0.6:
                grid = np.zeros((h, w), np.uint8)
                step = random.randint(8, 30)
                for xx in range(gx, gx + gw, step):
                    cv2.line(grid, (xx, int(y)), (xx, int(y + gh)), 255, 1)
                for yy in range(int(y), int(y + gh), step):
                    cv2.line(grid, (gx, yy), (gx + gw, yy), 255, 1)
                L.add(grid.astype(np.float32) / 255 * random.uniform(0.25, 0.6), ink)
            oy = int(y + gh * random.choice([1.0, 0.5]))
            cv2.arrowedLine(m, (gx, oy), (gx + gw, oy), 255, 1, cv2.LINE_AA, tipLength=0.02)
            cv2.arrowedLine(m, (gx, int(y + gh)), (gx, int(y)), 255, 1, cv2.LINE_AA, tipLength=0.02)
            if random.random() < 0.6:
                pts = []
                for k in range(30):
                    t = k / 29
                    pts.append((gx + int(t * gw), int(y + gh - gh * (t ** random.uniform(0.3, 3)))))
                cv2.polylines(m, [np.array(pts, np.int32)], False, 255, random.choice([1, 2]), cv2.LINE_AA)
            L.add(m.astype(np.float32) / 255, ink)
            for k in range(random.randint(0, 6)):
                L.add(mask_text(h, w, a, gx + k * gw // 6, oy + 4, str(random.choice([0, 0.5, 1, 1.5, 2, 5, 10, 15])),
                                font, int(px * 0.7)), ink)
            y += gh + lead
        elif r < 0.88:
            # A diagram: boxes, circles, arrows, labels.
            dh = int(lead * random.uniform(3, 8))
            m = np.zeros((h, w), np.uint8)
            for _ in range(random.randint(2, 8)):
                cx, cy = random.randint(0, w), int(y + random.uniform(0, dh))
                s = random.randint(10, 80)
                t = random.random()
                if t < 0.35:
                    cv2.rectangle(m, (cx, cy), (cx + s * 2, cy + s), 255, random.choice([1, 2]), cv2.LINE_AA)
                elif t < 0.6:
                    cv2.circle(m, (cx, cy), s // 2, 255, random.choice([1, 2]), cv2.LINE_AA)
                elif t < 0.85:
                    cv2.arrowedLine(m, (cx, cy), (cx + random.randint(-80, 80), cy + random.randint(-40, 40)), 255, 1, cv2.LINE_AA)
                else:
                    cv2.rectangle(m, (cx, cy), (cx + s, cy + s // 2), 255, -1)
                if random.random() < 0.5:
                    L.add(mask_text(h, w, a, cx + s, cy, random.choice(LABELS), font, int(px * 0.8)), ink)
            L.add(m.astype(np.float32) / 255, ink)
            y += dh + lead
        elif r < 0.92:
            # A printed drawing: cartoon line art, clip-art fills, a logo badge or a halftone picture.
            dh = int(lead * random.uniform(3, 9))
            drawing(L, a, h, w, int(y), dh, font, px)
            y += dh + lead
        elif r < 0.95:
            # A heading, bold or on a shaded band.
            f = random.choice(a.fonts)
            big = int(px * random.uniform(1.2, 2.2))
            if random.random() < 0.4:
                band = np.zeros((h, w), np.float32)
                band[max(0, int(y - 4)):max(0, int(y + big * 1.4)), :] = 1
                L.add(band * random.uniform(0.25, 0.9), random.choice([(80, 80, 80), (60, 90, 140), (150, 60, 60)]))
                L.add(mask_text(h, w, a, x_left, y, phrase(a, random.randint(1, 4)), f, big), (255, 255, 255) if random.random() < 0.5 else ink)
            else:
                L.add(mask_text(h, w, a, x_left, y, phrase(a, random.randint(1, 5)), f, big),
                      accent if random.random() < 0.35 else ink)
            y += big * 1.6
        else:
            y += lead * random.uniform(0.5, 3)
    return L, answer_lines


def blob(cx, cy, rx, ry, n=48):
    """A smooth closed outline: a circle with a few slow bulges, nothing hand-shaky."""
    k = random.randint(2, 5)
    phase = [random.uniform(0, 2 * math.pi) for _ in range(k)]
    amp = [random.uniform(0, 0.25) / (i + 1) for i in range(k)]
    pts = []
    for i in range(n):
        t = 2 * math.pi * i / n
        r = 1 + sum(amp[j] * math.sin((j + 2) * t + phase[j]) for j in range(k))
        pts.append((cx + rx * r * math.cos(t), cy + ry * r * math.sin(t)))
    return np.array(pts, np.int32)


def drawing(L, a, h, w, y, dh, font, px):
    kind = random.random()
    line = random.choice([(0, 0, 0)] * 3 + [(90, 30, 30), (40, 40, 90), (60, 60, 60)])
    if kind < 0.5:
        # Cartoon line art: a head, hair hatching, a body, flat fills.
        for _ in range(random.randint(1, 3)):
            cx, cy = random.randint(0, w), y + random.randint(0, max(1, dh))
            r = random.randint(20, 90)
            head = blob(cx, cy, r, r * random.uniform(0.9, 1.2))
            if random.random() < 0.6:
                fill = np.zeros((h, w), np.uint8)
                cv2.fillPoly(fill, [head], 255)
                L.add(fill.astype(np.float32) / 255 * random.uniform(0.15, 0.6),
                      random.choice([(240, 200, 170), (250, 220, 120), (150, 200, 240), (200, 120, 120)]))
            m = np.zeros((h, w), np.uint8)
            cv2.polylines(m, [head], True, 255, random.choice([1, 2, 3]), cv2.LINE_AA)
            # Eyes, mouth.
            for ex in (-0.35, 0.35):
                cv2.circle(m, (int(cx + ex * r), int(cy - 0.1 * r)), max(2, r // 10), 255, -1, cv2.LINE_AA)
            cv2.ellipse(m, (cx, int(cy + 0.3 * r)), (r // 3, r // 6), 0, 10, 170, 255, random.choice([1, 2]), cv2.LINE_AA)
            # Hair: many short parallel curves over the top of the head.
            hair = random.choice([(0, 0, 0), (120, 40, 40), (90, 60, 30), (160, 50, 50)])
            hm = np.zeros((h, w), np.uint8)
            for k in range(random.randint(0, 40)):
                t = random.uniform(math.pi * 1.05, math.pi * 1.95)
                x0, y0 = cx + r * math.cos(t), cy + r * math.sin(t)
                cv2.ellipse(hm, (int(x0), int(y0 + r * 0.3)), (int(r * 0.4), int(r * 0.3)), math.degrees(t), 180, 300, 255, 1, cv2.LINE_AA)
            L.add(hm.astype(np.float32) / 255, hair)
            if random.random() < 0.6:
                body = blob(cx, cy + r * 2.2, r * 1.2, r * 1.1)
                cv2.polylines(m, [body], True, 255, random.choice([1, 2]), cv2.LINE_AA)
            L.add(m.astype(np.float32) / 255, line)
    elif kind < 0.7:
        # Clip-art: overlapping smooth shapes, filled, outlined, sometimes hatched.
        for _ in range(random.randint(2, 6)):
            cx, cy = random.randint(0, w), y + random.randint(0, max(1, dh))
            shape = blob(cx, cy, random.randint(15, 100), random.randint(15, 80))
            fill = np.zeros((h, w), np.uint8)
            cv2.fillPoly(fill, [shape], 255)
            if random.random() < 0.4:
                hatch = np.zeros((h, w), np.uint8)
                step = random.randint(4, 9)
                for k in range(-h, w, step):
                    cv2.line(hatch, (k, 0), (k + h, h), 255, 1, cv2.LINE_AA)
                L.add((hatch & fill).astype(np.float32) / 255, line)
            else:
                L.add(fill.astype(np.float32) / 255 * random.uniform(0.2, 0.9),
                      tuple(random.randint(30, 250) for _ in range(3)))
            m = np.zeros((h, w), np.uint8)
            cv2.polylines(m, [shape], True, 255, random.choice([1, 2, 3]), cv2.LINE_AA)
            L.add(m.astype(np.float32) / 255, line)
    elif kind < 0.82:
        # A logo badge: a dark rounded box, light letters, a mark beside it.
        bw, bh = random.randint(px * 3, px * 9), int(px * random.uniform(1.4, 2.5))
        bx = random.randint(0, max(1, w - bw))
        m = np.zeros((h, w), np.uint8)
        cv2.rectangle(m, (bx, y), (bx + bw, y + bh), 255, -1)
        L.add(m.astype(np.float32) / 255, random.choice([(30, 30, 30), (20, 60, 140), (170, 30, 40), (40, 120, 60)]))
        L.add(mask_text(h, w, a, bx + px // 3, y + bh * 0.15, random.choice(['CS', 'OK', 'PDF', 'A+', 'SS']),
                        random.choice(a.fonts), int(bh * 0.7)), (255, 255, 255))
        L.add(mask_text(h, w, a, bx + bw + px // 2, y + bh * 0.2, phrase(a, 1), random.choice(a.fonts), int(bh * 0.6)), line)
    else:
        # A printed photo: a soft picture, maybe screened into dots.
        pw, ph = random.randint(w // 4, w), max(20, dh)
        px0 = random.randint(0, max(1, w - pw))
        small = np.random.uniform(0, 1, (max(2, ph // 24), max(2, pw // 24), 3)).astype(np.float32)
        pic = cv2.resize(small, (pw, ph), interpolation=cv2.INTER_CUBIC)
        pic = np.clip(pic, 0, 1)
        dark = 1 - pic.mean(axis=2)
        if random.random() < 0.4:
            # Halftone: dots on a grid, sized by darkness.
            cell = random.randint(3, 6)
            dots = np.zeros((ph, pw), np.uint8)
            for yy in range(0, ph, cell):
                for xx in range(0, pw, cell):
                    r = dark[yy, xx] * cell * 0.7
                    if r > 0.4:
                        cv2.circle(dots, (xx, yy), int(round(r)), 255, -1, cv2.LINE_AA)
            dark = dots.astype(np.float32) / 255
            pic[:] = 0
        full = np.zeros((h, w), np.float32)
        col = np.zeros((h, w, 3), np.float32)
        y0, y1 = max(0, y), min(h, y + ph)
        x1 = min(w, px0 + pw)
        if y1 > y0 and x1 > px0:
            full[y0:y1, px0:x1] = dark[y0 - y:y1 - y, :x1 - px0]
            col[y0:y1, px0:x1] = pic[y0 - y:y1 - y, :x1 - px0] * 255
            L.add(full, (0, 0, 0)) if pic.max() == 0 else add_colored(L, full, col)


def add_colored(L, alpha, color):
    """Like Layer.add, with a color per pixel."""
    a = np.clip(alpha, 0, 1)[..., None]
    L.color = L.color * (1 - a) + color / 255 * a
    L.alpha = 1 - (1 - L.alpha) * (1 - a[..., 0])


# ---------------------------------------------------------------- handwriting


def wobbly(points, jitter):
    """A hand-drawn version of a polyline: smooth random wander along it."""
    pts = np.array(points, np.float32)
    out = []
    for i in range(len(pts) - 1):
        p, q = pts[i], pts[i + 1]
        n = max(2, int(np.linalg.norm(q - p) / 6))
        for k in range(n):
            out.append(p + (q - p) * k / n)
    out.append(pts[-1])
    out = np.array(out)
    if len(out) > 3 and jitter > 0:
        noise = np.random.normal(0, jitter, out.shape)
        noise = cv2.GaussianBlur(noise.astype(np.float32).reshape(-1, 1, 2), (1, 9), 3).reshape(-1, 2)
        out = out + noise
    return out.astype(np.int32)


def draw_mark(m, kind, x, y, s, width):
    jit = s * 0.03 + 0.5
    if kind == 'ring':
        rx, ry = s * random.uniform(1, 3), s * random.uniform(0.5, 1)
        start = random.uniform(0, 2 * math.pi)
        turn = random.uniform(2.1, 2.4) * math.pi
        pts = [(x + rx * math.cos(start + turn * k / 40) * (1 + 0.05 * k / 40), y + ry * math.sin(start + turn * k / 40))
               for k in range(41)]
        cv2.polylines(m, [wobbly(pts, jit)], False, 255, width, cv2.LINE_AA)
    elif kind == 'tick':
        pts = [(x, y), (x + s * 0.3, y + s * 0.4), (x + s * random.uniform(0.8, 1.4), y - s * random.uniform(0.5, 1))]
        cv2.polylines(m, [wobbly(pts, jit * 0.5)], False, 255, width, cv2.LINE_AA)
    elif kind == 'cross':
        cv2.polylines(m, [wobbly([(x, y), (x + s, y + s)], jit)], False, 255, width, cv2.LINE_AA)
        cv2.polylines(m, [wobbly([(x + s, y), (x, y + s)], jit)], False, 255, width, cv2.LINE_AA)
    elif kind == 'underline':
        L = s * random.uniform(3, 12)
        pts = [(x, y), (x + L * 0.5, y + random.uniform(-3, 3)), (x + L, y + random.uniform(-5, 5))]
        cv2.polylines(m, [wobbly(pts, jit)], False, 255, width, cv2.LINE_AA)
        if random.random() < 0.2:
            cv2.polylines(m, [wobbly([(x, y + 5), (x + L, y + 6)], jit)], False, 255, width, cv2.LINE_AA)
    elif kind == 'strike':
        L = s * random.uniform(2, 8)
        cv2.polylines(m, [wobbly([(x, y), (x + L, y + random.uniform(-4, 4))], jit)], False, 255, width, cv2.LINE_AA)
    elif kind == 'scribble':
        pts = [(x + random.uniform(0, s * 3), y + random.uniform(0, s)) for _ in range(random.randint(6, 20))]
        cv2.polylines(m, [wobbly(pts, jit)], False, 255, width, cv2.LINE_AA)
    elif kind == 'arrow':
        dx, dy = random.uniform(-4, 4) * s, random.uniform(-2, 2) * s
        mid = (x + dx / 2 + random.uniform(-s, s), y + dy / 2 + random.uniform(-s, s))
        pts = wobbly([(x, y), mid, (x + dx, y + dy)], jit)
        cv2.polylines(m, [pts], False, 255, width, cv2.LINE_AA)
        end = pts[-1].astype(np.float32)
        back = pts[max(0, len(pts) - 4)].astype(np.float32)
        d = end - back
        d = d / (np.linalg.norm(d) + 1e-3) * s * 0.4
        for ang in (0.5, -0.5):
            r = np.array([[math.cos(ang), -math.sin(ang)], [math.sin(ang), math.cos(ang)]])
            tip = end - r @ d
            cv2.line(m, tuple(end.astype(int)), tuple(tip.astype(int)), 255, width, cv2.LINE_AA)
    elif kind == 'bracket':
        pts = [(x + s * 0.3, y), (x, y + s * 0.2), (x, y + s * 2), (x + s * 0.3, y + s * 2.2)]
        cv2.polylines(m, [wobbly(pts, jit)], False, 255, width, cv2.LINE_AA)


def pen():
    """A pen's color and how solid its ink is."""
    r = random.random()
    if r < 0.38:
        return (random.randint(0, 60), random.randint(20, 90), random.randint(110, 210)), random.uniform(0.75, 1), False
    if r < 0.68:
        g = random.randint(0, 60)
        return (g, g, g + random.randint(0, 20)), random.uniform(0.8, 1), False
    if r < 0.82:
        g = random.randint(60, 130)
        return (g, g, g + 5), random.uniform(0.35, 0.8), True
    if r < 0.94:
        return (random.randint(170, 230), random.randint(0, 60), random.randint(20, 70)), random.uniform(0.75, 1), False
    return (random.randint(0, 60), random.randint(100, 160), random.randint(40, 100)), random.uniform(0.75, 1), False


def handwriting_layer(a, h, w, answer_lines, print_alpha):
    L = Layer(h, w)
    pens = [pen() for _ in range(random.choice([1, 1, 1, 2, 2, 3]))]
    size = random.uniform(18, 60)
    # Some fragments are print alone, so print alone is seen to stay.
    pieces = 0 if random.random() < 0.3 else random.randint(1, 14)
    # Writing goes on answer lines mostly, and over and between the print too.
    for _ in range(pieces):
        color, strength, pencil = random.choice(pens)
        m = np.zeros((h, w), np.float32)
        if random.random() < 0.65 and a.strokes:
            s = a.stroke(random.choice(a.strokes)).astype(np.float32) / 255
            k = size * random.uniform(0.8, 1.2) / 48
            s = cv2.resize(s, None, fx=k, fy=k, interpolation=cv2.INTER_AREA)
            if s.shape[0] < 4 or s.shape[1] < 4:
                continue
            if random.random() < 0.3:
                ang = random.uniform(-8, 8)
                M = cv2.getRotationMatrix2D((s.shape[1] / 2, s.shape[0] / 2), ang, 1)
                s = cv2.warpAffine(s, M, (s.shape[1], s.shape[0]))
            if answer_lines and random.random() < 0.6:
                ly, x0, x1 = random.choice(answer_lines)
                x = random.randint(x0 - 20, max(x0 - 19, x1 - s.shape[1] // 2))
                yy = ly - int(s.shape[0] * random.uniform(0.6, 0.95))
            else:
                x = random.randint(-s.shape[1] // 2, w - 10)
                yy = random.randint(-s.shape[0] // 2, h - 10)
            sx0, sy0 = max(0, -x), max(0, -yy)
            dx0, dy0 = max(0, x), max(0, yy)
            cw = min(s.shape[1] - sx0, w - dx0)
            chh = min(s.shape[0] - sy0, h - dy0)
            if cw <= 0 or chh <= 0:
                continue
            m[dy0:dy0 + chh, dx0:dx0 + cw] = s[sy0:sy0 + chh, sx0:sx0 + cw]
            if random.random() < 0.5:
                # Pen thicker or thinner than the writer's.
                k = random.choice([2, 3])
                m = cv2.dilate(m, np.ones((k, k), np.uint8)) if random.random() < 0.5 else cv2.erode(m, np.ones((2, 2), np.uint8))
        else:
            mm = np.zeros((h, w), np.uint8)
            width = random.choice([1, 2, 2, 3, 4])
            kind = random.choice(['ring', 'ring', 'tick', 'tick', 'cross', 'underline', 'underline', 'strike', 'scribble',
                                  'arrow', 'bracket'])
            x, yy = random.randint(0, w), random.randint(0, h)
            if kind in ('underline', 'strike', 'ring') and answer_lines and random.random() < 0.5:
                ly, x0, x1 = random.choice(answer_lines)
                yy = ly - (int(size * 0.3) if kind != 'underline' else random.randint(-int(size), 2))
            elif kind in ('underline', 'strike', 'ring'):
                # Around, under or through print.
                ys, xs = np.nonzero(print_alpha > 0.5)
                if len(ys):
                    i = random.randrange(len(ys))
                    x, yy = xs[i], ys[i] + (random.randint(2, 10) if kind == 'underline' else 0)
            draw_mark(mm, kind, x, yy, size * random.uniform(0.5, 1.5), width)
            m = mm.astype(np.float32) / 255
        if pencil:
            grain = cv2.GaussianBlur(np.random.uniform(0.4, 1, (h, w)).astype(np.float32), (3, 3), 0.7)
            m = m * grain
        L.add(m * strength, color)
    return L


# ---------------------------------------------------------------- photo


def paper(h, w):
    base = np.array([random.uniform(200, 255)] * 3, np.float32)
    base += np.random.uniform(-12, 8, 3)
    img = np.ones((h, w, 3), np.float32) * base / 255
    Y, X = np.mgrid[0:h, 0:w].astype(np.float32)
    gx, gy = random.uniform(-0.3, 0.3), random.uniform(-0.3, 0.3)
    light = 1 + gx * (X / w - 0.5) + gy * (Y / h - 0.5)
    if random.random() < 0.3:
        cx, cy, r = random.uniform(0, w), random.uniform(0, h), random.uniform(0.3, 1) * max(h, w)
        light -= random.uniform(0.05, 0.3) * np.exp(-((X - cx) ** 2 + (Y - cy) ** 2) / (r * r))
    return img * light[..., None]


def roughen(img, target):
    h, w = target.shape
    if random.random() < 0.4:
        # A slight tilt and stretch, like a page straightened from a photo.
        d = w * 0.03
        src = np.float32([[0, 0], [w, 0], [w, h], [0, h]])
        dst = src + np.random.uniform(-d, d, (4, 2)).astype(np.float32)
        M = cv2.getPerspectiveTransform(src, dst)
        img = cv2.warpPerspective(img, M, (w, h), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REFLECT)
        target = cv2.warpPerspective(target, M, (w, h), flags=cv2.INTER_NEAREST, borderMode=cv2.BORDER_CONSTANT, borderValue=255)
    if random.random() < 0.5:
        k = random.uniform(0.5, 0.95)
        small = cv2.resize(img, None, fx=k, fy=k, interpolation=cv2.INTER_AREA)
        img = cv2.resize(small, (w, h), interpolation=cv2.INTER_LINEAR)
    if random.random() < 0.5:
        img = cv2.GaussianBlur(img, (0, 0), random.uniform(0.3, 1.1))
    img = img + np.random.normal(0, random.uniform(0.002, 0.03), img.shape).astype(np.float32)
    img = np.clip(img, 0, 1)
    r = random.random()
    if r < 0.3:
        # A scanning app's clean-up: paper pushed to white, ink deepened, edges sharpened.
        lo, hi = random.uniform(0, 0.25), random.uniform(0.6, 0.9)
        img = np.clip((img - lo) / (hi - lo), 0, 1)
        if random.random() < 0.6:
            blur = cv2.GaussianBlur(img, (0, 0), 1.5)
            img = np.clip(img + random.uniform(0.3, 1.2) * (img - blur), 0, 1)
        if random.random() < 0.3:
            # Some apps leave little color.
            g = img.mean(axis=2, keepdims=True)
            img = g + (img - g) * random.uniform(0.3, 1)
    elif r < 0.45:
        img = img ** random.uniform(0.7, 1.5)
    img = (img * 255).astype(np.uint8)
    if random.random() < 0.7:
        ok, enc = cv2.imencode('.jpg', img[..., ::-1], [cv2.IMWRITE_JPEG_QUALITY, random.randint(45, 95)])
        img = cv2.imdecode(enc, cv2.IMREAD_COLOR)[..., ::-1]
    return np.ascontiguousarray(img), target


def sample(a, h=384, w=384):
    P, answer_lines = print_layer(a, h, w)
    H = handwriting_layer(a, h, w, answer_lines, P.alpha)
    img = paper(h, w)
    for layer in (P, H):
        al = layer.alpha[..., None]
        col = layer.color / np.maximum(layer.alpha[..., None], 1e-3)
        col = np.clip(col, 0, 1)
        # Ink filters the light: dark ink over anything is dark.
        img = img * (1 - al + al * col)
    hw = H.alpha
    pr = P.alpha
    target = np.zeros((h, w), np.uint8)
    target[(hw > 0.3) & (pr < 0.4)] = 1
    target[(hw > 0.08) & (hw <= 0.3) & (pr < 0.4)] = 255
    if random.random() < 0.15:
        img = np.clip(img / max(1e-3, float(np.percentile(img, 99))), 0, 1)
        return np.ascontiguousarray((img * 255).astype(np.uint8)), target
    return roughen(img, target)


class Inoob:
    """Real exam photos with their handwriting marked (Inoob/HandwritingSegmentationDataset)."""

    def __init__(self, root):
        self.items = sorted(glob.glob(os.path.join(root, 'im', '*.jpg')))
        self.root = root

    def sample(self, h=384, w=384):
        p = random.choice(self.items)
        img = cv2.imread(p)[..., ::-1]
        gt = cv2.imread(os.path.join(self.root, 'gt', os.path.basename(p)[:-4] + '.png'), cv2.IMREAD_GRAYSCALE)
        # Their pages are whole sheets at about 1000 px: enlarge to our writing size.
        k = random.uniform(1.0, 2.2)
        img = cv2.resize(img, None, fx=k, fy=k, interpolation=cv2.INTER_LINEAR)
        gt = cv2.resize(gt, (img.shape[1], img.shape[0]), interpolation=cv2.INTER_LINEAR)
        if img.shape[0] < h or img.shape[1] < w:
            img = cv2.resize(img, (max(w, img.shape[1]), max(h, img.shape[0])))
            gt = cv2.resize(gt, (img.shape[1], img.shape[0]))
        y = random.randint(0, img.shape[0] - h)
        x = random.randint(0, img.shape[1] - w)
        img = img[y:y + h, x:x + w]
        g = gt[y:y + h, x:x + w]
        t = np.zeros((h, w), np.uint8)
        t[g > 160] = 1
        t[(g > 60) & (g <= 160)] = 255
        if random.random() < 0.5:
            ok, enc = cv2.imencode('.jpg', img[..., ::-1], [cv2.IMWRITE_JPEG_QUALITY, random.randint(50, 95)])
            img = cv2.imdecode(enc, cv2.IMREAD_COLOR)[..., ::-1]
        return np.ascontiguousarray(img), t


if __name__ == '__main__':
    a = Assets(sys.argv[1], sys.argv[2], sys.argv[4] if len(sys.argv) > 4 else None)
    random.seed(3)
    np.random.seed(3)
    tiles = []
    for _ in range(6):
        img, t = sample(a)
        vis = np.zeros_like(img)
        vis[t == 1] = (255, 60, 60)
        vis[t == 255] = (90, 90, 90)
        tiles.append(np.concatenate([img, vis], 1))
    sheet = np.concatenate([np.concatenate(tiles[i:i + 2], 1) for i in range(0, 6, 2)], 0)
    cv2.imwrite(sys.argv[3], sheet[..., ::-1])
