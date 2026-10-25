"""scenes2.py <print jpg> <out dir>: an old faded print with a torn corner on a desk, and an open book page photographed.

The print is "Biloxi Light House, Miss." (Detroit Photographic Co., 1901), public domain, from
Wikimedia Commons (Library of Congress photochrom collection).
"""
import os
import sys

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

src, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)
rng = np.random.default_rng(4)

# ---- Old print ----
p = cv2.cvtColor(cv2.imread(src), cv2.COLOR_BGR2RGB).astype(np.float32) / 255
h, w = p.shape[:2]
# Faded: blacks lifted, contrast flattened, a yellow-magenta cast.
p = 0.28 + 0.62 * p
p = p * np.array([1.06, 0.96, 0.78]) + np.array([0.03, 0.0, -0.02])
gray = p.mean(axis=2, keepdims=True)
p = gray + 0.55 * (p - gray)
# Dust: small dark and light specks; scratches: thin pale lines.
img = (np.clip(p, 0, 1) * 255).astype(np.uint8)
for _ in range(260):
    x, y = int(rng.integers(w)), int(rng.integers(h))
    r = int(rng.integers(1, 3))
    c = int(rng.choice([35, 225]))
    cv2.circle(img, (x, y), r, (c, c, c - 10), -1)
for _ in range(6):
    # Short, thin, nearly straight: what a print picks up in a drawer.
    x0, y0 = int(rng.integers(40, w - 40)), int(rng.integers(0, h - 200))
    length = int(rng.integers(90, 200))
    cv2.line(img, (x0, y0), (x0 + int(rng.integers(-12, 12)), y0 + length), (236, 230, 215), 1)
# White print border.
b = 40
bordered = np.full((h + 2 * b, w + 2 * b, 3), (238, 232, 218), np.uint8)
bordered[b:b + h, b:b + w] = img
H, W = bordered.shape[:2]
# The desk, warm wood; the print laid on it, its bottom-left corner torn away.
SW, SH = 2200, 1800
desk = np.zeros((SH, SW, 3), np.float32)
desk[:] = (112, 82, 58)
grain = cv2.GaussianBlur(rng.normal(0, 6, (SH, SW)).astype(np.float32), (0, 0), sigmaX=2, sigmaY=30)
desk += grain[..., None]
x0, y0 = (SW - W) // 2, (SH - H) // 2
mask = np.ones((H, W), np.uint8)
edge = [(int(330 * t + rng.integers(-14, 14)), int(H - 300 * (1 - t) + rng.integers(-14, 14))) for t in np.linspace(0, 1, 16)]
tear = np.array([(0, H - 300)] + edge + [(330, H), (0, H)], np.int32)
rim = bordered.copy()
cv2.fillPoly(rim, [np.array([(x + 9, y + 9) for x, y in edge] + [(0, H)], np.int32)], (244, 241, 234))
cv2.fillPoly(mask, [tear], 0)
scene = desk.copy()
region = scene[y0:y0 + H, x0:x0 + W]
keep = mask.astype(bool)
region[keep] = rim[keep]
# Soft shadow under the print's kept edges.
shadow = np.zeros((SH, SW), np.float32)
shadow[y0 + 10:y0 + H + 10, x0 + 8:x0 + W + 8] = mask
shadow = cv2.GaussianBlur(shadow, (0, 0), 12)
under = (scene == desk).all(axis=2)
scene[under] *= (1 - 0.35 * shadow[under])[..., None]
scene = np.clip(scene + rng.normal(0, 3, scene.shape), 0, 255).astype(np.uint8)
Image.fromarray(scene).save(os.path.join(out, 'oldprint.jpg'), quality=91)
with open(os.path.join(out, 'oldprint.txt'), 'w') as f:
    f.write(f'{x0} {y0} {W} {H} desk 112,82,58\n')

# ---- Open book page ----
PW, PH = 1500, 2100
page = Image.new('RGB', (PW, PH), (243, 236, 222))
d = ImageDraw.Draw(page)
serif = ImageFont.truetype('C:/Windows/Fonts/times.ttf', 44)
serif_b = ImageFont.truetype('C:/Windows/Fonts/timesbd.ttf', 60)
d.text((150, 170), 'Chapter Three', font=serif_b, fill=(30, 26, 22))
words = ('The tide had gone out by the time they reached the harbour, leaving the small boats '
         'lying on their sides in the mud. Along the quay the lamps were already lit, and the smell of '
         'tar and rope hung in the still evening air. Nobody spoke for a long while. Somewhere beyond '
         'the breakwater a bell was ringing, slow and patient, the way it must have rung for a hundred '
         'years before any of them were born. ').split()
y = 320
i = 0
while y < PH - 200:
    line, x = [], 150
    while True:
        wd = words[i % len(words)]
        width = d.textlength(wd + ' ', font=serif)
        if x + width > PW - 150:
            break
        line.append(wd)
        x += width
        i += 1
    d.text((150, y), ' '.join(line), font=serif, fill=(34, 30, 26))
    y += 66
d.text((PW // 2, PH - 110), '37', font=serif, fill=(60, 54, 48), anchor='mm')
page = np.array(page).astype(np.float32)
# Paper: faint fibre noise.
page *= (1 + cv2.GaussianBlur(rng.normal(0, 0.03, (PH, PW)).astype(np.float32), (0, 0), 1.2))[..., None]
# On a darker ground (the facing page's edge, the table), bent toward the spine on the right.
CW, CH = 1800, 2500
canvas = np.full((CH, CW, 3), (52, 46, 42), np.float32)
ox, oy = 120, 200
canvas[oy:oy + PH, ox:ox + PW] = page
yy, xx = np.mgrid[0:CH, 0:CW].astype(np.float32)
u = np.clip((xx - ox) / PW, 0, 1.05)
t = (yy - oy) / PH
amount = 0.055 * PH
dy = amount * u ** 3 * (0.3 + 1.4 * t) - amount * 0.25 * (u - 0.5) * (t - 0.5)
mapx = xx - 0.06 * PW * u ** 4  # squeezed toward the spine
mapy = yy - dy
bent = cv2.remap(canvas, mapx, mapy, cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE)
# The spine's shadow: darker into the gutter on the right, and a little light falloff.
shade = 1 - 0.55 * np.clip((xx - (ox + 0.78 * PW)) / (0.22 * PW), 0, 1) ** 2.2
shade *= 1 - 0.10 * (yy / CH)
bent *= shade[..., None]
bent = np.clip(bent + rng.normal(0, 3, bent.shape), 0, 255).astype(np.uint8)
Image.fromarray(bent).save(os.path.join(out, 'bookpage.jpg'), quality=91)
with open(os.path.join(out, 'bookpage.txt'), 'w') as f:
    f.write(f'{ox} {oy} {PW} {PH}\n')
print('ok')
