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
| Slides (projector or screen, cropped and straightened, de-glared) | ✅ Slides mode |
| Capture Screens (photo of a monitor, moiré removed) | ✅ Slides mode, with screen patterns reduced |
| Timestamp (date and time stamped on the scan) | ✅ |
| HD toggle (capture at full sensor resolution) | 🟡 always full resolution, no choice |
| Incorrect Question / test paper (blank out answers, re-scan missed questions) | ✅ handwriting removal blanks a test; split into questions |
| Formula (maths to LaTeX) | ❌ |
| ID Photo Maker (passport photo sizes, background colour) | ✅ |
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
| Restore old photo, Enhance image (colour photos) | ✅ Photo look |
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
  do: real photos in the benchmark. Tried 2026-10-21 and dropped: letting the finder overrule
  the edge search's outline when they disagree (it lost the second of two sheets) or when the
  outline takes in what the finder calls desk (its 48×64 mask can't tell: wrong and right
  outlines score the same). Of the 31 wrong outlines most are near misses, 2 to 5% off, which
  is edge refinement, not finding. Now 190 of 240. Target: 225 of 240.
  2026-10-22: the finder retrained on patterned cloth, booklets and curved spreads (from photos
  users sent), and its outline kept when its sides won't snap: 193 of 240, the new outlines
  near misses. The camera's own outline is now kept for the captured photo. Still to do: the
  finder on live frames (off since 0.5.4 for misfiring on real ones), and a benchmark of real
  photos rather than drawn ones.
- **Auto filter parity.** Side-by-side test set against our own baseline: whiter paper, no grey
  cast, crisp but not crunchy text, colour kept where it matters. Add a "No shadow" style
  option if Auto can't cover both.
  2026-10-24: measured on 21 real page photos (tools: `FilterHarnessTest`, `FILT_DIR`): paper
  already comes out 97% pure white with no measurable cast. The weak spot was crunchiness:
  sharpening lifted grain and the blotches of compressed photos as much as text. Auto, Bright
  and Gray now sharpen only steps of 8 levels or more: texture on smooth areas down 17%, text
  edges as crisp as before. Toner specks (2026-10-24): Auto and Gray even out light tones
  (noise coring: steps under 8 levels from the neighbourhood go, from 16 stay), so a
  photocopy's stipple and paper grain no longer turn into blotches, while pencil, thin lines and
  text edges stay. Specks on paper down by half (0.9 to 0.4 per megapixel), mottle 2.2 to 1.8,
  edges unchanged, about 40 ms more a page. Bright is left as it was, for the faintest pencil.
  Still to do: pale shading that flattens away (a tinted panel bigger than the paper estimate's
  reach goes white with the shadows), solid shapes hollowed out, and halftone tints inside
  pictures.
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
  at 200 dpi and read for text. ✅ A PDF's own text (2026-10-24) is kept instead, word by word
  with where each sits, on Android 15 and later (its PDF renderer can give it): exact, and
  about 0.3 s for a dense page rather than seconds of reading. Pages with fewer than 20 words
  (a scan, maybe under a stamped line) or text that doesn't come out as letters are still
  read; cropping, turning or blacking out a page reads it again, as for scans.
- ✅ **Merge across sources**: a document's "Add from files" adds a PDF's pages (or pictures)
  after its scans, and documents of either kind merge.
- ✅ **Smaller PDF** to share, whatever the quality setting (2026-10-21).
- ✅ **Long image** export: pages stacked into one tall picture (2026-10-21).

### 3.3 Office and language
- ✅ **Tables to Excel** (2026-10-21): Text → Export → Spreadsheet places read words in rows
  and columns by where they sit; numbers stay numbers. A cell whose text wraps onto
  more lines (2026-10-24) stays one cell, also when its row's other cells sit centred on it.
- ✅ **Better Word export** (2026-10-21): headings, centred lines, paragraphs and letter sizes
  rebuilt from where the read words sit; pictures (photos, charts, logos) cut from the page and
  put back in reading order. Text in two columns (2026-10-24) is read down each column, not across both, and set in two
  columns in Word; the read text, copied or saved as .txt, follows the same order.
- ✅ **Slides to PowerPoint** (2026-10-21): Share → Share as slides; each page fills a slide,
  its text behind the picture so the deck can be searched.
