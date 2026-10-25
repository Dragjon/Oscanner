# Third-party software and data in Oscanner

Oscanner is licensed under the GNU GPL, version 3 (see `LICENSE`). It includes or uses the following;
the Apache License 2.0 text is in `licenses/Apache-2.0.txt`, the Mozilla Public License 2.0 in
`licenses/MPL-2.0.txt`.

| Component | Use | License |
|---|---|---|
| [OpenCV](https://opencv.org) 4.12 | Page detection, straightening, looks, clean-up | Apache-2.0 |
| [Tesseract](https://github.com/tesseract-ocr/tesseract) 5 via [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android) 4.9 | Reading text | Apache-2.0 |
| [tessdata_fast](https://github.com/tesseract-ocr/tessdata_fast) `eng.traineddata` (bundled), other languages (downloaded on request) | Text recognition models | Apache-2.0 |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) 1.22 | Runs the PP-OCR text models | MIT |
| [PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR) PP-OCRv4 mobile text detection and PP-OCRv5 English recognition, as ONNX ([RapidOCR](https://huggingface.co/SWHL/RapidOCR), [monkt/paddleocr-onnx](https://huggingface.co/monkt/paddleocr-onnx)) (bundled, `core/ocr/src/main/assets/paddle`) | Finding and reading English text | Apache-2.0 |
| Print-or-handwriting sorter (bundled, `core/ocr/src/main/assets/print/sorter.onnx`), trained for Oscanner on handwriting from the [GNHK dataset](https://github.com/GoodNotes/GNHK-dataset) (GoodNotes; Lee, Chung and Lee, ICDAR 2021), handwritten digits from [EMNIST](https://www.nist.gov/itl/products-and-services/emnist-dataset) (Cohen et al. 2017, from NIST Special Database 19), and printed text rendered for training; scripts in `tools/print-sorter` | Telling print from handwriting for clean-up | Model: same as Oscanner; GNHK data CC BY 4.0; EMNIST from NIST data |
| [DocAligner](https://github.com/DocsaidLab/DocAligner) corner model (DocsaidLab; `lcnet100_h_e_bifpn_256_fp32.onnx`, bundled as `core/imaging/src/main/assets/detect/corners.onnx`, unmodified) | Finding a whole page's or card's corners in Document and ID card modes | Apache-2.0 |
| Page finder and card finder (bundled, `core/imaging/src/main/assets/detect/page.onnx`, `card.onnx`), trained for Oscanner on drawn scenes (`tools/page-finder`); the card finder also on real card photos and video frames from MIDV-2020 (Bulatov et al., "MIDV-2020: A Comprehensive Benchmark Dataset for Identity Document Analysis", 2022; mock documents, faces from Generated Photos) | Finding pages and ID cards in the camera | `page.onnx`: same as Oscanner; `card.onnx`: CC BY-SA 4.0, as an adaptation of MIDV-2020 (CC BY-SA 2.5), shipped as a separate data file |
| [SymSpell](https://github.com/wolfgarbe/SymSpell) English word and word-pair frequency lists, built from [Google Books Ngram](https://storage.googleapis.com/books/ngrams/books/datasetsv2.html) counts and [SCOWL](http://wordlist.aspell.net/) (bundled as `words.dat`, `pairs.dat`) | Correcting misread words | MIT (lists); Google Books Ngram data CC BY 3.0; SCOWL permissive |
| `pdf.ttf` "GlyphLessFont" from Tesseract | Invisible text layer in searchable PDFs | Apache-2.0 |
| [ZXing](https://github.com/zxing/zxing) core 3.5 | QR code and barcode reading | Apache-2.0 |
| [bergamot-translator](https://github.com/mozilla/translations) built for WebAssembly, version 4.0, and its JavaScript loader `bergamot-translator.js`, as Firefox ships them (from Firefox Remote Settings `translations-wasm-v2` and the Firefox source; bundled, unmodified, `core/translate/src/main/assets/bergamot`) | Translating text on the phone | MPL-2.0 |
| [Firefox Translations models](https://github.com/mozilla/translations) (Mozilla; downloaded on request from Firefox Remote Settings `translations-models-v2`, not bundled; list written by `tools/translation-models/catalog.py`) | Language pairs for translation | MPL-2.0 |
| [fzstd](https://github.com/101arrowz/fzstd) 0.1.1 (Arjun Barrett; bundled as `core/translate/src/main/assets/bergamot/fzstd.js`) | Unpacking the zstd-compressed engine and models | MIT (`licenses/fzstd-MIT.txt`) |
| [Figtree](https://github.com/erikdkennedy/figtree) | The app's typeface | SIL Open Font License 1.1 (`licenses/Figtree-OFL.txt`) |
| AndroidX (Compose, CameraX, Room, DataStore, WorkManager, Biometric, Navigation, Lifecycle, Fragment, ExifInterface, WebKit) | App framework | Apache-2.0 |
| Kotlin standard library and kotlinx.coroutines | Language runtime | Apache-2.0 |

## Pictures

The welcome tour's old-photo tile (`app/src/main/res/drawable-nodpi/welcome_photo_*.webp`) is made
from "Biloxi Light House, Miss." (Detroit Photographic Co., 1901), public domain, via
[Wikimedia Commons](https://commons.wikimedia.org/wiki/File:Detroit_Photographic_Company_(0473)_-_Biloxi_MS_Lighthouse.jpg)
from the Library of Congress photochrom collection. See `tools/welcome-assets/`.

No Google Play services, Firebase, ML Kit, analytics or crash-reporting SDKs are used.

## For F-Droid

OpenCV, Tesseract4Android and ONNX Runtime are currently consumed as prebuilt AARs (Maven
Central and JitPack). F-Droid builds everything from source, so before submission both need source builds
(F-Droid `srclibs`; ONNX Runtime has a documented Android build), ideally an OpenCV build with only the `core`, `imgproc` and `photo`
modules, which would also shrink the APK considerably.
