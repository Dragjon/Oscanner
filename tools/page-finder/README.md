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

Harder scenes (`hard.py`, 2026-10-22), from photos users sent: pages on patterned cloth,
booklets with the edges of the pages under them showing, curved pages and open spreads.
`python hard.py 4000 trainhard1.npz 301` (and so on) writes them like `gen.py`; `train.py`
picks up every `train*.npz`, and reports on `valhard.npz` too when it's there. The shipped
model was fine-tuned for 4 passes from the one before (`python train.py 4 pagenet.pt 1e-3`) on
the 30,000 drawn frames plus 16,000 hard ones: held-out hard scenes right (IoU ≥ 0.9) went
from 88% to 99.5%, the ordinary ones stayed at 99.9%.

Card finder (`card.onnx`, 2026-10-22): the same network, trained to mark ID cards for ID card
mode, from a photo a user sent of a white library card on a pale patterned sheet. Such a card is
no brighter than what it lies on; only the thin dark line of its edge shows, too thin for the
network to see at 96×128, but it learns where a card can be, and the detector takes the card's
edges from the frame itself, keeping only outlines the network covers. `cards.py` draws the
scenes (cards with bands, photos and barcodes, often as bright as the cloth, desk or paper under
them, edge lines, thumbs, and frames with no card).
`for i in 1 2 3 4 5 6; do python cards.py 2700 traincard$i.npz 40$i; done`,
`python cards.py 1000 valcard.npz 499`, then in that folder
`python train.py 5 <pagenet.pt> 2e-3 cardnet.pt` and `python train.py 4 cardnet.pt 1e-3 cardnet2.pt`
(held-out IoU mean 0.954, 89% ≥ 0.9). On a live camera that one still marked a TV screen and a
bookcase as cards, so `cards.py` now draws decoys too (screens showing patterns, shelves, framed
boxes, slabs of wood or cloth, never in the mask): `python cards.py 2000 traindecoy$i.npz 50$i`
for i in 1..6 and `python cards.py 800 valdecoy.npz 599`, plus `trainroom.npz`, 2,100 card-free
crops of real emulator camera frames; then `python train.py 3 cardnet2.pt 1e-3 cardnet3.pt`
(cards 0.956, decoy scenes 0.927) and `python export.py cardnet3.pt card.onnx`.

Real cards (2026-10-23): on photos of real cards the drawn-only finder was lost (mean IoU 0.19
on MIDV-2020 photos, 0.36 on frames from a user's video), so it now also learns from
MIDV-2020 (Bulatov et al., "MIDV-2020: A Comprehensive Benchmark Dataset for Identity
Document Analysis", 2022; CC BY-SA 2.5), photos and video frames of mock ID documents with their corners marked; only the
ID-1 card types are used. The archives are large, so
`curl <url> | python midvstream.py <out dir> [every]` streams one, keeping only the card images
shrunk to 384 px (and every n-th clip frame); then `realcards.py` crops them the way the camera frames a card
(`python realcards.py 30 trainmidvphoto.npz 701 <photo dir>`, `4 trainmidvclip.npz 702 <clips dir>`),
and with `composite` pastes the real cards into `cards.py`'s scenes in place of the drawn ones
(`python realcards.py composite 1800 traincomp$i.npz 71$i <photo dir>` for i in 1..5). The Slovak
ID (`svk_id`) is held out: `HOLD_OUT=svk_id` for the training sets, and
`ONLY=svk_id python realcards.py 5 valmidv.npz 703 <photo dir>` to check on. With the earlier
sets, `python train.py 4 cardnet3.pt 1e-3 cardnet4.pt`: drawn cards 0.952, decoy scenes 0.924,
the held-out real cards 0.891 (from 0.19), the user's frames 0.80. Because it learned from
CC BY-SA data, `card.onnx` is under CC BY-SA 4.0 (see `licenses/THIRD_PARTY.md`).

Real pages (2026-10-27): on real phone photos the drawn-only finder was unsure (held-out real
frames: mean IoU 0.80, 52% ≥ 0.9), worst on a sheet held up by hand against a pale wall or a
white radiator, where it ran the paper into the wall. `realpages.py` crops the Mendeley document
corner photos (CC BY 4.0) the way the camera frames a page, its first 130 held out: those are
the detector benchmark's real photos (`scene_m_<n>.png` is photo (n)). The `.pt` the shipped
model came from was gone, so `train.py` can start from an `.onnx` (its batch norms folded into
the convolutions, kept out of training). With fresh drawn and hard frames so it doesn't forget:
`python realpages.py mendeley 12 trainreal1.npz 801 <dir>`, `HOLD=only python realpages.py
mendeley 6 valreal.npz 899 <dir>`, `python gen.py 7500 train1.npz 101`, `python gen.py 2000
val.npz 200`, `python hard.py 4000 trainhard1.npz 301`, `python hard.py 1000 valhard.npz 399`,
then `python train.py 5 page.onnx 1e-3 real1.pt` (about 10 minutes a pass on a CPU): held-out
real frames 0.90 (74% ≥ 0.9), drawn 0.991, hard 0.982 (from 0.994 and 0.988).
