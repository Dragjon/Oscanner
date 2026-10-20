# Oscanner

A private document scanner for Android. Paper to clean, shareable PDFs in a few taps, with
everything happening on the phone: no account, no ads, no tracking, no watermark of its own.

Licensed under the GNU GPL v3. Third-party components are listed in
[`licenses/THIRD_PARTY.md`](licenses/THIRD_PARTY.md). Product goals live in
[`PRODUCT.md`](PRODUCT.md) and the full plan, including the clean-room rules every contributor
follows, in [`PLAN.md`](PLAN.md).

## What it does

- **Scan**: live page detection with auto-capture, batch scanning, crop with a magnifier, and
  the looks Original, Auto, Bright, Gray, Ink and Board, with shadow removal. Modes for ID cards
  (printed at true size), books (split at the spine), whiteboards, and QR codes or barcodes.
- **Text**: on-device OCR (Tesseract), full-text search, searchable PDFs, copy, and export to
  plain text or Word.
- **Documents**: folders, Trash, merge, reorder/rotate/copy out pages, sign and draw, erase,
  remove pen marks or fingers, password-protected PDFs (AES-256), page and file size options,
  watermark, App lock.
- **Everywhere**: share sheet in and out, save to any file provider, print, a Scan shortcut,
  Quick Settings tile and widget.

## Building

Requirements: Android Studio (or its bundled JDK 17+) and the Android SDK with platform 36.

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # unit tests
./gradlew assembleRelease      # per-ABI release APKs (unsigned)
```

Release builds are split per CPU type; the arm64 APK is about 18 MB.

## Project layout

```
app/              navigation, theme host, app lock, shortcut/tile/widget
core/model        plain data types (documents, pages, edit recipes)
core/data         Room database, page files, rendering queue, OCR jobs, export
core/imaging      OpenCV: detection, straightening, looks, clean-up, book split
core/ocr          Tesseract wrapper and language downloads
core/pdf          dependency-free PDF writer (text layer, AES-256) and DOCX writer
core/design       Soft Card, Night design system: theme, type, components
feature/*         library, capture, review, document, text, settings
```

Pages are never destroyed: each page keeps its original photo plus an edit recipe (crop,
look, sliders, marks, clean-up), and what you see is a render that can always be rebuilt.

## Not done yet

- Model-based clean-up (LaMa inpainting, a handwriting classifier) and a learned page detector
- Translation (Bergamot), pending a licence check of the language models
- App-level encryption at rest (Android's file-based encryption protects data meanwhile)
- Source builds of OpenCV and Tesseract for F-Droid; a baseline profile for faster cold start
- A freedom-to-operate review of the areas flagged ⚠ in `PLAN.md` before any public release