- **On-device translation** with downloadable language pairs, shown side by side and overlaid
  on the photo. Licence check done (2026-10-21): Mozilla's Firefox Translations models are
  MPL-2.0 (their repository's LICENSE; the repository is archived, the models now live on
  Mozilla's storage), and the bergamot-translator engine is MPL-2.0 too; both fit a GPL app.
  Next: a native build (NDK, the engine and its ARM matrix library) and a language-pair
  download screen. dev.davidv.translator on F-Droid shows it can be done (with slimt).

### 3.4 Capture modes
- ✅ **Slides / screen mode** (2026-10-21): colours kept, screen patterns reduced, squared up to
  16:9, 16:10 or 4:3 when the outline is close to one.
- ✅ **Timestamp** (2026-10-21): an Auto-fix option stamps when the photo was taken, as a text
  mark that can be moved or deleted.
- ✅ **ID photo** (2026-10-21): Share → ID photo makes a 35 × 45 mm, 2 × 2 in or 33 × 48 mm
  photo at 300 dpi from a page's photo, the head placed by the size's rules, on white, gray,
  light blue or the photo's own background, and a 4 × 6 in sheet of copies. Faces are found
  with Android's own face finder; the background is cut with GrabCut.
- ✅ **Test paper** (2026-10-21): blank the answers with handwriting removal, then Edit → Split
  into questions puts each numbered question ("1.", "2)", "Q3", "Question 4") with its answer
  space on a page of its own in a new document, across pages too. Marking which were wrong
  is still to do.
- **Large objects** (blueprints, posters): stitch overlapping shots. Only after a patent check.

### 3.5 Editing extras
- ~~Pixelate / blur as a second style for black-out~~: not doing it. Pixelated or blurred text
  can often be read back (tools such as Depix do it), and black-out is for privacy; solid
  boxes stay the only style. ✅ Black out → Find → All the text (2026-10-21) covers every line of
  read text, a box per line and column, leaving pictures, rules and signatures.
- ✅ **Retype a word** (2026-10-21): in Sign and draw → Text, tap a word that was read to change
  it. The old word is covered with the paper's colour from around it and the new one written
  in its place, in the line's letter height and the word's ink colour. The phone's own fonts
  are used, so it matches print closely but not exactly.
- ✅ **Restore old photo** (2026-10-21): the Photo look stretches each colour back between its
  own darkest and lightest (undoing fading and its yellow or magenta cast), gives back a little
  colour, and fills in dust specks on plain areas and thin straight bright scratches. Detail in
  the picture, such as the catchlights in eyes, is left alone. Torn corners and large stains are
  still to do.

### Library
- ✅ **Names that say what a document is** (2026-10-24): once read, a document still called
  "Scan …" or "Import …" is named for the kind of paper (receipt, invoice, quote, statement,
  payslip, letter, contract, ticket, prescription, certificate, form), who it's from and its
  date: "Receipt – Greenleaf Market – 18 Oct 2026", "Letter – Your council tax band review –
  7 Oct 2026". All on the phone, by rules rather than a model: no download, instant. Pages it
  can't place keep the heading as before. A document whose import was cut short (the screen
  closed) is now finished when the app next starts.
- ✅ **Folders** (2026-10-24): long-press a folder's chip, or open the folder and use More, to
  rename or delete it. Deleting keeps its documents (under All) unless you choose to put them
  in Trash, with Undo. Under All, documents in folders are grouped under each folder's name,
  after those in none; tapping the name opens the folder.

### 3.6 Optional, later
- **On-device questions and summaries** with a small downloadable model, off by default, so
  the app stays offline-only.
- ✅ **Send to computer without an account** (2026-10-24): Share → Send to a computer shows an
  address (and a QR code) to open in a browser on the same Wi-Fi or the phone's hotspot; the
  phone serves the PDF itself while the dialog is open, at most 15 minutes. The address holds
  an 8-letter secret and the server stops after 50 wrong guesses. Plain HTTP on the local
  network, which the dialog says.
- **Formula to LaTeX**, **counting objects**: only if people ask.

### Not planned
Accounts, cloud sync, share links hosted by us, fax, ads, watermarks, paywalls.
