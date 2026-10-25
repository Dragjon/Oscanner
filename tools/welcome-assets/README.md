# Welcome tour pictures

The before and after pictures in `app/src/main/res/drawable-nodpi/welcome_*.webp`. Each "after" is
Oscanner's own output from the "before"; nothing is drawn or retouched by hand.

1. `python scenes.py <out>` writes `receipt.jpg` and `worksheet.jpg`; `python scenes2.py <print.jpg> <out>`
   writes `oldprint.jpg` and `bookpage.jpg`. `<print.jpg>` is "Biloxi Light House, Miss." (Detroit
   Photographic Co., 1901), public domain:
   https://commons.wikimedia.org/wiki/File:Detroit_Photographic_Company_(0473)_-_Biloxi_MS_Lighthouse.jpg
2. The receipt, book and print afters: copy `WelcomeAssetsTest.kt.txt` into
   `core/imaging/src/test/kotlin/dev/olaf/oscanner/core/imaging/` as `WelcomeAssetsTest.kt` and run it with
   `WELCOME_SCENES=<out>` (it does nothing without it). The worksheet's after comes from the app's
   Clean up → Handwriting, and the translation pair from Text → Translate (English to French) on a
   page set in Times, both pulled from the app on an emulator.
3. `python tiles.py <out> app/src/main/res/drawable-nodpi` frames each pair for its tile.

`provenance/` holds each picture's origin note.
