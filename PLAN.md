# Oscanner: clean-room plan

A private, on-device Android document scanner that works like CamScanner day to day (capture, auto-crop, enhance, save, share, OCR, AI clean-up), but has no ads, accounts, watermarks or paywall, and has a quiet dark interface.

> Status: planning. No code yet. Product facts live in `PRODUCT.md`. Visual direction chosen: **Soft Card, Night** (§7).

---

## 1. Clean-room rules (read before contributing)

1. **Sources allowed:** public store listings, the vendor's marketing site and blog, published user manuals, press releases, third-party reviews, academic papers, and open-source code under compatible licenses. Every source used is listed in §12.
2. **Sources forbidden:** CamScanner APKs or IPAs, decompiled or disassembled code, network captures of its APIs, its bundled models or assets, and its screenshots as layout references. No contributor installs CamScanner to "see how it does it".
3. **Two roles.** A *spec writer* turns public descriptions into functional requirements (§3, §4) using only what a feature *does*, never how it is built. *Implementers* work only from this document and the public algorithm literature.
4. **No borrowed trade dress or names.** We don't use these names: *CamScanner, CS, Magic Color, Smart Erase, Mega Scan, CountCam, Scrollshot,* or "Scanned by …". Our names are plain descriptive words (§4.3). Our icons, colors and layout come from our own design system (§7).
5. **Record everything.** Design decisions are recorded in git with the date and the public source that motivated them, so independent development can be shown later.

## 2. Patent posture

I am not a lawyer. This section sets engineering defaults; it is **not** a freedom-to-operate (FTO) opinion. **Before any public release, a patent attorney should run an FTO search** on the areas flagged ⚠ below.

| Rule | Why |
|---|---|
| Prefer techniques published **more than 20 years ago** (Canny 1986, Douglas–Peucker 1973, homography/DLT, Otsu 1979, Sauvola 2000, Telea inpainting 2004). | Any patent on them has expired, and their publication is prior art against newer claims. |
| Prefer OSS under **Apache-2.0** (it includes an express patent grant from contributors): OpenCV ≥ 4.5, PdfBox-Android, ZXing, Tesseract, LaMa. | The patent grant reduces risk compared with MIT/BSD-only code. |
| Train our **own models on data we are licensed to use** (synthetic data, CC0/CC-BY, our own captures). Don't use research datasets with non-commercial licenses (e.g. IAM handwriting) for shipped weights. | Protects copyright and licensing as well as patents. |
| Don't reproduce the *specific combination of steps* described in any patent claim we come across. Where a known patent family exists, design around its claimed sequence and record how we did it. | Patent claims are infringed by doing all of the claimed elements together. |
| Flag ⚠ areas for FTO review: live document-boundary detection with auto-capture, page-curvature dewarping, handwriting-versus-print separation, finger removal in book scans, multi-frame stitching. | These areas are active for patent filings by scanner vendors (US families such as *"Image processing to detect a rectangular object"* US 10331966 → 12051258 exist; assignees not verified). |

Concrete design-arounds are noted next to each feature in §5.

---

## 3. Research: what CamScanner offers (public sources only)

### 3.1 Capture
- Auto edge detection, auto-crop and perspective correction; manual corner drag; rotate; reset.
- Single and batch shooting; flash; grid; zoom presets; import from gallery or files.
- Modes: **Document, ID card / passport (with ID templates), Book (two-page), Test paper, Whiteboard, Slides, Image to text, QR code**, plus a large-object stitching mode for blueprints and art (marketed as "Mega Scan").
- Clean-up during capture: shadow and glare removal, creases and stains, bleed-through, screen moiré, finger marks and fingers covering the page, and brightening.

### 3.2 Enhancement filters (from the official manuals; current labels may differ)
Original · Lighten · "Magic Color" (default: white-balanced, vivid, clean background) · Grayscale (small files) · B&W (high-contrast, removes shadows). Older builds also had Auto and a second B&W. Manual brightness, contrast and detail sliders.

