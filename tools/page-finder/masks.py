"""Runs the page finder on every scene_*.png of a folder and writes its masks (0..255, 48x64)
to <out>/<scene>.png, the way PageNet.kt would on the phone. masks.py <model.pt|.onnx> <scenes> <out>"""
import glob
import os
import sys

import cv2
import numpy as np

model, scenes, out = sys.argv[1:4]
os.makedirs(out, exist_ok=True)
if model.endswith('.onnx'):
    import onnxruntime as ort
    sess = ort.InferenceSession(model)
    run = lambda x: sess.run(None, {sess.get_inputs()[0].name: x})[0]
else:
    import torch
    from train import PageNet
    net = PageNet()
    net.load_state_dict(torch.load(model))
    net.eval()
    run = lambda x: net(torch.from_numpy(x)).detach().numpy()
for f in sorted(glob.glob(os.path.join(scenes, 'scene_*.png'))):
    g = cv2.imread(f, cv2.IMREAD_GRAYSCALE)
    x = cv2.resize(g, (96, 128), interpolation=cv2.INTER_AREA).astype(np.float32)[None, None] / 255
    logit = run(x)[0, 0]
    p = (255 / (1 + np.exp(-logit))).astype(np.uint8)
    cv2.imwrite(os.path.join(out, os.path.basename(f)), p)
print('done')
