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
| Mega Scan (stitch a large object from many shots) | ✅ Edit → Pages → Join (2026-10-26) |
| Import images | ✅ |
| Import files (PDF, Word, Excel, PPT into the library) | ✅ PDFs, pictures, Word, Excel and PowerPoint (.docx, .xlsx, .pptx) |

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
| Photo translation keeping the layout; text translation | ✅ on the phone, 58 languages |
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
  2026-10-25: a page with a big picture printed on it was cut down to the paper beside the
  picture, taken for a sheet of its own (or the picture for desk beside one). A sheet only
  splits off now when what's left is not part paper, part darker: drawn pages with a picture at
  the top, middle, bottom or full width 3 of 40 right before, 31 of 40 after; the 240 drawn
  scenes unchanged (164); 124 real document photos (Mendeley set, edge search alone) 36 to 39
  right, 36 to 33 wrong.
  **Head to head with CamScanner (2026-10-27).** The 124 Mendeley phone photos (CC BY 4.0),
  imported into CamScanner 7.27 on the emulator (Import Images, Original look, Auto Crop) and
  into ours (the same detector, page finder and corner network an import uses). CamScanner's
  outline was recovered by matching each of its cropped pages back onto the photo (SIFT,
  RANSAC); 11 near-blank pages couldn't be matched and are left out for both. On 113 photos:

  | | Oscanner 0.8.6 | CamScanner 7.27 |
  |---|---|---|
  | mean overlap (IoU) with the true page | 0.79 | 0.88 |
  | overlap ≥ 0.95 / ≥ 0.90 / ≥ 0.80 | 42% / 59% / 71% | 65% / 79% / 84% |
  | every corner within 2% / 5% of the frame | 35% / 58% | 57% / 73% |
  | nothing found (whole photo kept) | 19 | — |

  CamScanner is better on 55 photos, we are on 14, 44 the same. Nearly all of the worst ones are
  a sheet held up by hand against a pale wall or radiator, where we find nothing; our corner
  network misses them too (right on 1 of those 19, CamScanner 15). So the gap is finding a pale
  page on a pale background and a hand over its edge, then a few percent of corner accuracy.
  Bench: `oscanner-bench/csbench` (CamScanner's outlines, scripts).
  2026-10-27, closing it, step 1: a corner-network outline now stands with one side partly
  hidden (a hand holding the page; at least a quarter of it still on an edge), and the page
  finder is fine-tuned on real photos (Mendeley photos 131 on; the benchmark's are held out,
  though some were shot in the same rooms). Same 113 photos: mean overlap 0.79 → 0.84
  (CamScanner 0.88), ≥ 0.90 59% → 62%, ≥ 0.80 71% → 78%, nothing found 19 → 9. Elsewhere:
  SmartDoc background05 nothing found 86 → 48 of 142 (mean 0.32 → 0.58), drawn scenes 207 →
  206 of 240, empty rooms outlined 2 → 0 of 7. What's left is mostly corner precision (≥ 0.95:
  43% against 65%).
  Step 2: the corner network for pages is tuned on real photos too (Mendeley 131 on, SmartDoc
  backgrounds 01–04, and frames with no page), then blended 80/20 back towards DocAligner's own:
  tuned alone it lost pages among clutter (SmartDoc background05 0.58 → 0.15) and outlined empty
  rooms. ID cards keep DocAligner's own, which holds a card steadier. Same 113 photos: mean
  overlap 0.84 → 0.86, ≥ 0.95 43% → 50%, ≥ 0.90 62% → 72%, corners within 2% 36% → 42%, nothing
  found 9 → 5. Drawn scenes 206 → 225 of 240, SmartDoc background05 0.58 → 0.55, empty rooms
  still 0 of 7, a page video's jumps 7 → 5.
  Then trained longer on a GPU (4 passes) and blended 70/30: SmartDoc background05 0.55 → 0.66
  (nothing found 51 → 37), the 113 photos unchanged (0.86), drawn scenes 226 of 240. The
  network alone gets SmartDoc to 0.92, so the detector's checks now throw away good outlines
  there: the next thing to look at.
  Done: a network outline may now have two faint sides if the other two show plainly (white
  paper on a pale desk, seen small). SmartDoc background05 0.66 → 0.73 (nothing found 37 → 28),
  the 113 photos 0.86 (≥ 0.95 51%, CamScanner 65%), everything else unchanged.
  Corner precision: the network's own corners were closer to the truth (median 1.5% off) than
  what we output after snapping its sides to nearby edges (2.1%; CamScanner 1.8%), and every
  outline was pulled 1.2% inside the paper where CamScanner crops about half that. Network
  corners are now kept as placed and the inset is 0.6%. Same 113 photos: mean 0.86 → 0.87,
  ≥ 0.95 51% → 59%, ≥ 0.90 73% → 74%, corners within 2% 43% → 50% (CamScanner 0.88, 65%, 79%,
  57%); clearly better than CamScanner on 17, worse on 21 (was 13 and 39). SmartDoc
  background05 0.73 → 0.74, drawn scenes 226 → 230 of 240, the rest unchanged.
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
  Pale shading (2026-10-24): the paper further out is now measured across the whole page (a
  square closing, which keeps the slope of uneven light exactly), so a tinted panel of any
  size keeps its tint instead of going white with the shadows: a cell diagram's gray fill
  stays even where it went white in patches, and a dark disc no longer pales inside its rim;
  16 of the 21 benchmark pages unchanged, mottle 1.8 to 1.7. Shapes away from the page's edge
  are no longer hollowed on any benchmark page. Shading or a shape that runs off the image's
  edge still flattens away: a printed band down one side (a diagram's sea) and desk beside a
  loosely cropped page are both a straight-edged strip along a whole side, and nothing in the
  picture tells them apart; telling them would need the photo around the crop.
  Halftone tints inside pictures: tried and dropped (2026-10-24). Smoothing a print's screen
  where fine contrast never lets up across a text-sized square and there's little bare paper
  (which keeps text out: every text page unchanged) made a dark screened disc even again, but
  growing the area to a screen's edge left shapes half smooth and half dotted, and sharpening
  outlines back after the blur drew halos. Still to do, if picked up again: a smoothing that
  keeps outlines (an edge-aware filter guided by the blurred page) instead of blur-then-sharpen. Tried and dropped (2026-10-24): keeping deep shapes that touch the page's edge (a
  map printed to the edge); it kept dark desk beside a loosely cropped page as a black band.
