# Third-party software and data in Oscanner

Oscanner is licensed under the GNU GPL, version 3 (see `LICENSE`). It includes or uses:

| Component | Use | License |
|---|---|---|
| [OpenCV](https://opencv.org) 4.12 | Page detection, straightening, looks, clean-up | Apache-2.0 |
| [Tesseract](https://github.com/tesseract-ocr/tesseract) 5 via [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android) 4.9 | Reading text | Apache-2.0 |
| [tessdata_fast](https://github.com/tesseract-ocr/tessdata_fast) `eng.traineddata` (bundled), other languages (downloaded on request) | Text recognition models | Apache-2.0 |
| `pdf.ttf` "GlyphLessFont" from Tesseract | Invisible text layer in searchable PDFs | Apache-2.0 |
| [ZXing](https://github.com/zxing/zxing) core 3.5 | QR code and barcode reading | Apache-2.0 |
| [Figtree](https://github.com/erikdkennedy/figtree) | The app's typeface | SIL Open Font License 1.1 (`licenses/Figtree-OFL.txt`) |
| AndroidX (Compose, CameraX, Room, DataStore, WorkManager, Biometric, Navigation, Lifecycle, Fragment, ExifInterface) | App framework | Apache-2.0 |
| Kotlin standard library and kotlinx.coroutines | Language runtime | Apache-2.0 |

No Google Play services, Firebase, ML Kit, analytics or crash-reporting SDKs are used.

## For F-Droid

OpenCV and Tesseract4Android are currently consumed as prebuilt AARs (Maven Central and
JitPack). F-Droid builds everything from source, so before submission both need source builds
(F-Droid `srclibs`), ideally an OpenCV build with only the `core`, `imgproc` and `photo`
modules, which would also shrink the APK considerably.
