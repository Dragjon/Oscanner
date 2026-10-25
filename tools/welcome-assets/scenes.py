"""scenes.py <out dir>: phone-photo-like scenes for the welcome tour's tiles (the 'before' pictures).

receipt.jpg: a crumpled till receipt on a wooden desk, at a slant, under a hand's shadow.
worksheet.jpg: a printed maths worksheet answered in blue pen, photographed flat.
"""
import os
import sys

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

out = sys.argv[1]
os.makedirs(out, exist_ok=True)
rng = np.random.default_rng(21)
F = 'C:/Windows/Fonts/'

# ---- Receipt ----
mono = ImageFont.truetype(F + 'consola.ttf', 30)
mono_b = ImageFont.truetype(F + 'consolab.ttf', 40)
W, H = 640, 1300
r = Image.new('RGB', (W, H), (247, 245, 238))
d = ImageDraw.Draw(r)
d.text((W // 2, 70), 'GREENLEAF MARKET', font=mono_b, fill=(30, 30, 30), anchor='mm')
d.text((W // 2, 115), '12 Mill Lane, Ashford', font=mono, fill=(40, 40, 40), anchor='mm')
d.text((W // 2, 150), '18/10/2026  14:32', font=mono, fill=(40, 40, 40), anchor='mm')
items = [('Sourdough loaf', '3.20'), ('Free range eggs x6', '2.85'), ('Oat milk 1L', '1.75'), ('Apples 1.2kg', '2.40'),
         ('Cheddar 400g', '4.10'), ('Coffee beans', '6.50'), ('Tomatoes', '1.90'), ('Pasta 500g', '1.15'), ('Olive oil', '5.60')]
y = 220
for name, price in items:
    d.text((40, y), name, font=mono, fill=(35, 35, 35))
    d.text((W - 40, y), price, font=mono, fill=(35, 35, 35), anchor='ra')
    y += 52
d.line((40, y + 10, W - 40, y + 10), fill=(60, 60, 60), width=3)
d.text((40, y + 40), 'TOTAL', font=mono_b, fill=(25, 25, 25))
d.text((W - 40, y + 40), '29.45', font=mono_b, fill=(25, 25, 25), anchor='ra')
d.text((40, y + 110), 'CARD  ************4821', font=mono, fill=(40, 40, 40))
d.text((W // 2, y + 200), 'Thank you for shopping local', font=mono, fill=(40, 40, 40), anchor='mm')
paper = np.array(r).astype(np.float32)
# Crumpled: soft creases of light and shade across the paper.
yy, xx = np.mgrid[0:H, 0:W]
shade = np.ones((H, W), np.float32)
for _ in range(14):
    a = rng.uniform(0, np.pi)
    c = rng.uniform(-200, 200)
    dist = (xx - W / 2) * np.cos(a) + (yy - H / 2) * np.sin(a) - c
    shade += rng.uniform(-0.07, 0.07) * np.exp(-(dist / rng.uniform(8, 40)) ** 2) * np.sign(dist)
shade = cv2.GaussianBlur(shade, (0, 0), 3)
paper *= shade[..., None]
# The desk: warm wood with grain.
SW, SH = 1500, 2000
desk = np.zeros((SH, SW, 3), np.float32)
base = np.array([62, 95, 140], np.float32)  # BGR-ish warm brown, written as RGB below
grain = np.sin(np.linspace(0, 90, SW))[None, :] * 9 + rng.normal(0, 5, (SH, SW))
grain = cv2.GaussianBlur(grain.astype(np.float32), (0, 0), sigmaX=1.5, sigmaY=25)
desk[:] = (150, 104, 66)
desk += grain[..., None]
quad = np.float32([[430, 330], [1090, 410], [1020, 1720], [330, 1650]])
m = cv2.getPerspectiveTransform(np.float32([[0, 0], [W, 0], [W, H], [0, H]]), quad)
warped = cv2.warpPerspective(paper, m, (SW, SH))
mask = cv2.warpPerspective(np.ones((H, W), np.float32), m, (SW, SH))
mask = cv2.GaussianBlur(mask, (3, 3), 0)
# A soft drop shadow under the receipt, then the receipt.
shadow = cv2.GaussianBlur(cv2.warpAffine(mask, np.float32([[1, 0, 18], [0, 1, 24]]), (SW, SH)), (0, 0), 18)
scene = desk * (1 - 0.45 * shadow[..., None])
scene = scene * (1 - mask[..., None]) + warped * mask[..., None]
# The phone's own shadow falling across the lower left.
hand = np.clip(1 - ((xx_ := np.mgrid[0:SH, 0:SW][1]) + np.mgrid[0:SH, 0:SW][0] * 0.6 - 1300) / 500, 0, 1)
scene *= (1 - 0.35 * cv2.GaussianBlur(hand.astype(np.float32), (0, 0), 40))[..., None]
scene = np.clip(scene + rng.normal(0, 4, scene.shape), 0, 255).astype(np.uint8)
Image.fromarray(scene).save(os.path.join(out, 'receipt.jpg'), quality=90)

# ---- Worksheet ----
WW, WH = 1240, 1754
ws = Image.new('RGB', (WW, WH), (250, 250, 247))
d = ImageDraw.Draw(ws)
arial = ImageFont.truetype(F + 'arial.ttf', 38)
arial_b = ImageFont.truetype(F + 'arialbd.ttf', 52)
pen = ImageFont.truetype(F + 'Inkfree.ttf', 58)
d.text((110, 110), 'Fractions: practice sheet', font=arial_b, fill=(20, 20, 20))
d.text((110, 190), 'Name: ______________________', font=arial, fill=(30, 30, 30))
d.text((WW - 110, 190), 'Class 5B', font=arial, fill=(30, 30, 30), anchor='ra')
qs = ['1.  1/2 + 1/4 =', '2.  3/5 - 1/5 =', '3.  2/3 of 12 =', '4.  Simplify 6/8', '5.  1/3 + 1/6 =', '6.  Which is bigger, 3/4 or 5/8?']
answers = ['3/4', '2/5', '8', '3/4', '1/2', '3/4']
for i, (q, a) in enumerate(zip(qs, answers)):
    y = 330 + i * 200
    d.text((110, y), q, font=arial, fill=(25, 25, 25))
    d.line((110, y + 120, WW - 110, y + 120), fill=(200, 200, 200), width=2)
    d.text((760 if i < 5 else 900, y - 10), a, font=pen, fill=(28, 52, 160))
# Pen workings in the margin and a tick.
d.text((110, 1580), 'check: 6/8 = 3/4', font=pen, fill=(28, 52, 160))
d.text((210, 270), 'Maya', font=pen, fill=(28, 52, 160))
sheet = np.array(ws).astype(np.float32)
light = (1 - 0.12 * np.mgrid[0:WH, 0:WW][1] / WW)[..., None]
sheet = np.clip(sheet * light + rng.normal(0, 3, sheet.shape), 0, 255).astype(np.uint8)
canvas = np.full((2000, 1500, 3), (70, 74, 80), np.uint8)
canvas[120:120 + WH, 130:130 + WW] = sheet
Image.fromarray(canvas).save(os.path.join(out, 'worksheet.jpg'), quality=90)
print('ok')
