"""midvstream.py <out dir> [every]: reads a MIDV-2020 tar (photo.tar or clips.tar) from stdin and
keeps only the ID-1 card types, each image shrunk to 384 px on its long side, with the VIA
annotation files as they are. Nothing else is written; the archive's MD5 is printed at the end to
check against the published md5.txt. [every]: keep every n-th clip frame."""
import hashlib, os, sys, tarfile
import cv2
import numpy as np

CARDS = {'alb_id', 'esp_id', 'est_id', 'fin_id', 'svk_id'}
out = sys.argv[1]
every = int(sys.argv[2]) if len(sys.argv) > 2 else 1
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


src = Hashing(sys.stdin.buffer)
kept = 0
seen = 0
with tarfile.open(fileobj=src, mode='r|') as tar:
    for m in tar:
        if not m.isfile():
            continue
        parts = m.name.strip('/').split('/')
        # images/<CODE>/<name>.jpg, images/<CODE>/<clip>/<frame>.jpg, annotations/<CODE>.json, annotations/<CODE>/<clip>.json
        if len(parts) < 2 or parts[0] not in ('images', 'annotations') or parts[1].removesuffix('.json') not in CARDS:
            continue
        # Only plain names: no path tricks from the archive.
        if any(p in ('', '.', '..') for p in parts) or not all(p.replace('_', '').replace('.', '').replace('-', '').isalnum() for p in parts):
            continue
        data = tar.extractfile(m).read()
        dest = os.path.join(out, *parts)
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        if parts[0] == 'annotations' and parts[-1].endswith('.json'):
            open(dest, 'wb').write(data)
        elif parts[0] == 'images' and parts[-1].endswith('.jpg'):
            seen += 1
            if len(parts) == 4 and every > 1 and (int(os.path.splitext(parts[-1])[0]) // 6) % every:
                continue
            img = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_COLOR)
            if img is None:
                continue
            s = 384 / max(img.shape[:2])
            # Kept at its own size ratio, so the annotation's pixel corners scale by this file:
            h, w = img.shape[:2]
            small = cv2.resize(img, (round(w * s), round(h * s)), interpolation=cv2.INTER_AREA)
            cv2.imwrite(dest, small, [cv2.IMWRITE_JPEG_QUALITY, 92])
            with open(dest + '.size', 'w') as f:
                f.write(f'{w} {h}')
            kept += 1
            if kept % 500 == 0:
                print(f'{kept} kept, {src.n / 1e9:.2f} GB read', flush=True)
while src.read(1 << 20):
    pass
print(f'done: {kept} images kept of {seen} card images; {src.n} bytes; md5 {md5.hexdigest()}', flush=True)