- ✅ **Curved pages follow their text** (2026-10-25): a table's or form's rules show the bend
  only where they are, so the lines of text above and below them now join the fit (a page with
  a table on top, bent 46 px, was left as it was; now within 2 px), and strongly bent lines of
  text are no longer turned away for their tall outline (81 px of bend now within 6 px, from 10).
  ✅ Squeeze toward the spine (2026-10-25): paper nearer the camera looks bigger, so how far
  apart the lines spread, strip by strip across the page, shows how high the paper stands;
  where it drops into the spine, each column is widened back to the paper's true width. The
  camera's place comes from the crop's corners and the photo's focal length (a 26 mm phone lens
  when the photo doesn't say). On rendered book pages, columns that were 15 to 58 px out of
  place (of 1500) end within 3 to 7; with the camera guessed a quarter too near or far, within
  25. In the app (emulator, imported photo of a page curving into its spine): margin ticks
  within 9 px of evenly spaced. Not yet tried on photos of real books.
- ✅ **Creases, stains and bleed-through** in Clean up. ✅ Show-through (2026-10-21): Clean up →
  Show-through takes out the back page's print, told from faint ink on the front by its soft
  edges and gray colour; pencil, highlighter and pale photos stay (94% found on a staged page,
  none of the pencil); it takes out the shadow of a fold too, though a fold's thin lit edge
  stays. ✅ Stains (2026-10-21): Clean up → Stains divides out the tint of coffee, tea and
  water stains, so the print under them stays; highlighter, warm photos and yellowed paper
  are left alone. A faint outline of a stain's rim can remain.

