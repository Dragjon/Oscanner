"""export.py <model.pt> <out.onnx>: PageNet to ONNX, input 'gray' 1x1x128x96, output logits 1x1x64x48."""
import sys
import torch
from train import PageNet
net = PageNet()
net.load_state_dict(torch.load(sys.argv[1]))
net.eval()
torch.onnx.export(net, torch.zeros(1, 1, 128, 96), sys.argv[2], input_names=['gray'], output_names=['page'], opset_version=17, do_constant_folding=True)
print('ok')
