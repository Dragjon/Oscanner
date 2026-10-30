"""Trains the handwriting mask network on drawn fragments (gen.py) and real exam photos (Inoob).

    python train.py --fonts F --strokes S --test-strokes T --inoob I --out hand.pt [--steps N] [--resume hand.pt]

Prints the handwriting IoU on held-out drawn fragments (GNHK test writers) and on Inoob's
validation photos as it goes.
"""
import argparse
import os
import random
import time

import numpy as np
import torch
import torch.nn.functional as F
from torch.utils.data import DataLoader, IterableDataset

import gen
from net import HandNet


class Pieces(IterableDataset):
    def __init__(self, args, strokes, real_share, size):
        self.args, self.strokes, self.real_share, self.size = args, strokes, real_share, size

    def __iter__(self):
        info = torch.utils.data.get_worker_info()
        seed = (info.id if info else 0) * 7919 + int(time.time() * 1000) % 100000
        random.seed(seed)
        np.random.seed(seed % (2 ** 32))
        a = gen.Assets(self.args.fonts, self.strokes, self.args.words)
        real = gen.Inoob(os.path.join(self.args.inoob, 'train')) if self.real_share > 0 else None
        while True:
            try:
                if real and random.random() < self.real_share:
                    img, t = real.sample(self.size, self.size)
                else:
                    img, t = gen.sample(a, self.size, self.size)
            except Exception as e:  # a font missing a glyph, a stroke too small: draw another
                continue
            yield torch.from_numpy(img).permute(2, 0, 1), torch.from_numpy(t)


def fixed(args, n, real, seed):
    random.seed(seed)
    np.random.seed(seed)
    out = []
    if real:
        r = gen.Inoob(os.path.join(args.inoob, 'val'))
        for _ in range(n):
            out.append(r.sample(512, 512))
    else:
        a = gen.Assets(args.fonts, args.test_strokes, args.words)
        while len(out) < n:
            try:
                out.append(gen.sample(a, 512, 512))
            except Exception:
                pass
    x = torch.stack([torch.from_numpy(i).permute(2, 0, 1) for i, _ in out])
    y = torch.stack([torch.from_numpy(t) for _, t in out])
    return x, y


def loss_fn(logit, t):
    t = t.long()
    valid = (t != 255).float()
    y = (t == 1).float()
    bce = F.binary_cross_entropy_with_logits(logit[:, 0], y, reduction='none')
    bce = (bce * valid).sum() / valid.sum().clamp(min=1)
    p = torch.sigmoid(logit[:, 0]) * valid
    inter = (p * y).sum()
    dice = 1 - (2 * inter + 1) / (p.sum() + y.sum() + 1)
    return bce + 0.5 * dice


@torch.no_grad()
def score(net, x, y, dev):
    net.eval()
    inter = union = 0
    tp = fp = fn = 0
    for i in range(0, len(x), 8):
        xb = x[i:i + 8].to(dev).float() / 255
        tb = y[i:i + 8].to(dev)
        with torch.autocast('cuda', dtype=torch.float16, enabled=dev == 'cuda'):
            p = net(xb)[:, 0] > 0
        v = tb != 255
        t = tb == 1
        tp += (p & t & v).sum().item()
        fp += (p & ~t & v).sum().item()
        fn += (~p & t & v).sum().item()
    net.train()
    return tp / max(1, tp + fp + fn), tp / max(1, tp + fp), tp / max(1, tp + fn)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--fonts')
    ap.add_argument('--strokes')
    ap.add_argument('--test-strokes')
    ap.add_argument('--inoob')
    ap.add_argument('--words', default=None)
    ap.add_argument('--out', default='hand.pt')
    ap.add_argument('--resume')
    ap.add_argument('--steps', type=int, default=40000)
    ap.add_argument('--batch', type=int, default=24)
    ap.add_argument('--size', type=int, default=384)
    ap.add_argument('--lr', type=float, default=2e-3)
    ap.add_argument('--real', type=float, default=0.3)
    ap.add_argument('--workers', type=int, default=40)
    args = ap.parse_args()
    dev = 'cuda' if torch.cuda.is_available() else 'cpu'
    net = HandNet().to(dev)
    if args.resume:
        net.load_state_dict(torch.load(args.resume, map_location=dev))
    opt = torch.optim.AdamW(net.parameters(), lr=args.lr, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=args.lr, total_steps=args.steps, pct_start=0.05)
    scaler = (torch.amp.GradScaler if hasattr(torch.amp, 'GradScaler') else torch.cuda.amp.GradScaler)(enabled=dev == 'cuda')
    vs = fixed(args, 160, False, 5)
    vr = fixed(args, 100, True, 6)
    data = DataLoader(Pieces(args, args.strokes, args.real, args.size), batch_size=args.batch,
                      num_workers=args.workers, persistent_workers=args.workers > 0,
                      prefetch_factor=4 if args.workers > 0 else None)
    t0 = time.time()
    run = 0
    best = -1
    for step, (x, t) in enumerate(data, 1):
        x = x.to(dev, non_blocking=True).float() / 255
        t = t.to(dev, non_blocking=True)
        if random.random() < 0.5:
            x, t = x.flip(3), t.flip(2)
        with torch.autocast('cuda', dtype=torch.float16, enabled=dev == 'cuda'):
            logit = net(x)
        loss = loss_fn(logit.float(), t)
        opt.zero_grad(set_to_none=True)
        scaler.scale(loss).backward()
        scaler.step(opt)
        scaler.update()
        sched.step()
        run = 0.98 * run + 0.02 * loss.item()
        if step % 500 == 0 or step == args.steps:
            s = score(net, *vs, dev)
            r = score(net, *vr, dev)
            print('step %d loss %.4f  drawn IoU %.3f (P %.3f R %.3f)  real IoU %.3f (P %.3f R %.3f)  %.0fs' %
                  ((step, run) + s + r + (time.time() - t0,)), flush=True)
            torch.save(net.state_dict(), args.out)
            if s[0] + r[0] > best:
                best = s[0] + r[0]
                torch.save(net.state_dict(), args.out[:-3] + '_best.pt')
        if step >= args.steps:
            break


if __name__ == '__main__':
    main()
