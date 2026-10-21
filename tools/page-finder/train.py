"""Trains the page finder: a small U-Net from a 96x128 gray frame to the page's mask.

python train.py <epochs> [init.pt] [peak lr] [out.pt]
"""
import glob
import sys

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

torch.manual_seed(0)
np.random.seed(0)


def block(i, o):
    return nn.Sequential(nn.Conv2d(i, o, 3, padding=1), nn.BatchNorm2d(o), nn.ReLU(), nn.Conv2d(o, o, 3, padding=1), nn.BatchNorm2d(o), nn.ReLU())


class PageNet(nn.Module):
    """96x128 frame in, 48x64 page mask (logits) out: a stride-2 stem, then a small U-Net."""

    def __init__(self):
        super().__init__()
        c = [16, 24, 32, 48, 64]
        self.stem = nn.Sequential(nn.Conv2d(1, c[0], 3, stride=2, padding=1), nn.BatchNorm2d(c[0]), nn.ReLU())
        self.e0 = block(c[0], c[0])
        self.e1 = block(c[0], c[1])
        self.e2 = block(c[1], c[2])
        self.e3 = block(c[2], c[3])
        self.mid = block(c[3], c[4])
        self.d3 = block(c[4] + c[3], c[3])
        self.d2 = block(c[3] + c[2], c[2])
        self.d1 = block(c[2] + c[1], c[1])
        self.d0 = block(c[1] + c[0], c[0])
        self.out = nn.Conv2d(c[0], 1, 1)

    def forward(self, x):
        e0 = self.e0(self.stem(x))
        e1 = self.e1(F.max_pool2d(e0, 2))
        e2 = self.e2(F.max_pool2d(e1, 2))
        e3 = self.e3(F.max_pool2d(e2, 2))
        m = self.mid(F.max_pool2d(e3, 2))
        up = lambda t, s: F.interpolate(t, size=s.shape[2:], mode='bilinear', align_corners=False)
        d3 = self.d3(torch.cat([up(m, e3), e3], 1))
        d2 = self.d2(torch.cat([up(d3, e2), e2], 1))
        d1 = self.d1(torch.cat([up(d2, e1), e1], 1))
        d0 = self.d0(torch.cat([up(d1, e0), e0], 1))
        return self.out(d0)


def load(paths):
    X, Y = [], []
    for p in paths:
        d = np.load(p)
        X.append(d['X'])
        Y.append(d['Y'])
    return np.concatenate(X), np.concatenate(Y)


def tensors(X, Y):
    y = torch.from_numpy((Y > 127).astype(np.float32))[:, None]
    # Masks at the network's output size, 48x64.
    y = (F.avg_pool2d(y, 2) > 0.5).float()
    return torch.from_numpy(X.astype(np.float32) / 255)[:, None], y


def iou(logits, y):
    p = (logits > 0).float()
    inter = (p * y).sum((1, 2, 3))
    union = ((p + y) > 0).float().sum((1, 2, 3))
    return torch.where(union > 0, inter / union.clamp(min=1), torch.ones_like(union))


def main():
    epochs = int(sys.argv[1]) if len(sys.argv) > 1 else 8
    X, Y = load(sorted(glob.glob('train*.npz')))
    Xv, Yv = load(['val.npz'])
    print('train', len(X), 'val', len(Xv), flush=True)
    xv, yv = tensors(Xv, Yv)
    net = PageNet()
    if len(sys.argv) > 2 and sys.argv[2] != '-':
        net.load_state_dict(torch.load(sys.argv[2]))
    print(sum(p.numel() for p in net.parameters()), 'params', flush=True)
    peak = float(sys.argv[3]) if len(sys.argv) > 3 else 3e-3
    out = sys.argv[4] if len(sys.argv) > 4 else 'pagenet.pt'
    opt = torch.optim.AdamW(net.parameters(), peak / 2, weight_decay=1e-4)
    bs = 64
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, peak, total_steps=epochs * (len(X) // bs + 1))
    for ep in range(epochs):
        net.train()
        order = np.random.permutation(len(X))
        tot = 0
        for b in range(0, len(X), bs):
            idx = order[b:b + bs]
            x, y = tensors(X[idx], Y[idx])
            # Brightness and contrast vary with every phone and room.
            g = torch.empty(len(idx), 1, 1, 1).uniform_(0.7, 1.3)
            o = torch.empty(len(idx), 1, 1, 1).uniform_(-0.15, 0.15)
            x = ((x - 0.5) * g + 0.5 + o).clamp(0, 1)
            if np.random.rand() < 0.5:
                x, y = x.flip(3), y.flip(3)
            logit = net(x)
            pos = y.mean().clamp(0.05, 0.95)
            loss = F.binary_cross_entropy_with_logits(logit, y) + (1 - (2 * (torch.sigmoid(logit) * y).sum() + 1) / (torch.sigmoid(logit).sum() + y.sum() + 1))
            opt.zero_grad()
            loss.backward()
            opt.step()
            sched.step()
            tot += loss.item() * len(idx)
        net.eval()
        with torch.no_grad():
            ious = torch.cat([iou(net(xv[i:i + 256]), yv[i:i + 256]) for i in range(0, len(xv), 256)])
        print(f'epoch {ep} loss {tot / len(X):.4f} | val IoU mean {ious.mean():.3f} | >=0.9 {(ious >= 0.9).float().mean():.3f}', flush=True)
        torch.save(net.state_dict(), out)


if __name__ == '__main__':
    main()
