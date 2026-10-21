# Page finder

Trains `core/imaging/src/main/assets/detect/page.onnx`, the small network `DocumentDetector`
asks when its own edge search finds no page: on pale, speckled or busy surfaces a white page's
edge can be too faint to follow. It takes the camera frame squeezed to 96×128 in gray (0..1) and
marks the page at 48×64; `PageNet.kt` does the same on the phone. The detector then snaps the
mask's rough outline onto the page's own edge and drops it unless the network was sure all over,
so a light patch or a page running off the frame isn't outlined.

Everything is drawn: surfaces, pages and the camera's light, blur, noise and JPEG come from
`../detector-bench/make_scenes.py` (set `BENCH_DATA` to where its `PAGES` live), plus gradients,
clutter, hands on edges, pages partly out of frame and frames with no page.

1. `for i in 1 2 3 4; do python gen.py 7500 train$i.npz 10$i; done` and
   `python gen.py 2000 val.npz 200` (30,000 training frames, 2,000 held out).
2. `python train.py 8` writes `pagenet.pt` after every pass and prints the held-out mean IoU.
3. `python export.py pagenet.pt page.onnx`.

`python masks.py <pagenet.pt|page.onnx> <scenes> <out>` writes the masks for a folder of benchmark
scenes; with `DET_MASKS=<out>` the detector benchmark uses them in place of the model, so the
JVM test needs no ONNX Runtime.
