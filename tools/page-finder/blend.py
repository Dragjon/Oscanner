"""blend.py <base.onnx> <tuned.pt> <share> <out.onnx>: the corner network with each weight
(1 - share) the base model's and share the tuned one's (cornertrain.py's), as ONNX. A tuned
network blended back this way keeps most of what tuning gained and most of what it forgot.
"""
import sys, torch, onnx
from onnx2torch import convert
old = convert(onnx.load(sys.argv[1])).state_dict()
new = torch.load(sys.argv[2], weights_only=False)
a = float(sys.argv[3])
sd = new.state_dict()
for k, v in sd.items():
    if v.is_floating_point():
        sd[k] = (1 - a) * old[k] + a * v
new.load_state_dict(sd); new.eval()
torch.onnx.export(new, torch.zeros(1, 3, 256, 256), sys.argv[4], input_names=['img'], output_names=['heatmap'], opset_version=17, do_constant_folding=True)
