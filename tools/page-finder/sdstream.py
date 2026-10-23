"""sdstream.py <out dir> [every]: reads SmartDoc 2015 Challenge 1's testDataset.tar.gz (or the
sample) from stdin and keeps every n-th frame (default 5) of each video, shrunk to 480 px on its
long side, as <out dir>/<background>/<doc>/<frame>.jpg, with the page's corners from the ground
truth in <out dir>/<background>/<doc>/corners.txt (frame index, then tl tr br bl as x,y in the
shrunk frame's pixels; rejected frames left out). The archive's MD5 is printed at the end to check
against Zenodo's."""
import hashlib
import os
import sys
import tarfile
import tempfile
import xml.etree.ElementTree as ET

import cv2

out = sys.argv[1]
every = int(sys.argv[2]) if len(sys.argv) > 2 else 5
LONG = 480
md5 = hashlib.md5()


class Hashing:
    def __init__(self, f):
        self.f = f
        self.n = 0

    def read(self, size=-1):
        b = self.f.read(size)
        md5.update(b)
        self.n += len(b)
        return b


def safe(parts):
    return all(p not in ('', '.', '..') and p.replace('_', '').replace('.', '').replace('-', '').isalnum() for p in parts)


videos, truths = {}, {}
src = Hashing(sys.stdin.buffer)
tmp = tempfile.mkdtemp()
with tarfile.open(fileobj=src, mode='r|gz') as tar:
    for m in tar:
        if not m.isfile():
            continue
        parts = m.name.strip('/').split('/')
        if not safe(parts):
            continue
        name = parts[-1]
        if name.endswith('.gt.xml'):
            bg = parts[-2].removesuffix('_gt')
            truths[(bg, name.removesuffix('.gt.xml'))] = tar.extractfile(m).read()
        elif name.endswith('.avi'):
            # Videos come before or after their truth; keep the frames now (the video is too big to hold).
            path = os.path.join(tmp, 'v.avi')
            with open(path, 'wb') as f:
                f.write(tar.extractfile(m).read())
            key = (parts[-2], name.removesuffix('.avi'))
            v = cv2.VideoCapture(path)
            dest = os.path.join(out, *key)
            os.makedirs(dest, exist_ok=True)
            i, kept, scale = 0, [], 1.0
            while True:
                ok, frame = v.read()
                if not ok:
                    break
                i += 1  # the ground truth counts frames from 1
                if (i - 1) % every:
                    continue
                scale = LONG / max(frame.shape[:2])
                small = cv2.resize(frame, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
                cv2.imwrite(os.path.join(dest, f'{i:04d}.jpg'), small, [cv2.IMWRITE_JPEG_QUALITY, 92])
                kept.append(i)
            v.release()
            os.remove(path)
            videos[key] = (kept, scale)
            print(f'{key[0]}/{key[1]}: {len(kept)} frames of {i}, {src.n / 1e9:.2f} GB read', flush=True)
while src.read(1 << 20):
    pass

for key, (kept, scale) in videos.items():
    if key not in truths:
        print(f'no ground truth for {key}', flush=True)
        continue
    root = ET.fromstring(truths[key])
    lines = []
    for fr in root.iter('frame'):
        idx = int(fr.get('index'))
        if fr.get('rejected') == 'true' or idx not in kept:
            continue
        pts = {p.get('name'): (float(p.get('x')) * scale, float(p.get('y')) * scale) for p in fr.iter('point')}
        lines.append(f'{idx} ' + ' '.join(f'{pts[k][0]:.2f},{pts[k][1]:.2f}' for k in ('tl', 'tr', 'br', 'bl')))
    with open(os.path.join(out, *key, 'corners.txt'), 'w') as f:
        f.write('\n'.join(lines) + '\n')
print(f'done: {len(videos)} videos, {len(truths)} truths; {src.n} bytes; md5 {md5.hexdigest()}', flush=True)
