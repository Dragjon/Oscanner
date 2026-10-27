"""Fine-tunes the corner network (corners.onnx, DocAligner's lcnet100) on real photos of pages.

    python cornertrain.py make <out.npz> <n per photo> <seed> <mendeley dir> [<scenes dir>...]
    python cornertrain.py smartdoc <out.npz> <every nth frame> <seed> <background dir>...
    python cornertrain.py empty <out.npz> <n> <seed> <mendeley dir>
    python cornertrain.py train <epochs> <init .onnx|.pt> <peak lr> <out.pt> <train.npz>... [--val <val.npz>]
    python cornertrain.py export <model.pt> <out.onnx>

make: frames as the app gives them to the network (CornerNet.kt): gray, padded out to 4:3 if
longer, 256x256, with the page's corners (top left, top right, bottom right, bottom left, 0..1).
From each Mendeley photo (realpages.py's reading of it, its first 130 held out unless HOLD=only)
[n] frames: turned, mirrored, cropped so the page takes from a sixth to most of the frame, with
light, blur, noise and JPEG varied. A scenes dir (scene_*.png with truth.txt, as
../detector-bench/make_scenes.py writes) adds each scene once, turned and mirrored at random:
drawn pages, so the network keeps what it knew.

smartdoc: frames of SmartDoc 2015's videos (a background's folders of NNNN.jpg, each with
corners.txt): pages lying among other things on a desk, which Mendeley's photos rarely show.
empty: frames with no page, cut from Mendeley photos beside the page; their heatmaps are all
cold, so the network doesn't put a page on an empty desk or wall.

train: the ONNX model as a PyTorch one (onnx2torch, its batch norms already folded in), learning
four heatmaps at 128x128, a Gaussian of SIGMA cells round each corner, as it puts them out.
"""
import os
import sys

import cv2
import numpy as np

SIDE = 256
SIGMA = 2.0


def order(q):
    """Corners as top left, top right, bottom right, bottom left, whatever way the page turns."""
    s, d = q.sum(1), q[:, 1] - q[:, 0]
    return np.array([q[np.argmin(s)], q[np.argmin(d)], q[np.argmax(s)], q[np.argmax(d)]], np.float32)


