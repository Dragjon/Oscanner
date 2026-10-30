"""The handwriting mask network: a small U-Net, RGB in (0..1), one logit per pixel out."""
import torch
import torch.nn as nn
import torch.nn.functional as F


def block(cin, cout):
    return nn.Sequential(
        nn.Conv2d(cin, cout, 3, padding=1, bias=False), nn.BatchNorm2d(cout), nn.ReLU(inplace=True),
        nn.Conv2d(cout, cout, 3, padding=1, bias=False), nn.BatchNorm2d(cout), nn.ReLU(inplace=True))


class HandNet(nn.Module):
    def __init__(self, widths=(12, 24, 48, 80, 128)):
        super().__init__()
        w = widths
        # Full size: one cheap layer, so thin pencil lines aren't lost before anything sees them.
        self.stem = nn.Sequential(nn.Conv2d(3, w[0], 3, padding=1, bias=False), nn.BatchNorm2d(w[0]), nn.ReLU(inplace=True))
        self.down = nn.ModuleList()
        self.enc = nn.ModuleList()
        for i in range(1, len(w)):
            self.down.append(nn.Sequential(nn.Conv2d(w[i - 1], w[i], 3, stride=2, padding=1, bias=False),
                                           nn.BatchNorm2d(w[i]), nn.ReLU(inplace=True)))
            self.enc.append(block(w[i], w[i]))
        self.dec = nn.ModuleList()
        for i in range(len(w) - 1, 1, -1):
            self.dec.append(block(w[i] + w[i - 1], w[i - 1]))
        # Back at full size, one layer again.
        self.dec.append(nn.Sequential(nn.Conv2d(w[1] + w[0], w[0], 3, padding=1, bias=False), nn.BatchNorm2d(w[0]),
                                      nn.ReLU(inplace=True)))
        self.head = nn.Conv2d(w[0], 1, 1)

    def forward(self, x):
        skips = [self.stem(x)]
        y = skips[0]
        for d, e in zip(self.down, self.enc):
            y = e(d(y))
            skips.append(y)
        y = skips.pop()
        for dec in self.dec:
            s = skips.pop()
            y = F.interpolate(y, size=s.shape[-2:], mode='bilinear', align_corners=False)
            y = dec(torch.cat([y, s], 1))
        return self.head(y)


if __name__ == '__main__':
    n = HandNet()
    print(sum(p.numel() for p in n.parameters()), 'parameters')
    from torch.utils.flop_counter import FlopCounterMode
    with FlopCounterMode(display=False) as fc:
        n(torch.zeros(1, 3, 1024, 1024))
    print(fc.get_total_flops() / 1e9 / 2, 'GMAC per megapixel')
