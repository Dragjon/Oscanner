"""Marks on a page sorted into print and handwriting by the window classifier."""
import sys

import cv2
import numpy as np
import torch

from common import windows
from train import Net


def marks(gray):
    h, w = gray.shape
    side = max(h, w)
    ink = cv2.adaptiveThreshold(gray, 255, cv2.ADAPTIVE_THRESH_MEAN_C, cv2.THRESH_BINARY_INV, max(15, (side // 40) | 1), 15)
    run = int(side * 0.06)
    rules = cv2.morphologyEx(cv2.dilate(ink, np.ones((3, 1), np.uint8)), cv2.MORPH_OPEN, np.ones((1, run), np.uint8))
    rules |= cv2.morphologyEx(cv2.dilate(ink, np.ones((1, 3), np.uint8)), cv2.MORPH_OPEN, np.ones((run, 1), np.uint8))
    rules &= ink
    text = ink & ~cv2.dilate(rules, np.ones((3, 3), np.uint8))
    return text, rules


def runs(text):
    n, lab, st, _ = cv2.connectedComponentsWithStats(text, 8)
    ok = np.zeros(n, bool)
    ok[1:] = (st[1:, 4] >= 6) & (st[1:, 3] >= 4)
    hs = st[ok, 3]
    unit = int(np.median(hs)) if len(hs) else 10
    keep = np.isin(lab, np.nonzero(ok)[0]).astype(np.uint8) * 255
    smear = cv2.dilate(keep, cv2.getStructuringElement(cv2.MORPH_RECT, (int(unit * 1.2) | 1, 1)))
    m, glab = cv2.connectedComponents(smear, connectivity=8)
    group = {}
    for i in np.nonzero(ok)[0]:
        x, y, bw, bh = st[i, :4]
        ys, xs = np.nonzero(lab[y:y + bh, x:x + bw] == i)
        g = glab[y + ys[0], x + xs[0]]
        group.setdefault(g, []).append(i)
    return lab, st, group


GAP = 0.35
SURE_HAND = 0.85


def classify(gray, net):
    text, rules = marks(gray)
    lab, st, group = runs(text)
    score = np.full(st.shape[0], np.nan)
    for g, comps in group.items():
        hs = np.array([st[i, 3] for i in comps])
        med = np.median(hs)
        main = [i for i in comps if st[i, 3] >= 0.3 * med]
        x0 = min(st[i, 0] for i in main); x1 = max(st[i, 0] + st[i, 2] for i in main) - 1
        y0 = min(st[i, 1] for i in main); y1 = max(st[i, 1] + st[i, 3] for i in main) - 1
        h = y1 - y0 + 1
        ws, centers = windows(gray, x0, x1, (y0 + y1) // 2, h)
        if not ws:
            continue
        with torch.no_grad():
            p = torch.sigmoid(net(torch.from_numpy(np.array(ws, np.float32))[:, None])).squeeze(1).numpy()
        centers = np.array(centers)
        half = h * 1.25
        own = {}
        for i in comps:
            cx = st[i, 0] + st[i, 2] / 2
            d = np.abs(centers - cx)
            near = d <= half
            wts = np.where(near, 1 - d / (half + 1), 0)
            own[i] = (p * wts).sum() / wts.sum() if wts.sum() > 0 else p[np.argmin(d)]
        # Words: marks closer than a letter gap; each word takes its marks' verdict together.
        order = sorted(comps, key=lambda i: st[i, 0])
        word = [order[0]]
        right = st[order[0], 0] + st[order[0], 2]
        def close(ws):
            a = np.array([st[i, 4] for i in ws], float)
            v = (np.array([own[i] for i in ws]) * a).sum() / a.sum()
            for i in ws:
                # A mark it's very sure is handwriting stays so, even touching a printed word.
                score[i] = own[i] if own[i] > SURE_HAND else v
        for i in order[1:]:
            if st[i, 0] - right > GAP * h:
                close(word)
                word = []
            word.append(i)
            right = max(right, st[i, 0] + st[i, 2])
        close(word)
    return lab, st, score, rules


if __name__ == '__main__':
    net = Net()
    net.load_state_dict(torch.load(sys.argv[1]))
    net.eval()
    for path in sys.argv[2:]:
        img = cv2.imread(path)
        gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        lab, st, score, rules = classify(gray, net)
        out = img.copy()
        hand = np.zeros(gray.shape, np.uint8)
        for i in range(1, st.shape[0]):
            if np.isnan(score[i]):
                continue
            x, y, w, h = st[i, :4]
            color = (0, 0, 255) if score[i] > 0.5 else (0, 170, 0)
            cv2.rectangle(out, (x, y), (x + w, y + h), color, 1)
            if score[i] > 0.5:
                hand[lab == i] = 255
        name = path.replace('\\', '/').rsplit('/', 1)[-1].rsplit('.', 1)[0]
        cv2.imwrite('cls_' + name + '.png', out)
        print(name, 'done')
