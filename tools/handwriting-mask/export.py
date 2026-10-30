"""Writes the trained network as ONNX for the app: python export.py hand.pt hand.onnx"""
import sys

import torch

from net import HandNet

net = HandNet().eval()
net.load_state_dict(torch.load(sys.argv[1], map_location='cpu'))
extra = {'dynamo': False} if 'dynamo' in torch.onnx.export.__code__.co_varnames else {}
torch.onnx.export(net, torch.zeros(1, 3, 512, 512), sys.argv[2], input_names=['rgb'], output_names=['logit'],
                  dynamic_axes={'rgb': {2: 'h', 3: 'w'}, 'logit': {2: 'h', 3: 'w'}}, opset_version=13, **extra)
print('wrote', sys.argv[2])