def network_input(gray, pts):
    """[gray] padded to 4:3 and squeezed to SIDE x SIDE as CornerNet.kt does; [pts] in pixels to 0..1 of that."""
    h, w = gray.shape
    wide = max(w, (h * 3 + 2) // 4) if h >= w else w
    tall = max(h, (w * 3 + 2) // 4) if w > h else h
    left, top = (wide - w) // 2, (tall - h) // 2
    g = cv2.copyMakeBorder(gray, top, tall - h - top, left, wide - w - left, cv2.BORDER_REPLICATE)
    g = cv2.resize(g, (SIDE, SIDE), interpolation=cv2.INTER_AREA)
    return g, (pts + [left, top]) / [wide, tall]


def camera(g, rng):
    """Light, blur, noise and JPEG as phones vary them."""
    g = g.astype(np.float32)
    g = (g - 128) * rng.uniform(0.7, 1.25) + 128 + rng.uniform(-30, 25)
    if rng.random() < 0.5:
        # Light falling off across the frame.
        h, w = g.shape
        yy, xx = np.mgrid[0:h, 0:w] / max(h, w)
        g *= 1 + rng.uniform(-0.25, 0.25) * (xx - 0.5) + rng.uniform(-0.25, 0.25) * (yy - 0.5)
    if rng.random() < 0.4:
        k = int(rng.choice([3, 5]))
        g = cv2.GaussianBlur(g, (k, k), 0)
    g += rng.normal(0, rng.uniform(0, 5), g.shape)
    g = np.clip(g, 0, 255).astype(np.uint8)
    if rng.random() < 0.6:
        g = cv2.imdecode(cv2.imencode('.jpg', g, [cv2.IMWRITE_JPEG_QUALITY, int(rng.uniform(40, 95))])[1], 0)
    return g


def turned(img, pts, rng):
    """Turned a quarter at random (more often on its side when wide, as phones are held) and mirrored half the time."""
    from realcards import turn
    wide = img.shape[1] > img.shape[0]
    k = rng.choice([1, 3, 0, 2], p=[0.35, 0.35, 0.25, 0.05]) if wide else rng.choice([0, 1, 3, 2], p=[0.7, 0.1, 0.1, 0.1])
    img, pts = turn(img, pts.copy(), k)
    if rng.random() < 0.5:
        img = img[:, ::-1].copy()
        pts = np.stack([img.shape[1] - 1 - pts[:, 0], pts[:, 1]], 1)
    return img, pts


def crop(img, pts, rng):
    """A crop holding the whole page, which takes a sixth to most of it; None if none fits."""
    h, w = img.shape[:2]
    lo, hi = pts.min(0), pts.max(0)
    area = cv2.contourArea(pts.astype(np.float32))
    share = np.exp(rng.uniform(np.log(0.16), np.log(0.9)))
    aspect = rng.uniform(0.56, 1.0) if h >= w else rng.uniform(1.0, 1.78)
    cw = np.sqrt(area / share * aspect)
    ch = cw / aspect
    cw, ch = min(cw, w), min(ch, h)
    margin = 0.01 * max(cw, ch)
    x0, x1 = max(0, hi[0] + margin - cw), min(w - cw, lo[0] - margin)
    y0, y1 = max(0, hi[1] + margin - ch), min(h - ch, lo[1] - margin)
    if x0 > x1 or y0 > y1:
        return None
    x, y = rng.uniform(x0, x1), rng.uniform(y0, y1)
    return img[int(y):int(y + ch), int(x):int(x + cw)], pts - [int(x), int(y)]


def make(out, n, seed, root, scenes):
    from realpages import mendeley
    rng = np.random.default_rng(seed)
    X, Q = [], []
    for item in mendeley(root, os.environ.get('HOLD', 'out')):
        img = cv2.imread(item[0])
        if img is None:
            continue
        gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        pts = item[1] * [img.shape[1] / item[2], img.shape[0] / item[3]]
        for _ in range(n):
            g, p = turned(gray, pts, rng)
            # A page that fills the photo leaves no room for a small share of the frame: tried again.
            for _ in range(6):
                got = crop(g, p, rng)
                if got is not None:
                    break
            else:
                continue
            g, q = network_input(camera(got[0], rng), got[1])
            X.append(g)
            Q.append(order(q))
    for d in scenes:
        for line in open(os.path.join(d, 'truth.txt')):
            f = line.split()
            if len(f) < 2:
                continue
            g = cv2.imread(os.path.join(d, f[0]), cv2.IMREAD_GRAYSCALE)
            if g is None:
                continue
            pts = np.array([[float(v) for v in c.split(',')] for c in f[1].split(';')], np.float32) * [g.shape[1], g.shape[0]]
            g, p = turned(g, pts, rng)
            g, q = network_input(g, p)
            X.append(g)
            Q.append(order(q))
    np.savez_compressed(out, X=np.array(X), Q=np.array(Q, np.float32))
    print(len(X), 'frames')


def smartdoc(out, nth, seed, dirs):
    rng = np.random.default_rng(seed)
    X, Q = [], []
    for bg in dirs:
        for doc in sorted(os.listdir(bg)):
            d = os.path.join(bg, doc)
            if not os.path.isfile(os.path.join(d, 'corners.txt')):
                continue
            for i, line in enumerate(open(os.path.join(d, 'corners.txt'))):
                f = line.split()
                if i % nth or len(f) < 5:
                    continue
                g = cv2.imread(os.path.join(d, f'{int(f[0]):04d}.jpg'), cv2.IMREAD_GRAYSCALE)
                if g is None:
                    continue
                pts = np.array([[float(v) for v in c.split(',')] for c in f[1:5]], np.float32)
                g, p = turned(g, pts, rng)
                g, q = network_input(camera(g, rng), p)
                X.append(g)
                Q.append(order(q))
    np.savez_compressed(out, X=np.array(X), Q=np.array(Q, np.float32))
    print(len(X), 'frames')


def empty(out, n, seed, root):
    """[n] frames from beside the pages of Mendeley photos, each wholly off the page."""
    from realpages import mendeley
    rng = np.random.default_rng(seed)
    items = list(mendeley(root, 'out'))
    X = []
    while len(X) < n:
        item = items[rng.integers(len(items))]
        img = cv2.imread(item[0], cv2.IMREAD_GRAYSCALE)
        if img is None:
            continue
        h, w = img.shape
        pts = item[1] * [w / item[2], h / item[3]]
        page = np.zeros((h, w), np.uint8)
        cv2.fillPoly(page, [pts.astype(np.int32)], 1)
        for _ in range(20):
            cw = int(w * rng.uniform(0.25, 0.6))
            ch = int(min(h, cw * (rng.uniform(1.0, 1.78) if rng.random() < 0.7 else rng.uniform(0.56, 1.0))))
            if ch < 32 or cw < 32:
                continue
            x, y = rng.integers(0, w - cw + 1), rng.integers(0, h - ch + 1)
            if page[y:y + ch, x:x + cw].mean() < 0.01:
                g, _ = turned(img[y:y + ch, x:x + cw], np.zeros((4, 2), np.float32), rng)
                X.append(network_input(camera(g, rng), np.zeros((4, 2)))[0])
                break
    # No corners: NaN, which heatmaps() leaves cold.
    np.savez_compressed(out, X=np.array(X), Q=np.full((len(X), 4, 2), np.nan, np.float32))
    print(len(X), 'frames')


def heatmaps(Q, size=SIDE // 2):
    import torch
    yy, xx = torch.meshgrid(torch.arange(size), torch.arange(size), indexing='ij')
    c = torch.from_numpy(Q) * size - 0.5  # cell centers at (i + 0.5) / size
    d2 = (xx[None, None] - c[..., 0, None, None]) ** 2 + (yy[None, None] - c[..., 1, None, None]) ** 2
    return torch.nan_to_num(torch.exp(-d2 / (2 * SIGMA ** 2)))


def peaks(hm):
    import torch
    """Each corner where its heatmap peaks, 0..1, and how high: the app's way, roughly."""
    n, k, h, w = hm.shape
    flat = hm.reshape(n, k, -1)
    v, i = flat.max(2)
    return torch.stack([((i % w).float() + 0.5) / w, ((i // w).float() + 0.5) / h], -1).numpy(), v.numpy()


def train(epochs, init, peak, out, paths, val):
    import torch
    import torch.nn.functional as F
    torch.manual_seed(0)
    rng = np.random.default_rng(0)
    dev = 'cuda' if torch.cuda.is_available() else 'cpu'
    print('on', torch.cuda.get_device_name(0) if dev == 'cuda' else 'cpu', flush=True)
    if init.endswith('.onnx'):
        import onnx
        from onnx2torch import convert
        net = convert(onnx.load(init))
    else:
        net = torch.load(init, weights_only=False, map_location='cpu')
    net.to(dev)
    data = [np.load(p) for p in paths]
    X = np.concatenate([d['X'] for d in data])
    Q = np.concatenate([d['Q'] for d in data])
    print('train', len(X), flush=True)
    vx = vq = None
    if val:
        v = np.load(val)
        vx, vq = v['X'], v['Q']
    bs = 16
    opt = torch.optim.AdamW(net.parameters(), peak / 4, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, peak, total_steps=epochs * (len(X) // bs + 1), pct_start=0.15)

    def batch(x):
        t = torch.from_numpy(x.astype(np.float32) / 255)[:, None]
        return t.repeat(1, 3, 1, 1).to(dev)

    def check():
        net.eval()
        errs, sure = [], []
        with torch.no_grad():
            for i in range(0, len(vx), 32):
                p, v = peaks(net(batch(vx[i:i + 32])).cpu())
                errs.append(np.linalg.norm(p - vq[i:i + 32], axis=-1).max(1))
                sure.append(v.min(1))
        e, s = np.concatenate(errs), np.concatenate(sure)
        return f'val worst corner median {np.median(e):.4f}, within 2% {(e <= 0.02).mean():.3f}, 5% {(e <= 0.05).mean():.3f}, all sure {(s >= 0.5).mean():.3f}'

    if vx is not None:
        print('before |', check(), flush=True)
    for ep in range(epochs):
        net.train()
        order_ = rng.permutation(len(X))
        tot = 0
        for b in range(0, len(X), bs):
            idx = order_[b:b + bs]
            x = batch(X[idx])
            y = heatmaps(Q[idx]).to(dev)
            p = net(x).clamp(1e-6, 1 - 1e-6)
            # Cells near a corner weigh more: they're few among the 16,384.
            w = 1 + 20 * y
            loss = (F.binary_cross_entropy(p, y, reduction='none') * w).mean()
            opt.zero_grad()
            loss.backward()
            opt.step()
            sched.step()
            tot += loss.item() * len(idx)
            if b // bs % 50 == 49:
                print(f'  step {b // bs + 1} of {len(X) // bs + 1}, loss {tot / (b + len(idx)):.5f}', flush=True)
        torch.save(net.cpu(), out)
        net.to(dev)
        print(f'epoch {ep} loss {tot / len(X):.5f}' + (' | ' + check() if vx is not None else ''), flush=True)


def export(model, out):
    import torch
    net = torch.load(model, weights_only=False, map_location='cpu')
    net.eval()
    torch.onnx.export(net, torch.zeros(1, 3, SIDE, SIDE), out, input_names=['img'], output_names=['heatmap'], opset_version=17, do_constant_folding=True)
    print('ok')


if __name__ == '__main__':
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    cmd, args = sys.argv[1], sys.argv[2:]
    if cmd == 'make':
        make(args[0], int(args[1]), int(args[2]), args[3], args[4:])
    elif cmd == 'smartdoc':
        smartdoc(args[0], int(args[1]), int(args[2]), args[3:])
    elif cmd == 'empty':
        empty(args[0], int(args[1]), int(args[2]), args[3])
    elif cmd == 'train':
        val = args[args.index('--val') + 1] if '--val' in args else None
        rest = [a for a in args[4:] if a != '--val' and a != val]
        train(int(args[0]), args[1], float(args[2]), args[3], rest, val)
    elif cmd == 'export':
        export(args[0], args[1])
