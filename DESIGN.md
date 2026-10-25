# Oscanner design system: Soft Card, Night

As built in `core/design` (Theme.kt, Type.kt, Components.kt, Motion.kt, DocumentCard.kt); the
wipe tile, tile sheet and pinned bar as built in `app/.../WelcomeScreen.kt`.
PLAN.md §7 is the intent; this file records what the code actually does.

## The one rule

The only saturated color on screen is something you can press. Orange means "act"; everything
else (content, status, chrome) stays warm neutral. Status is never orange: the camera's page
outline is paper-white, processing chips are gray, errors use a muted red with an icon. The
welcome tour's wipe line and its grip are the same paper-white `#F4EEE6`.

## Color (Material 3 roles)

| Role | Night (default) | Day | Used for |
|---|---|---|---|
| background / surface | `#161412` | `#FFF4E6` | Screen ground |
| surfaceContainer | `#221F1C` | `#FFFFFF` | Cards, dock, sheets |
| surfaceContainerHigh | `#2C2824` | `#F7EBDD` | Thumbnail wells, chips, fields |
| onSurface | `#F4EEE6` | `#2B241F` | Text |
| onSurfaceVariant | `#B3A99D` | `#6F625A` | Secondary text, idle icons |
| primary | `#FF6A3D` | `#F25A2C` | Pressable only: Scan, Save, Share, Apply, active chips, shutter ring |
| onPrimary | `#1E0E07` | `#1E0E07` | Ink on orange (white on this orange fails AA) |
| primaryContainer | `#3B231A` | `#FFE3D6` | Selected-but-not-primary (chosen filter, chosen card) |
| error | `#FF9C9C` | `#B3261E` | Always with an icon, never a filled button |

Extras (`OscannerExtras`): success `#9AD3A6`/`#2E7D4F`, detection outline `#F4EEE6`, clean-up
hatch, and a soft shadow on the dock in Day only. "Use wallpaper color for buttons" swaps only
the primary family; grounds and text stay ours so contrast holds.

Camera chrome sits on photos in both themes: black scrim at 55%, ink `#F4EEE6`.

## Type

Figtree (OFL, bundled), sentence case, no tracking. headlineMedium 28/34 for screen titles,
headlineSmall 24/30 semibold for bottom-sheet titles, titleMedium 16/22 semibold for document names, bodyLarge 16/24 for sentences, bodyMedium 14/20
for metadata, labelLarge 15/20 semibold for buttons, labelMedium 12/16 for dock and chip
labels. Dock labels stop growing at 1.3× system font scale so four still fit at 200%.

## Shape and space

Cards 24 dp, dock 28 dp, sheets 28 dp top corners, chips 16 dp, thumbnail wells 12 dp,
buttons fully round. A picture well inside a 24 dp card with 8 dp padding is 16 dp, so the
corners stay concentric (outer radius minus padding). 4 dp grid; 16 dp screen gutter, 12 dp between cards. Tonal elevation only
in Night. Lists cap at 720 dp on large screens; the dock caps at 480 dp; the welcome tour caps
at 560 dp.

Bento rows: a row of tiles takes `height(IntrinsicSize.Min)` so its height comes from its
content, and a tile that should match its neighbours takes `fillMaxHeight()`; nothing in a
row is given a fixed height to line up. A bar pinned over scrolling content measures itself
(`onGloballyPositioned`) and the content ends in a spacer of that height plus 16 dp, so the
last item scrolls clear at any font scale; never guess the bar's height.

## Components

- **Dock**: floating card at thumb height on every main screen; at most one `PrimaryPill` and
  a few `DockAction`s (icon above a word).
- **DocumentCard**: fixed 116 dp plate (thumbnail, name, pages · date · size); a status chip
  crossfades in place so the list never reflows; long-press selects.
- **SheetAction**: bottom-sheet row with icon, name and one line saying what it does.
- **StatusChip**: Enhancing / Reading text / Couldn't finish, in neutral colors.
- **WipeTile**: a pressable `surfaceContainer` card (24 dp, 8 dp padding) holding a picture
  well (16 dp) and a one-line verb-phrase caption in labelLarge. The well shows the app's result
  with the phone photo clipped over its left part; between them a 2 dp paper-white line with a
  1 dp keyline either side (`#161412` at 60%) so it reads over white paper as over a photo.
  The line is the only ornament. Results are cropped to fill, except a page whose edges matter
  (a receipt's totals), which is fitted whole on its own white paper.
- **Tile sheet**: tapping a WipeTile opens a `ModalBottomSheet` on `surfaceContainer`, fully
  expanded, 20 dp sides, 28 dp bottom: headlineSmall title, the pictures whole (aspect ratio
  no taller than 3:4), a 3 dp line under the finger (drag or tap to place it) with a 36 dp
  paper-white round grip carrying a swap icon in dark ink, a labelMedium row "Photo / Drag to
  compare / Oscanner", then two plain lines in bodyLarge. Opened tiles go to a sheet, not
  expanded in place.
- **Type-only tile**: a claim with no picture (the privacy promise) sits on
  `surfaceContainerHigh` at 24 dp, 18 dp padding, an onSurfaceVariant icon at top and a
  titleLarge semibold line over a bodyMedium one, so it reads as a different kind of tile.
- **Pinned action bar**: background-colored, one `PrimaryPill` centred with at most a neutral
  `TextButton` (onSurfaceVariant, labelLarge) above it; sized as in Shape and space.
- Before/after pictures are the app's own output from the photo beside them, never drawn or
  retouched; each shipping raster has an origin note in `tools/welcome-assets/provenance/`.
- Every control is an icon plus a word; icon-only buttons (back, close) carry TalkBack labels.

## Motion

One press response: spring to 0.97 (damping 0.8). State chips crossfade. Camera capture: a
white flash at 30% for 80 ms and a haptic tick; the shutter ring fills while auto-capture
waits. With system animations off, the press scale and flash are skipped.

The wipe: a 5000 ms loop per tile, holding on the photo 1600 ms, sweeping to the result in
900 ms (fast-out-slow-in), holding, and sweeping back; each tile starts 700 ms after the one
before so the grid breathes rather than flashes. Olaf greets with one 1400 ms lean-and-settle
from his base. With animations off, tiles show the result, still, and Olaf stands.

## Not yet built from §7

The container transform from card to document, and screenshot tests at font scales 1.0/1.3.