### 3.3 AI and "smart" tools
- **Handwriting removal** ("erase handwriting" on test papers: removes pen marks and keeps printed text).
- **Brush erase**: select and remove objects, handwriting or text from a scan.
- **OCR** in about 41 languages: copy, edit, export TXT or Word, and translate (side-by-side original and translation).
- **Ask AI / summary** over a scanned document.
- **Photo translation** that keeps the original layout.
- **Old photo restoration**.
- **Tamper / AI-image / Photoshop detection** (2026).
- Mosaic/pixelate brush and auto-mask text; AI document search.
- Utilities: Measure, object counting, scrolling screenshots.

### 3.4 Convert / PDF tools
PDF ↔ Word, Excel, PPT and JPG; merge, split, reorder, rotate and delete pages; compress; add watermark; password-protect; e-signature; annotate; Markdown view/edit; print; fax (paid per page).

### 3.5 Organize and share
Folders, tags, sort, list or grid view, search by title, tag, note or OCR text, ID folder, collage (paid), notes per document. Share as PDF, JPG, TXT or link (expiring or password links are paid). Cloud sync and third-party upload (Drive, Dropbox, OneDrive, Box, Evernote). Password-lock documents and folders.

### 3.6 Free vs paid (as publicly reported; prices vary by region)
| Free | Premium (~$4.99–$9.99/mo, ~$49.99–$69.99/yr) |
|---|---|
| Basic scan and PDF, filters, limited OCR | Full OCR, conversion to Word/Excel/PPT |
| Watermark on output (removed free for US users since 2022) | No watermark, no ads |
| Ads (video ads on launch, a frequent complaint) | Higher-quality scans, more cloud storage (10 GB vs 2 GB) |
| 7-day trash recovery | 30-day recovery, collage, ID photos, encrypted or expiring links, auto-upload |

### 3.7 What users complain about (useful for us)
Busy UI, intrusive ads, watermark, searchable PDF behind the paywall, inconsistent AI text edits (warped nearby lines), mediocre handwriting OCR, and imperfect straightening of brochures.

---

## 4. Oscanner scope

### 4.1 Positioning
Everything a person needs to scan their own paperwork, all free and all on the device. We match CamScanner on the core loop and the clean-up tools people actually use. We leave out the long tail (fax, collage, tamper detection, measure, counting, cloud accounts).

### 4.2 Feature matrix
P = phase (§9). ✅ = in scope, ➖ = later or optional, ✖ = out of scope.