### 3.2 Work with files people already have
- ✅ **Import Word, Excel and PowerPoint files** (2026-10-25), the same ways as PDFs. Read on
  the phone straight from their XML (no Office library: Apache POI would add megabytes) and laid
  out as pages with Android's own text layout, their text kept word by word with where each
  sits, exactly, so nothing is read from the picture. Word: pages its own size and margins,
  headings, bold, italic, underline, sizes and colours, centred and justified paragraphs,
  bullets and numbered lists (by the paragraph or its style), tables with their column widths,
  pictures at their size, page breaks. PowerPoint: a page a slide at its shape, boxes where the
  slide or its layout puts them, alignment, sizes and bullets from the slide, its layout and
  master, fills, pictures, groups, tables; text that doesn't fit is shrunk as autofit does.
  Excel: each sheet a grid over pages, portrait or landscape by its width, columns at their
  widths (scaled to fit down to 60%, then onto pages of their own), dates and percentages as
  Excel shows them, hidden sheets left out. Not read: the old .doc, .xls and .ppt formats,
  charts drawn by Office itself, headers and footers, text boxes floating in Word, and fonts
  (the phone's own are used). Files that unpack to more than 256 MB are refused.
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
- ✅ **On-device translation** (2026-10-24): Text → Translate, from the language the text is in
  (guessed by Android's own language finder) into any of 58, through English when neither is
  English. The models are Firefox's own (MPL-2.0, Firefox Remote Settings
  `translations-models-v2`, the Android sets where Firefox has them), downloaded once per
  language and checked against their hashes; the engine is bergamot-translator built for
  WebAssembly (MPL-2.0, as Firefox ships it, 1.2 MB in the APK), run in a WebView that's never
  shown and can't reach the network, so no NDK build was needed. Read lines are joined back
  into paragraphs first, so sentences translate whole. slimt was passed over: its licence is
  GPL-2.0 without "or later", which can't be combined with GPL-3.0. A page's translation is
  kept beside its files and shown again next time, until its text is fixed. Over the photo:
  each paragraph found by the page layout is translated on its own, painted over in the
  paper's colour around it and its translation written in its place in its ink's colour, as
  big as the page's letters or smaller to fit; a single line may run into the room beside it,
  and a date set against the right margin stays there. Shown as the page's picture on the
  Text screen (tap for full screen) and shared as pictures. ✅ The page's typeface
  (2026-10-25): serif print gets the phone's serif face, told by how its strokes vary in width
  (upright strokes over level ones: 1.15 to 1.43 for Times, Georgia, Cambria, Garamond and Book
  Antiqua, 0.94 to 1.10 for Arial, Calibri, Verdana, Segoe and Tahoma, all 30 told apart at
  20 to 48 px); headings stay bold. Print smaller than 18 px keeps the plain face. ✅ Text on
  pictures (2026-10-25): where the colour round a paragraph varies (a photo, a gradient), only
  its letters, with the pale rim sharpening leaves, are taken off and filled from the picture
  around (the same patch fill as torn photos), and the translation is written with a thin edge
  in the background's colour; on paper or a plain coloured box it's painted over flat as before.

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
  space on a page of its own in a new document, across pages too. ✅ Marking which were wrong
  (2026-10-24): Edit → Mark wrong on a page, or several at once in Pages; a red "Wrong" label
  sits under the page (not on it, where the question's number is). Edit → Practise wrong
  questions copies the marked pages into a new document; Got it right takes a mark off.
- ✅ **Large objects** (blueprints, posters) (2026-10-26): photograph the thing in overlapping
  parts as ordinary pages, then Edit → Pages, choose them and Join. The photos are joined into
  one new page put before the parts (which stay, to delete once it looks right), and its outline
  is found as for any photo. A flat thing seen from anywhere is one picture under a perspective
  transform: each shot is placed by matching marks (SIFT) with one it overlaps, kept where most
  agree (RANSAC), all laid on the middle shot's plane, each pixel from the shot it lies furthest
  inside, brightness matched across the seams (`PageStitcher`). Three askew shots of a drawn
  poster land within 3 px of where they belong; three photos of a real exam page on a desk join
  into the whole page in the app, about 16 s on the emulator. Shots that don't overlap (two
  different posters) are refused. Patent check (not legal advice): the document-specific patents
  found align parts by their OCR'd text (US10356318) or flag text cut off at a part's edge and ask
  for a reshoot (US11822216); neither is done here. Feature matching with a homography is the
  panorama literature's method (Szeliski; Brown and Lowe 2003, 2007), SIFT's patent ran out in
  2020, Microsoft's whiteboard-stitching patent (US7119816) in 2023, and Seiko Epson's image
  stitching patent (US7894689) has lapsed.

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
  the picture, such as the catchlights in eyes, is left alone. ✅ Torn corners and edges
  (2026-10-25): with the Photo look, Clean up → Edges finds where the desk the print lay on
  shows inside the crop at its edge (its color sampled just outside the crop in the photo),
  with the white paper core along the tear, and fills it with more of the picture: small
  squares matched from the rest of it, coarse to fine (PatchMatch), so grass goes on as grass
  and a horizon runs on through. A pale sky reaching the edge of a photo on a pale desk stays:
  a gap must have a sharp edge against the picture. ✅ Faint dust and short scratches
  (2026-10-26): on a faded print's pale sky, dust and scratches stand out by only 20 or so
  levels and the color stretch then makes them plain; now they're found too, but only on plain
  ground with nothing else standing out within 12 px, so a printed caption's letters, ripples
  on water and a weathervane's arms stay. Scratches from 1.5% of the photo's side count there.
  On the 1901 lighthouse print: the sky's scratch and pale specks gone, everything else as
  before. ✅ Tears filled from beside them (2026-10-26): pieces for a gap now come from within
  twice its size of it. Matched from anywhere, a corner torn off over water took in rocks and a
  jetty from up the bank, where the same fence runs; now it's water (on the 1901 print, 70% of
  the gap close in color to the water beside it before, 94% after). The fill there is a little
  smoother than the ripples around it. ✅ Big stains (2026-10-26): with the Photo look, a stain
  brushed over in Clean up is filled with more of the picture, not the paper's color. ✅ Tears
  on a patterned desk (2026-10-26): the desk around the crop is read as up to four colors (a
  checked cloth's red and cream, wood's light and dark grain), and a gap is any run of them. A
  desk color the picture has plenty of too (cream, and a pale sky) only counts among the desk's
  other colors, as far from them as the pattern is coarse, measured along the photo's edge; and
  on a desk of several colors a gap must run along the tear's white core for a quarter of its
  edge, so a dark trunk as dark as one of the desk's colors stays. On the 1901 print laid on a
  red and cream check, in the app: the torn corner was missed; now it's water. A tear's desk
  left inside the crop also threw off the Photo look's color stretch (a check's red cast the
  whole print teal); filled first, that goes too.

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
