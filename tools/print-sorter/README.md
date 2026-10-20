# Print-or-handwriting sorter

Trains `core/ocr/src/main/assets/print/sorter.onnx`, the small network clean-up uses to tell
printed marks from handwritten ones. `common.py` defines what it looks at (windows across a run
of text, 32×64, paper 0 and ink 1); `PrintSorter.kt` does the same on the phone.

1. Handwriting (and some printed words photographed in the wild) from GNHK
   (https://github.com/GoodNotes/GNHK-dataset, CC BY 4.0): unzip its `train_data` and
   `test_data`, then
   `python make_gnhk.py <train dir> gtrain 1` and `python make_gnhk.py <test dir> gtest 2`.
2. Printed text rendered in the system's document typefaces and roughed up like a phone scan:
   `python make_print.py 9000 ptrain.npy 11` and `python make_print.py 1500 ptest.npy 12`.
3. `python train.py 6` (about 25 minutes on a laptop CPU) writes `sorter.pt`; it prints accuracy
   on GNHK's test images and on held-out rendered print after every pass.
4. Export: `torch.onnx.export(net, torch.zeros(2, 1, 32, 64), 'sorter.onnx', input_names=['x'],
   output_names=['logit'], dynamic_axes={'x': {0: 'n'}, 'logit': {0: 'n'}}, opset_version=13)`.

`python page.py sorter.pt page.png` draws each mark's verdict on a page (green print, red
handwriting), for checking on pages it wasn't trained on.
