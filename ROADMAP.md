# Roadmap: making CamScanner unnecessary

Written 2026-10-21. Compares Oscanner 0.5.2 with CamScanner 7.27 (Android, October 2026) and
plans the work to cover what people still reach for CamScanner for.

**How CamScanner was looked at.** From its public store listings, website and reviews (sources
in `PLAN.md` §12), and, with the owner's go-ahead on 2026-10-21, by installing the official
Play build on a test emulator and using it as a user: which tools and modes it offers, which are
paid, and what a scan looks like. Nothing was decompiled or unpacked beyond checking the
signature, no network traffic was inspected, no assets or models were taken, and its screens
are not layout references (`PLAN.md` §1 still applies to how we build).

---

## 1. What CamScanner offers, and where Oscanner stands

✅ have it · 🟡 partly · ❌ missing · ⛔ won't do (against our principles)

### Capture
| CamScanner | Oscanner |
|---|---|
| Auto edge detection, auto-capture, single and batch | ✅ (light busy counters improved, still behind; see §3.1) |
| Modes: Document, ID card, Book, Whiteboard, Scan code | ✅ |
| Slides (projector or screen, cropped and straightened, de-glared) | 🟡 Whiteboard and Document mostly cover it |
| Capture Screens (photo of a monitor, moiré removed) | 🟡 "Reduce screen patterns" exists, no mode |
| Timestamp (date and time stamped on the scan) | ❌ |
| HD toggle (capture at full sensor resolution) | 🟡 always full resolution, no choice |
| Incorrect Question / test paper (blank out answers, re-scan missed questions) | 🟡 handwriting removal blanks a test; no question cropping |
| Formula (maths to LaTeX) | ❌ |
| ID Photo Maker (passport photo sizes, background colour) | ❌ |
| Mega Scan (stitch a large object from many shots) | ❌ (⚠ patents) |
| Import images | ✅ |
| Import files (PDF, Word, Excel, PPT into the library) | ✅ PDFs and pictures; ❌ Office files |

