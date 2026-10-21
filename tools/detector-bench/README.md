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
