"""Trains the print (0) / handwriting (1) window classifier."""
import sys

import numpy as np
import torch
import torch.nn as nn

torch.manual_seed(0)
np.random.seed(0)


class Net(nn.Module):
    def __init__(self):
        super().__init__()

        def block(i, o):
            return nn.Sequential(nn.Conv2d(i, o, 3, padding=1), nn.BatchNorm2d(o), nn.ReLU())

        # Light enough for a phone: about 1.5M multiply-adds a window.
        self.f = nn.Sequential(block(1, 12), nn.MaxPool2d(2), block(12, 24), nn.MaxPool2d(2), block(24, 32), block(32, 32), nn.MaxPool2d(2),
                               block(32, 48), block(48, 48))
        self.head = nn.Linear(48, 1)

    def forward(self, x):
        x = self.f(x)
        return self.head(x.mean(dim=(2, 3)))


def augment(x):
    """Ruled lines through either kind, contrast changes, and a little shift."""
    x = x.copy()
    n = len(x)
    for i in np.nonzero(np.random.rand(n) < 0.35)[0]:
        y = np.random.randint(4, 30)
        t = np.random.choice([1, 1, 2])
        x[i, y:y + t, :] = np.maximum(x[i, y:y + t, :], np.random.uniform(0.5, 1.0))
    gain = np.random.uniform(0.6, 1.1, (n, 1, 1)).astype(np.float32)
    x = np.clip(x * gain, 0, 1)
    shift = np.random.randint(-4, 5)
    x = np.roll(x, shift, axis=2)
    return x


def load(paths):
    return np.concatenate([np.load(p).astype(np.float32) for p in paths])


def main():
    hand = load(['gtrain_hand.npy', 'dtrain.npy'])
    prin = load(['ptrain.npy', 'gtrain_print.npy', 'pnum_train.npy'])
    X = np.concatenate([prin, hand])
    y = np.concatenate([np.zeros(len(prin)), np.ones(len(hand))]).astype(np.float32)
    w = np.where(y == 1, len(prin) / len(hand), 1.0).astype(np.float32)
    print('train', len(prin), 'print', len(hand), 'hand')
    tests = {'gnhk hand': (load(['gtest_hand.npy']), 1), 'gnhk print': (load(['gtest_print.npy']), 0), 'font print': (load(['ptest.npy']), 0), 'hand numbers': (load(['dtest.npy']), 1), 'printed numbers': (load(['pdigits_test.npy']), 0)}
    net = Net()
    if len(sys.argv) > 2:
        net.load_state_dict(torch.load(sys.argv[2]))
    print(sum(p.numel() for p in net.parameters()), 'params')
    peak = float(sys.argv[3]) if len(sys.argv) > 3 else 3e-3
    opt = torch.optim.AdamW(net.parameters(), peak / 1.5, weight_decay=1e-4)
    epochs = int(sys.argv[1]) if len(sys.argv) > 1 else 8
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, peak, total_steps=epochs * (len(X) // 256 + 1))
    lossf = nn.BCEWithLogitsLoss(reduction='none')
    for ep in range(epochs):
        net.train()
        order = np.random.permutation(len(X))
        tot = 0
        for b in range(0, len(X), 256):
            idx = order[b:b + 256]
            xb = torch.from_numpy(augment(X[idx]))[:, None]
            logit = net(xb).squeeze(1)
            loss = (lossf(logit, torch.from_numpy(y[idx])) * torch.from_numpy(w[idx])).mean()
            opt.zero_grad()
            loss.backward()
            opt.step()
            sched.step()
            tot += loss.item() * len(idx)
        net.eval()
        msg = []
        with torch.no_grad():
            for name, (xt, label) in tests.items():
                p = torch.cat([torch.sigmoid(net(torch.from_numpy(xt[i:i + 1024])[:, None])).squeeze(1) for i in range(0, len(xt), 1024)]).numpy()
                acc = ((p > 0.5) == label).mean()
                msg.append(f'{name} {acc:.3f}')
        print(f'epoch {ep} loss {tot / len(X):.4f} | ' + ' | '.join(msg), flush=True)
    torch.save(net.state_dict(), sys.argv[4] if len(sys.argv) > 4 else 'sorter.pt')


if __name__ == '__main__':
    main()