### Clean-up and enhance
| CamScanner | Oscanner |
|---|---|
| Filters: Original, Lighten, Enhance, No Shadow, B&W, Eco, Grayscale | ✅ Original, Auto, Bright, Gray, Ink, Board; shadow removal in Auto |
| Erase handwriting ("Erase Marks") | ✅ print-vs-handwriting network |
| Smart Erase (brush) | ✅ Erase brush |
| Remove fingers, moiré, curved-page flattening | ✅ |
| Remove creases and stains, bleed-through | ✅ |
| Restore old photo, Enhance image (colour photos) | ❌ |
| Mosaic / pixelate, auto-mask text | 🟡 black-out boxes and "find emails / numbers" |
| Edit text inside the image (retype a word in the page's font) | ❌ |

### Text and conversion
| CamScanner | Oscanner |
|---|---|
| OCR, extract text, searchable PDF (paid) | ✅ free; PP-OCR for English, Tesseract for others |
| To Word | ✅ headings, paragraphs and pictures rebuilt from the page |
| To Excel (tables) | ✅ from the text, by layout |
| To PPT | ✅ a page to a slide, text searchable |
| PDF to images, PDF to long image | ✅ |
| Photo translation keeping the layout; text translation | ❌ |
| Markdown view and edit | ❌ (low value) |

### Documents and PDF tools
| CamScanner | Oscanner |
|---|---|
| Sign, annotate, add watermark | ✅ |
| Merge, extract pages, reorder, rotate, delete | ✅ (imported PDFs too) |
| Compress | ✅ "Share a smaller PDF" |
| Lock (password) | ✅ PDF password and app lock |
| Tags, folders, search by title, tag and text | ✅ |
| Print | ✅ |
| Fax (paid per page) | ⛔ |

### Utilities and cloud
| CamScanner | Oscanner |
|---|---|
| AI Chat, Ask AI, Solver AI (homework answers) | ⛔ cloud; 🟡 an on-device version is possible later (§3.6) |
| CountCam (counting objects), Measure | ❌ niche |
| Cloud sync, share links, send to computer, account | ⛔ |

### Money
CamScanner's free tier stamps "Scanned with CamScanner" on every export, shows ads, and puts
ID cards, PDF tools, Extract Text and Word export behind a subscription (3-day trial, then
$49.99 a year, seen 2026-10-21). Everything Oscanner has is free and has no watermark.

---

## 2. Why someone would still pick CamScanner

In rough order of how many people it affects:

1. **They already have a PDF.** A form arrives by email and needs signing, a scan needs merging
   with a PDF they were sent, or they want to compress or password one. Oscanner only works on
   what it scanned itself.
2. **The scan just works on their counter.** Detection that misses a white page on a light
   table, or an Auto filter that leaves a grey cast, sends people back to what they trust. On
   our new off-white benchmark the detector found 164 of 240 pages; with the learned page
   finder, 192.
3. **They need Office files.** A table to Excel, a page to an editable Word file that still looks
   like the page, slides to PowerPoint.
4. **They need it in another language.** Translate a letter or a menu, ideally over the photo.
5. **It's for school.** Blank a worksheet, crop out the questions they got wrong, read maths.
6. **Odd jobs it does in one place**: passport photos, a long screenshot-style image, a date
   stamp on site photos, a clean photo of a slide, restoring an old photo.
7. **It's on their other devices.** Sync, links and a desktop app. We won't do accounts; local
   ways to get a file to a computer cover part of this.
8. **AI answers.** Summaries, questions about a document, homework help.

---

## 3. Plan

Each step ships on its own, behind the same bar as today: tested on the emulator and on the
benchmark it belongs to, committed and pushed, released only when everything is checked.

### 3.1 Quality first (the thing people notice every day)
- 🟡 **Page detection on any counter.** A small learned page finder, trained on drawn scenes
  (`tools/page-finder`), now steps in when the edge search finds nothing (2026-10-21): 192 of
  240 on the off-white benchmark (was 164), no new outlines on the 177 older scenes. Still to
  do: real photos in the benchmark, and letting the finder overrule a classical outline that
  is slightly off (most of the remaining misses). Target: 225 of 240.
- **Auto filter parity.** Side-by-side test set against our own baseline: whiter paper, no grey
  cast, crisp but not crunchy text, colour kept where it matters. Add a "No shadow" style
  option if Auto can't cover both.
- ✅ **Creases, stains and bleed-through** in Clean up. ✅ Show-through (2026-10-21): Clean up →
  Show-through takes out the back page's print, told from faint ink on the front by its soft
  edges and gray colour; pencil, highlighter and pale photos stay (94% found on a staged page,
  none of the pencil); it takes out the shadow of a fold too, though a fold's thin lit edge
  stays. ✅ Stains (2026-10-21): Clean up → Stains divides out the tint of coffee, tea and
  water stains, so the print under them stays; highlighter, warm photos and yellowed paper
  are left alone. A faint outline of a stain's rim can remain.

### 3.2 Work with files people already have
- ✅ **Import PDFs** into the library as documents (2026-10-21): from Import → Files, a
  document's "Add from files", or by sharing or opening a PDF with Oscanner. Pages are rendered
  at 200 dpi and read for text; keeping a PDF's own vector text is still to do.
- ✅ **Merge across sources**: a document's "Add from files" adds a PDF's pages (or pictures)
  after its scans, and documents of either kind merge.
- ✅ **Smaller PDF** to share, whatever the quality setting (2026-10-21).
- ✅ **Long image** export: pages stacked into one tall picture (2026-10-21).

### 3.3 Office and language
- ✅ **Tables to Excel** (2026-10-21): Text → Export → Spreadsheet places read words in rows
  and columns by where they sit; numbers stay numbers. Ruled tables with cells that wrap onto
  two lines are still to do.
- ✅ **Better Word export** (2026-10-21): headings, centred lines, paragraphs and letter sizes
  rebuilt from where the read words sit; pictures (photos, charts, logos) cut from the page and
  put back in reading order. Columns side by side still come out one after the other.
- ✅ **Slides to PowerPoint** (2026-10-21): Share → Share as slides; each page fills a slide,
  its text behind the picture so the deck can be searched.
- **On-device translation** with downloadable language pairs (Bergamot or another open model,
  pending a licence check), shown side by side and overlaid on the photo.

### 3.4 Capture modes
- ✅ **Slides / screen mode** (2026-10-21): colours kept, screen patterns reduced, squared up to
  16:9, 16:10 or 4:3 when the outline is close to one.
- ✅ **Timestamp** (2026-10-21): an Auto-fix option stamps when the photo was taken, as a text
  mark that can be moved or deleted.
- **ID photo**: face-centred crop to common passport sizes, plain background colour, a sheet
  of copies to print.
- **Test paper**: blank the answers (have), then crop each question into its own page for
  re-doing; mark which were wrong.
- **Large objects** (blueprints, posters): stitch overlapping shots. Only after a patent check.

### 3.5 Editing extras
- ~~Pixelate / blur as a second style for black-out~~: not doing it. Pixelated or blurred text
  can often be read back (tools such as Depix do it), and black-out is for privacy; solid
  boxes stay the only style. "Cover all text in a box" is still worth adding.
- **Retype a word** in the image: erase it and draw the new word in a close matching font.
- **Restore old photo**: dust and scratch removal, colour fading correction (classical first).

### 3.6 Optional, later
- **On-device questions and summaries** with a small downloadable model, off by default, so
  the app stays offline-only.
- **Send to computer without an account**: a local Wi-Fi page or QR hand-off on the same network.
- **Formula to LaTeX**, **counting objects**: only if people ask.

### Not planned
Accounts, cloud sync, share links hosted by us, fax, ads, watermarks, paywalls.
