# Handwriting mask

Trains `core/imaging/src/main/assets/clean/hand.onnx`, the network clean-up uses to find
handwriting pixel by pixel (`HandMask.kt`). It sees a page in color at about 2200 px long and
gives each pixel a chance of being handwriting; where pen crosses print, those pixels count as
print, so the print stays whole.

What it learns from:

1. **Drawn pages** (`gen.py`): exam-like page fragments drawn in layers (text in the system's
   document typefaces, dotted and solid answer lines, ruled paper, tables, graphs, diagrams,
   cartoons, logos, halftone pictures), with handwriting over them in pen or pencil, then
   roughed up like a phone photo or a scanning app's cleaned page. Every pixel's answer is
   known. The handwriting is real: lines of it cut from GNHK
   (https://github.com/GoodNotes/GNHK-dataset, CC BY 4.0) by `make_strokes.py`, plus drawn
   rings, ticks, underlines, crossings-out and arrows.
2. **Real exam photos** with their handwriting marked: Inoob/HandwritingSegmentationDataset on
   Hugging Face (listed as MIT; its images look to come from Baidu's handwriting-erasure
   competition, `dehw_train_*`, mostly Chinese papers).

Steps:

1. `python make_strokes.py <GNHK train_data dir> strokes_train` and the same for `test_data`
   into `strokes_test`.
2. Download Inoob's `train.zip` and `val.zip` and unzip them into `inoob/train` and `inoob/val`
   (each with `im/` and `gt/`).
3. Copy the document typefaces into `fonts/` (see `../print-sorter/make_print.py` for the list),
   and `core/ocr/src/main/assets/paddle/words.dat` beside them.
4. `python train.py --fonts fonts --strokes strokes_train --test-strokes strokes_test --inoob inoob
   --words words.dat --out hand.pt --steps 40000`. On an RTX 3060 with 64 workers drawing pages,
   about two hours. It prints handwriting IoU on held-out drawn pages (GNHK test writers) and on
   Inoob's validation photos every 500 steps.
5. `python export.py hand.pt hand.onnx`.

`python predict.py hand.pt <out dir> <pages...>` draws what it finds on whole pages (red) and the
page with it taken out, for checking by eye. `python gen.py fonts strokes_test sheet.png
words.dat` draws a sheet of training fragments with their answers.
