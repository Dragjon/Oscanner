"""tiles.py <scenes dir> <res dir>: the welcome tour's before/after tile pictures, framed and aligned."""
import os
import sys

import cv2
import numpy as np

src, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)


def read(name):
    return cv2.imread(os.path.join(src, name))


def save(name, img, size):
    img = cv2.resize(img, size, interpolation=cv2.INTER_AREA)
    path = os.path.join(out, f'welcome_{name}.webp')
    cv2.imwrite(path, img, [cv2.IMWRITE_WEBP_QUALITY, 82])
    return os.path.getsize(path)


def fit(img, aspect, top=0.0):
    """Centre-crop to width/height = aspect; top picks where a tall crop starts (0 top, 0.5 middle)."""
    h, w = img.shape[:2]
    if w / h > aspect:
        nw = int(h * aspect)
        x = (w - nw) // 2
        return img[:, x:x + nw]
    nh = int(w / aspect)
    y = int((h - nh) * top)
    return img[y:y + nh]


def align(moving, fixed):
    """[moving] warped onto [fixed] by a homography found with ECC on gray copies."""
    g1 = cv2.cvtColor(cv2.resize(fixed, (fixed.shape[1] // 2, fixed.shape[0] // 2)), cv2.COLOR_BGR2GRAY).astype(np.float32)
    g2 = cv2.cvtColor(cv2.resize(moving, (fixed.shape[1] // 2, fixed.shape[0] // 2)), cv2.COLOR_BGR2GRAY).astype(np.float32)
    warp = np.eye(3, dtype=np.float32)
    _, warp = cv2.findTransformECC(g1, g2, warp, cv2.MOTION_HOMOGRAPHY, (cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 200, 1e-6), None, 5)
    s = np.diag([2, 2, 1]).astype(np.float32)
    full = s @ warp @ np.linalg.inv(s)
    small = cv2.resize(moving, (fixed.shape[1], fixed.shape[0]))
    return cv2.warpPerspective(small, full, (fixed.shape[1], fixed.shape[0]), flags=cv2.INTER_LINEAR + cv2.WARP_INVERSE_MAP)


total = 0
# Receipt: the photo around the receipt, and the scan it became (not the same frame: a reveal).
photo = read('receipt.jpg')[230:1780, 240:1200]
total += save('receipt_before', fit(photo, 0.6), (480, 800))
total += save('receipt_after', fit(read('receipt-after.jpg'), 0.6, top=0.0), (480, 800))

# Handwriting: the worksheet as photographed, aligned onto the cleaned page; questions 2 to 4.
after = read('worksheet-after.jpg')
sheet = read('worksheet.jpg')[120:120 + 1754, 130:130 + 1240]
before = align(sheet, after)
box = (slice(440, 1100), slice(90, 922))
total += save('pen_before', before[box], (480, 380))
total += save('pen_after', after[box], (480, 380))

# Book page near the spine, before and after flattening (the flat page scaled back onto the photo's frame).
def frac(img, y0, y1, x0, x1):
    h, w = img.shape[:2]
    return img[int(h * y0):int(h * y1), int(w * x0):int(w * x1)]


book_before = read('book-before.jpg')
book_after = cv2.resize(read('book-after.jpg'), (book_before.shape[1], book_before.shape[0]))
total += save('book_before', fit(frac(book_before, 0.50, 0.92, 0.40, 1.0), 480 / 380), (480, 380))
total += save('book_after', fit(frac(book_after, 0.50, 0.92, 0.40, 1.0), 480 / 380), (480, 380))

# Read and translate: the same rendered page, English and French.
box = (slice(110, 870), slice(40, 1340))
total += save('translate_before', read('translate-before.jpg')[box], (960, 560))
total += save('translate_after', read('translate-after.jpg')[box], (960, 560))

# Old photo: trees, lighthouse and shore, faded and dusty, then the Photo look.
total += save('photo_before', fit(frac(read('photo-before.jpg'), 0.10, 0.80, 0.22, 0.70), 480 / 540), (480, 540))
total += save('photo_after', fit(frac(read('photo-after.jpg'), 0.10, 0.80, 0.22, 0.70), 480 / 540), (480, 540))
print('total', total // 1024, 'KB')