| Area | Feature | Oscanner | P |
|---|---|---|---|
| Capture | Live edge detection, auto-capture when steady | ✅ | 1 |
| | Manual crop with magnifier loupe, rotate | ✅ | 1 |
| | Batch capture, import from gallery or files | ✅ | 1 |
| | Modes: Document, ID card, Book, Whiteboard | ✅ | 3 |
| | QR / barcode | ✅ | 3 |
| | Large-object stitching | ✖ (⚠, complexity) | – |
| Enhance | Original, Auto, Bright, Gray, Ink (B&W) + sliders | ✅ | 1 |
| | Shadow removal | ✅ | 1 |
| | Glare hint while capturing; moiré reduction | ➖ | 5 |
| Clean-up | Brush eraser (inpaint) | ✅ | 5 |
| | Handwriting removal | ✅ | 5 |
| | Finger removal in Book mode | ➖ | 5 |
| | Stain and bleed-through cleanup | ➖ | 5 |
| Text | OCR, copy, edit, searchable PDF | ✅ (free) | 2 |
| | Translate (on-device, open models) | ➖ | 6 |
| | Ask / summarize | ✖ in the F-Droid build (would add the NonFreeNet anti-feature); may return in a later Play flavor | – |
| | Export TXT, DOCX; tables to XLSX | ✅ / ➖ | 6 |
| PDF | Merge, split, reorder, rotate, delete, compress | ✅ | 4 |
| | Sign (draw, import), annotate, watermark (user's own) | ✅ | 4 |
| | Password (AES-256) | ✅ | 4 |
| Library | Folders, tags, search incl. OCR text, sort, trash (30 days) | ✅ | 1–2 |
| | App lock (biometric) | ✅ | 4 |
| Share | Share sheet, save to any SAF provider, print | ✅ | 1 |
| | Cloud accounts, sync, links, fax | ✖ | – |
| Misc | Old-photo restore, tamper detection, measure, count | ✖ | – |

### 4.3 Our names
Self-explanatory words, not brand terms:

| Theirs | Ours |
|---|---|
| Magic Color | **Auto** |
| Lighten | **Bright** |
| Grayscale | **Gray** |
| B&W | **Ink** (pure black on white) |
| Smart Erase | **Erase** (brush) |
| Erase handwriting | **Remove handwriting** |
| Image to text | **Text** |

---

## 5. Technical design

### 5.1 Stack
Kotlin 2.x, Jetpack Compose + Material 3, minSdk 26, targetSdk latest. CameraX (Preview + ImageAnalysis + ImageCapture). OpenCV 4.x (Apache-2.0) for imaging, called from Kotlin through the official Android SDK; hot loops go into a small C++ JNI module. Room + FTS4 for the library. WorkManager for background OCR. Hilt for DI. Coil for thumbnails. PdfBox-Android (Apache-2.0) for PDF writing, text layers, encryption, merge and split. LiteRT (TensorFlow Lite) or ONNX Runtime Mobile for our own models.

**F-Droid rule for every dependency:** it must be FOSS and buildable from source by F-Droid's build server. That rules out Google Play Services, Firebase, ML Kit, Gemini Nano/AICore, closed analytics or crash reporting, and any proprietary SDK. All the libraries above (Apache-2.0 / MIT / BSD) are compatible with the app's **GPL-3.0** license. CI runs a dependency-license check on every PR.

### 5.2 Modules
```
:app                      navigation, DI, theme host
:core:design              tokens, components (from §7), icons
:core:model               Document, Page, Folder, Tag, Filter, Quad
:core:data                Room DB, file store, repositories, FTS
:core:imaging             detection, warp, filters, clean-up (OpenCV + JNI)
:core:pdf                 PdfBox wrapper: build, text layer, encrypt, merge/split
:core:ml                  model runtime, model registry, download-on-demand
:feature:library          home, folders, search, trash
:feature:capture          camera, modes, auto-capture, batch tray
:feature:review           crop, filters, page strip
:feature:document         page viewer, reorder, tools sheet, export
:feature:text             OCR view, copy, export, translate (later)
:feature:settings
```

### 5.3 Storage model
`files/docs/<docId>/<pageId>/{original.jpg, quad.json, render.jpg, thumb.webp, ocr.json}`. **The original is never overwritten.** Every edit (quad, filter and parameters, erase masks, rotation) is stored as an ordered recipe in `page.edits` (JSON), and `render.jpg` is a cache that can be rebuilt. That gives unlimited undo and lets the user revert any AI result (Product Principle 4). Optional encryption at rest with Tink AEAD, with the key in Android Keystore, when App lock is on.

### 5.4 Imaging pipeline (designed by us from classical literature)

**Document boundary detection (live, about 15 fps at 640 px on the long side)** ⚠
1. Downscale, convert to grayscale, bilateral or median denoise.
2. Run two candidate generators in parallel: (a) Canny with automatic thresholds from the median, then morphological close; (b) a saturation and value channel threshold for low-contrast desks.
3. Find contours, take the convex hull, and approximate with Douglas–Peucker to 4 vertices; also fit lines with probabilistic Hough on the hull edges as a fallback.
4. Score each quad: area ratio, convexity, angle sanity (60–120°), edge-gradient support along the sides, and aspect plausibility against ISO 216, Letter and ID-1.
5. Smooth over time with an exponential moving average per corner, plus hysteresis so the overlay doesn't jitter.
6. **Auto-capture** fires when the quad's IoU stays at 0.95 or higher for about 600 ms and the frame is sharp (variance of the Laplacian above a threshold).
7. **Phase 5 upgrade:** a small segmentation model of our own (MobileNetV3-small encoder with a light decoder, 256², INT8, under 2 MB) trained on **synthetic composites** (rendered public-domain pages warped onto CC0 desk textures under random lighting) plus our own labeled captures. The mask goes to the same quad fitting.
*Design-around note:* we don't use corner detectors with adaptive luminance thresholding in the claimed order of the 2003-era families. Our pipeline fits polygons to contours and then scores candidates.

**Perspective warp.** Compute a homography from the quad to a rectangle. Output size comes from the average opposite edge lengths, adjusted by the estimated focal length when EXIF provides it. Use cubic interpolation, at full resolution, off the main thread.

**Filters** (all deterministic, parameterized and golden-tested):
- **Auto:** estimate the background with a large morphological close (or downscale, median, upscale), divide the image by it to flatten lighting and shadows, apply gray-world white balance on background pixels, apply a gentle S-curve, and add an unsharp mask. Keeps color.
- **Bright:** like Auto, with a lifted midtone gamma and lower saturation.
- **Gray:** luminance, background flattening and contrast stretch. Smallest file size.
- **Ink:** flattened luminance, then **Sauvola** adaptive threshold (window based on estimated text height), then a despeckle (connected components below N px). Optional pen-color preservation.
- **Original:** perspective correction only.
- Sliders: brightness, contrast, detail (unsharp amount). They are applied after the filter and stored in the recipe.

**Shadow removal.** The background-division step above, plus soft blending at shadow boundaries detected from the gradient of the background estimate.

### 5.5 Clean-up tools

**Erase (brush).** The user paints a mask, with an optional *"fill from page"* toggle.
- For flat page background (most cases), fill the mask with the locally estimated background color and add matched noise: cheap and exact.
- For textured or complex regions, use **LaMa** inpainting (Apache-2.0), exported to TFLite at 512², tiled. The model downloads on demand (about 20 MB) so the APK stays small.
- Show a before/after hold-to-compare, plus undo.

**Remove handwriting** ⚠ (two tiers):
1. *Color tier (instant, no ML):* when the ink hue differs from the printed text (blue or red pen on black print), segment in HSV/Lab and build a mask from hue clusters, then fill as with Erase. This covers most test-paper cases.
2. *Model tier:* our own pixel classifier with three classes (background, printed, handwritten): a small U-Net, INT8, about 3 MB, at 512² tiles.
   - Training data is **fully synthetic**: printed layer from public-domain or self-authored worksheets rendered from LaTeX/HTML; handwriting layer from strokes rendered in OFL handwriting fonts, with jitter, pressure and pen-color augmentation, plus our own collected samples from consenting volunteers.
   - The handwritten mask is then dilated and filled with background, or with LaMa where it overlaps print.
   - The user always sees a mask preview to refine with the brush before applying.

**Finger removal (Book mode).** Detect skin-colored blobs that touch the page margin (YCrCb skin model, plus a model later), then fill with the margin background.

**Moiré (later).** Notch filtering in the FFT magnitude spectrum on screen photos.

### 5.6 Book, ID and Whiteboard modes
- **Book** ⚠: detect the spine as a vertical valley in the projected gradient, then split into two pages. For dewarping, fit text lines (connected components grouped into lines), fit a cubic surface model, and remap. Publicly described in open-source page-dewarp work (MIT, 2016). This needs FTO review before shipping; the fallback is a flat split with no dewarp.
- **ID card:** guided two-step capture (front, then back). Both sides are warped to ISO/IEC 7810 ID-1 aspect (85.60 × 53.98 mm) and composed at true scale on an A4 or Letter page for printing.
- **Whiteboard:** stronger background flattening, color saturation boost for markers, and perspective allowed at steeper angles.

### 5.7 Text (OCR, translate, ask)
- **OCR:** **Tesseract 5** through `tesseract4android` (Apache-2.0, native code built from source).
  - The English `tessdata_fast` model is bundled. Other languages download on demand from the official `tessdata` repository (Apache-2.0).
  - Tesseract is weaker than ML Kit on raw photos, so OCR always runs on our cleaned render: Ink binarization, deskew, and upscaling to about 300 DPI equivalent.
  - Later option: PaddleOCR mobile models (Apache-2.0) via ONNX Runtime for CJK and difficult photos.
  - Results are stored as blocks, lines and words with bounding boxes.
- **Searchable PDF:** an invisible text layer (render mode 3) placed at the word boxes using PdfBox. **Free.**
- **Search:** Room FTS4 over title, tags, notes and OCR text.
- **Translate (later, optional):** on-device with Mozilla's Bergamot engine (MPL-2.0) and its translation models, downloaded per language pair. Each model's license must be checked before it is offered. If no suitably licensed models exist for a pair, that pair isn't offered. Shown as a side-by-side view.
- **Ask / Summarize:** not in the F-Droid build. The app makes no network requests except user-initiated downloads of free model and language files.
- **Export DOCX:** generate OOXML ourselves (paragraphs from OCR blocks, with rough font size from box height). **XLSX** from detected tables (grid lines found by morphology) comes later.

### 5.8 PDF and document tools
PdfBox-Android: build (page size Auto/A4/Letter, margins, JPEG quality), merge, split, reorder, rotate, delete, compress (re-encode quality and downscale presets: *Small / Balanced / Best*), AES-256 password, signature (drawn on a canvas, stored as a vector path, placed and scaled on the page), freehand and highlight annotations, and an optional text watermark the user writes. The app adds **no watermark of its own, ever**.

### 5.9 Platform integration
Share sheet in and out (receive images and PDFs), Storage Access Framework save-as, `PrintManager`, a Quick Settings tile ("Scan"), an app shortcut, and a home-screen widget. Predictive Back, edge-to-edge, large-screen layouts (navigation rail and two-pane document view on tablets and foldables).

### 5.10 Distribution: F-Droid first
- **Source:** a public repository (Codeberg or GitHub) under **GPL-3.0**, with a `LICENSE` file and SPDX headers.
- **Metadata:** in the repo using the fastlane layout: `fastlane/metadata/android/en-US/{title,short_description,full_description}.txt`, `images/phoneScreenshots/`, and `changelogs/<versionCode>.txt`. F-Droid reads these directly.
- **Releases:** a git tag per release (`v1.2.0`) with a matching `versionCode`. F-Droid's auto-update watches the tags.
- **Inclusion:** open a merge request to `fdroiddata` on GitLab with the build recipe. Alternatively, file a Request For Packaging issue.
- **Reproducible builds** (aim from v1): pin the Gradle, AGP, NDK and JDK versions, and avoid build timestamps. That way F-Droid can publish **our own signature**, and a later Google Play release can use the same key, so users can switch stores without reinstalling.
- **Anti-features:** target none. No tracking, no non-free network services, and no non-free assets. ML model weights ship only if their license is free; otherwise we train our own (§2).
- **Before F-Droid accepts the app:** signed APKs on Releases, installable directly or through Obtainium.
- **Build flavors:** `foss` is the only flavor now. A `play` flavor can be added later without touching the `foss` code path.

### 5.11 Performance budgets
Cold start under 600 ms to an interactive library. Camera preview within 400 ms of tapping Scan. Detection overlay at 15 fps or more on a mid-range 2022 device. Capture to cropped preview in 700 ms or less. 10-page PDF export in under 3 s. APK base size under 25 MB, with models downloaded on demand.

---

## 6. Information architecture and flows

**Destinations.** Few on purpose:
1. **Library** (home): documents, folders, search.
2. **Capture**: full-screen camera, opened from the single primary action.
3. **Document**: pages of one document, plus its tools.
4. **Settings**, reached from Library's overflow.

There's no bottom navigation bar: one home plus one primary action doesn't need it.

**Primary loop** (target: 3 taps, under 10 s):
`Library → [Scan] → camera auto-captures → [Done] → Review (Auto filter applied, crop looks right) → [Save] → Document → [Share]`

**Screen-by-screen action budget.** At most **3 primary, labeled actions** per screen. Everything else sits in one labeled *More* sheet, grouped by purpose.

| Screen | Primary actions | More sheet |
|---|---|---|
| Library | Scan · Import · Search | New folder, Select, Sort, Settings |
| Capture | Mode (Document/ID/Book/Whiteboard) · Shutter/Auto · Done (n) | Flash, Grid, Import |
| Review | Crop · Filter · Save | Rotate, Retake, Delete page |
| Document | Share · Edit · Text | Rename, Move, Add pages, Reorder, Sign, Erase, Remove handwriting, Password, Compress, Print, Delete |
| Text | Copy · Export · Translate | Language |

**Self-documenting rules**
- Every button shows **an icon and a word**. Only universal glyphs (back, close) appear without a label, and they still have TalkBack labels.
- Use verbs for actions (*Share, Erase, Save*) and nouns for places (*Library, Settings*).
- Filters show **live thumbnails of the actual page**, so the effect is obvious without reading the name.
- AI tools show a **mask or diff preview with Apply / Cancel**. There are no "magic" sparkle icons.
- Use *Undo* snackbars instead of confirmation dialogs (except permanent delete from Trash).
- Empty states teach by doing: an empty Library shows one sentence and the Scan button, nothing else.
- Settings use plain sentences ("Save originals in full resolution"), never jargon toggles.
- No onboarding carousel. Camera permission is requested in context with one line explaining why.

---

## 7. Visual design: "Soft Card, Night" (chosen 2026-10-20)

**Thesis.** Friendly cards in plain words on a warm charcoal ground. **The only saturated color on screen is always something you can press.** This rule is what makes the UI self-documenting: if it's orange, it's an action; if it isn't, it's content or status.

It's a warm consumer-app surface translated to night, and it deliberately avoids the category default of a cold grey Material grid with a blue FAB. Dark is the default. Light is the same system on its original warm ground.

### 7.1 Color tokens (Material 3 roles)

| Role | Dark (default) | Light | Use |
|---|---|---|---|
| `background` (ground) | `#161412` | `#FFF4E6` | Screen ground, warm and never pure black |
| `surface` (card) | `#221F1C` | `#FFFFFF` | Cards, dock, sheets |
| `surfaceContainerHigh` | `#2C2824` | `#F7EBDD` | Pressed cards, input fields, thumbnail wells |
| `outlineVariant` | `#3A352F` | `#E7DACB` | 1 dp dividers inside cards (rare) |
| `onSurface` | `#F4EEE6` | `#2B241F` | Primary text |
| `onSurfaceVariant` | `#B3A99D` | `#6F625A` | Secondary text, idle icons |
| `primary` (action) | `#FF6A3D` | `#F25A2C` | **Only** on actionable elements: primary buttons, active chip, shutter ring, links |
| `onPrimary` | `#1E0E07` | `#1E0E07` | Text and icons on orange. Dark ink, not white, because white on this orange fails AA (≈2.9:1) while dark ink passes (≈7:1) |
| `primaryContainer` | `#3B231A` | `#FFE3D6` | Selected-but-not-primary states (a selected filter card's ground) |
| `error` | `#FF9C9C` + icon | `#B3261E` + icon | Always paired with an ⚠ glyph and an outline treatment. **Never a filled button**, so it can't be mistaken for an action |
| `success` | `#9AD3A6` | `#2E7D4F` | "Saved", "Text ready" states |
| `scrim/overlay` | `#000000` at 55% | same | Camera chrome backdrop, sheet scrim |
| Detection quad | `#F4EEE6` 2 dp + 10 dp corner dots | same | Paper-white, **not orange**, because the quad is status rather than an action |
| AI mask preview | `#F4EEE6` 35% diagonal hatch | `#2B241F` 30% hatch | Shows what will be erased |

Contrast checks to keep in CI: `onSurface`/`surface` ≥ 12:1, `onSurfaceVariant`/`surface` ≥ 6:1, `onPrimary`/`primary` ≥ 7:1. Dynamic color is off by default; when the user enables it, only `primary` is re-derived and it must still pass the `onPrimary` check.

### 7.2 Type
One family: **Figtree** (OFL, bundled), a friendly sans with open counters that stays legible at small sizes. Fallback is Roboto. All sizes are in sp and mapped to Material roles:

| Role | Size / line / weight | Used for |
|---|---|---|
| headlineMedium | 28/34 Semibold | Screen title ("Documents") |
| titleLarge | 20/26 Semibold | Card titles, sheet titles |
| titleMedium | 16/22 Semibold | Document names |
| bodyLarge | 16/24 Regular | Sentences, settings, empty states |
| bodyMedium | 14/20 Regular | Metadata ("3 pages · 12 Oct") |
| labelLarge | 15/20 Semibold | Button labels |
| labelMedium | 12/16 Medium | Chip and dock labels under icons |

No all-caps, no tracking tricks, no italic display. Labels are sentence case and written as a person would say them.

### 7.3 Shape, space, elevation
- Radii: cards **24 dp**, dock **28 dp**, buttons fully rounded (pill), chips **14 dp**, thumbnails **12 dp**, bottom sheets **28 dp** top corners.
- Spacing on a 4 dp grid. Card padding 20 dp, gap between cards 12 dp, screen gutter 16 dp.
- Elevation is **tonal only** (ground → card → container). No drop shadows in dark mode; light mode uses one soft 1 dp ambient shadow on the dock only.
- Iconography: Material Symbols Rounded, weight 400, grade 0, 24 dp, with an icon plus a word always.

### 7.4 Motion
- Press: a spring scale to 0.97 (damping 0.8, medium stiffness) with a 40 ms color settle. **No bounce on entry.**
- Navigation: Material shared-axis X between Library and Document, and a container transform from a document card into the Document screen.
- Capture: the shutter ring fills clockwise during the auto-capture countdown (600 ms), giving one haptic tick and a white flash at 30% for 80 ms.
- Processing: a row's state chip crossfades in place (Enhancing → Reading text → Text ready) and the list never reflows.
- With the system "Remove animations" setting, everything becomes a crossfade or an instant cut.

### 7.5 Key screens

**Library (home)**
- Top: the title "Documents" and a search field card ("Search titles and text"). Below it, a horizontal row of folder chips: *All · Receipts · School · + New folder*.
- A single column of **document cards**. Each card has the same fixed layout:
  - a 64×84 dp page thumbnail in a 12 dp-radius well;
  - the name (titleMedium);
  - metadata: "3 pages · 12 Oct · 1.2 MB";
  - a trailing status chip only while something is happening.
- Bottom **dock** (a floating card, thumb height): `Import` (icon + label, neutral) · **`Scan`** (orange pill, the largest element) · `Select`.
- Empty state: one sentence ("Scan your first page. It stays on this phone.") above the dock. Nothing else.

**Capture**
- The camera fills the screen, with the paper-white detection quad on top.
- Top: a status pill on a scrim ("Looking for a page…" → "Hold steady" → "Captured"), plus flash and close buttons.
- Bottom dock on a scrim:
  - a mode chip row (*Document · ID card · Book · Whiteboard*), with the active chip in orange;
  - then three controls: the batch stack thumbnail with a count badge ("3"), the **shutter** (orange ring, white core), and `Done`.
- An `Auto` / `Manual` toggle chip sits by the shutter and is labeled in words.

**Review**
- The page, large, on the ground.
- Below it, a **filter strip**: cards with *live thumbnails of this page* in Original · Auto · Bright · Gray · Ink. The selected card is outlined in orange.
- Dock: `Crop` · `Rotate` · **`Save`**. Swipe sideways between pages; a page rail of ticks shows the count.

**Document**
- A paged viewer with a reorderable grid toggle.
- Dock: **`Share`** · `Edit` · `Text` · `More`.
- *More* opens a bottom sheet of grouped tool cards. Each has an icon, a name and a one-line description, for example:
  - "Remove handwriting: keeps printed text, removes pen marks."
  - "Erase: paint over anything to remove it."
  - "Sign: draw or reuse your signature."
  - "Password: only people with the password can open the PDF."

**AI tool preview** (Erase, Remove handwriting)
- The page shows the hatched mask. Below it, `Brush` / `Eraser` size chips and a **press-and-hold "Compare"** button.
- The dock offers `Cancel` · **`Apply`**. After applying, a snackbar shows "Handwriting removed" with an `Undo` action.

**Text**
- The page and its recognized text are shown side by side on tablets and stacked on phones.
- Dock: **`Copy`** · `Export` (TXT, DOCX) · `Translate` (appears only once a language pack is installed).

### 7.6 Bans
Gradient text, glow or neon, glassmorphism, sparkle "AI" icons, onboarding carousels, decorative illustrations, mascots, and orange on anything that isn't pressable. The page image always gets the most pixels.

### 7.7 Reference
The world's quality-bar board and hero are saved in `.impeccable/refs/` (light originals). They set the craft level and are not layouts to copy. The direction contract and DESIGN.md are written at build time (Phase 0), from the shipped components.

## 8. Accessibility and quality
WCAG 2.2 AA contrast in both themes. 48 dp touch targets. Type scales up to 200% without clipping. TalkBack: the camera announces the detection state ("Page found, hold steady", "Captured, 3 pages"). Haptic tick on auto-capture. Reduced motion means crossfades only. Everything localizable (no text in images), with RTL mirroring.

**Testing**
- Unit tests for geometry.
- **Golden-image tests** for every filter and clean-up tool on a fixed corpus of 60 of our own photos (receipts, forms, books, whiteboards, low light, shadows, colored pens).
- Detection benchmark (IoU against hand-labeled quads) in CI.
- Compose UI tests for the primary loop.
- Macrobenchmark for startup and capture.
- Screenshot tests at font scales 1.0 and 1.3, in dark and light.

## 9. Roadmap

| Phase | Deliverable | Exit criterion |
|---|---|---|
| **0 Foundation** | Project, modules, design tokens and components for the chosen direction, Room schema, navigation | Library and empty states render in dark and light |
| **1 MVP scan** | CameraX capture, live detection, auto-capture, batch, crop, warp, five filters, shadow removal, PDF/JPG export, share, folders, trash | The primary loop in 3 taps; detection IoU ≥ 0.90 on the corpus |
| **2 Text** | OCR (background WorkManager), searchable PDF, full-text search, text view and copy | Search finds words inside scans |
| **3 Modes** | ID card, Book (split; dewarp after FTO review), Whiteboard, QR/barcode, gallery import with detection | Each mode passes its golden tests |
| **4 PDF tools** | Merge, split, reorder, rotate, compress, sign, annotate, watermark, password, App lock + encryption at rest | Output PDFs validate (veraPDF basic checks) |
| **5 Clean-up AI** | Erase brush + inpaint, handwriting removal (color then model tier), finger removal, learned detector | Handwriting removal keeps ≥ 98% of printed-text OCR accuracy on the test set |
| **6 Text export** | DOCX export, Translate (Bergamot, if licensed models are available), more OCR languages | Everything works offline; no network traffic other than user-requested downloads |
| **7 Polish** | Tablets and foldables, widget, QS tile, accessibility audit, performance budgets, FTO review sign-off | All §5.11 budgets met; FTO cleared |

## 10. Risks
- **Patents** (⚠ items): mitigated by §2 and an FTO review before release; risky features ship behind flags.
- **Handwriting removal quality** depends on synthetic training data. The color tier ships first, and the model tier only once it beats it.
- **Model size**: models download on demand, and the core scanner never needs a download.
- **F-Droid review time and build failures:** the first inclusion can take weeks. Keep the build plain Gradle with no proprietary plugins, and publish signed APKs on GitHub/Codeberg Releases in the meantime.
- **Android developer verification** (announced by Google in 2025, rolling out from 2026, and possibly affecting apps installed outside Play): watch it; it may require registering the signing key with Google even for F-Droid distribution.
- **OCR quality with Tesseract** is lower than ML Kit on photos. Mitigated by preprocessing; benchmark it on the corpus in Phase 2.
- **Device fragmentation** in CameraX: fall back to manual capture, and keep a test matrix of 6 devices.

## 11. Open decisions
- Final app name (working name: Oscanner).
- ~~Visual direction~~: chosen, Soft Card, Night (§7).
- ~~Store~~: decided, **F-Droid first** (§5.10). Google Play later, once 12 testers are available.
- ~~License~~: decided, **GPL-3.0**.

## 12. Sources
- CamScanner website, feature and tool listing: https://www.camscanner.com/
- CamScanner on the App Store (description, version history, IAP prices): https://apps.apple.com/us/app/camscanner-pdf-scanner-app/id388627783
- Apple "Get organized" story (Smart Erase description): https://apps.apple.com/story/id1815461355
- CamScanner Android 3.0 user manual (enhance modes, settings): http://s.intsig.net/r/manual/CamScanner_Android3.0_en-us.html
- CamScanner blog, watermark removal for US users (2022): https://blog.camscanner.com/2022/11/22/camscanner-unlocks-an-advanced-feature-to-american-ios-and-android-users/
- Scanner.biz review of free scanner apps: https://scanner.biz/reviews/best-free-scanner-apps/
- TechBullion, free vs premium overview: https://techbullion.com/why-camscanner-stands-out-and-ahead-the-best-scanner-app/
- Capterra user reviews: https://www.capterra.com/p/210426/CamScanner/reviews/
- MakeUseOf (older filter list): https://www.makeuseof.com/tag/scan-documents-phone-camscanner-android-iphone/
- Pocketables (2012 B&W mode): https://pocketables.com/2012/08/camscanner-for-android-update-adds-awesome-bw-document-mode.html
- CamScanner 2026 press (AI image detector, tax workflow): https://parsers.vc/news/260217-camscanner-redefines-trust--ai-powers/
- Patent families noted for FTO: US 7301564, US 6985631, US 10331966 / 10943140 / 11423633 / 11682225 / 12051258 (via USPTO and Google Patents)
