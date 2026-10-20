"""Scores kotlin_results.txt against truth.txt: found, right (every corner within 2%), wrong."""
import math
import sys
from collections import defaultdict

d = sys.argv[1]
truth = {l.split()[0]: [tuple(map(float, c.split(','))) for c in l.split()[1].split(';')] for l in open(d + '/truth.txt') if l.strip()}
res = {}
for l in open(d + '/kotlin_results.txt'):
    n, ms, q = l.split(' ', 2)
    q = q.strip()
    res[n] = None if q == 'none' else [tuple(map(float, c.split(','))) for c in q.split(';')]
by = defaultdict(lambda: [0, 0, 0, 0])
bad = []
for n, t in truth.items():
    kind = n.split('_')[1]
    by[kind][0] += 1
    r = res.get(n)
    if r is None:
        by[kind][3] += 1
        bad.append((n, 'none'))
        continue
    err = max(min(math.dist(a, b) for b in r) for a in t)
    if err <= 0.02:
        by[kind][1] += 1
    else:
        by[kind][2] += 1
        bad.append((n, f'off {err:.3f}'))
tot = [sum(v[i] for v in by.values()) for i in range(4)]
for k, v in sorted(by.items()):
    print(f'{k:9s} {v[1]:3d}/{v[0]:3d} right, {v[2]:2d} wrong, {v[3]:2d} missed')
print(f'ALL       {tot[1]:3d}/{tot[0]:3d} right, {tot[2]:2d} wrong, {tot[3]:2d} missed')
if len(sys.argv) > 2:
    for b in bad:
        print(*b)
