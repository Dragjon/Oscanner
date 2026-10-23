# Page detector benchmark

Camera-like frames of white pages on off-white, busy surfaces (terrazzo, granite, wood, marble,
carpet, plain), each with its true outline, for measuring `DocumentDetector`.

1. `python make_scenes.py scenes 60 180 1` writes `scenes/scene_*.png` (640 px, as the camera
   analyses them) and `scenes/truth.txt`. The 60 `photo` scenes are variations of one real photo
   of a page on terrazzo (set `PHOTO` and its outline `PHOTO_QUAD`; any photo works); the rest
   lay one of the `PAGES` images onto a drawn surface, under uneven light, blur, noise and JPEG.
2. From the repository root:
   `DET_DIR=<path>/scenes ./gradlew :core:imaging:testDebugUnitTest --tests '*DetectorHarnessTest*' --rerun`
   writes `scenes/kotlin_results.txt` (the test does nothing without `DET_DIR`).
3. `python score.py scenes` counts, per surface, outlines right (every corner within 2% of the
   frame), wrong, and missed; `python show.py scenes out.png wrong|missed [kind]` draws them.

On 2026-10-21 the detector got 164 of 240 right (38 of the 60 photo scenes); with the page
finder (`../page-finder`) 192 (45), and no new outlines on the older scenes.

Corner network (2026-10-23): in Document and ID card modes the detector first asks DocAligner's
heatmap model (`core/imaging/src/main/assets/detect/corners.onnx`, DocsaidLab, Apache-2.0), which
finds a whole page's or card's corners outright; its outline is kept only if it's page-shaped (or
card-shaped) with all four sides on an edge, and otherwise the search runs as before.
`python ../page-finder/corners.py <corners.onnx> scenes <out>` writes its outlines, and with
`DET_CORNERS=<out>` the harness uses them (add `DET_SEQ=1 DET_NET_EVERY=<k>` to run it as the
camera does, on its own thread: an outline every k frames, from k frames before, held between).
On a user's video of a student card in ID card mode, corners moving over 10% of the card from one
frame to the next fell from 16 times in 226 frames to none (run as the camera runs it too); on ten
hand-marked frames, mean IoU 0.71 to 0.96. On these drawn scenes 207 of 240 right (193 before);
the drawn card scenes 82 and 88 of 120 good (72 and 82); 124 held-out phone photos of documents
(the Mendeley "New Image Dataset for Document Corner Localization", CC BY 4.0) mean IoU 0.66 to
0.71, SmartDoc 2015 Challenge 1's background05 (CC BY 4.0) 0.04 to 0.32; no new outlines on
frames without a page. A frame longer than 4:3 (a tall photo) is padded out to 4:3 first: squeezed
whole into the square input, a card comes out too thin to see.
