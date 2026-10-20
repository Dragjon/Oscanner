# Oscanner design system: Soft Card, Night

As built in `core/design` (Theme.kt, Type.kt, Components.kt, Motion.kt, DocumentCard.kt).
PLAN.md §7 is the intent; this file records what the code actually does.

## The one rule

The only saturated color on screen is something you can press. Orange means "act"; everything
else (content, status, chrome) stays warm neutral. Status is never orange: the camera's page
outline is paper-white, processing chips are gray, errors use a muted red with an icon.

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
titleMedium 16/22 semibold for document names, bodyLarge 16/24 for sentences, bodyMedium 14/20
for metadata, labelLarge 15/20 semibold for buttons, labelMedium 12/16 for dock and chip
labels. Dock labels stop growing at 1.3× system font scale so four still fit at 200%.

## Shape and space

Cards 24 dp, dock 28 dp, sheets 28 dp top corners, chips 16 dp, thumbnail wells 12 dp,
buttons fully round. 4 dp grid; 16 dp screen gutter, 12 dp between cards. Tonal elevation only
in Night. Lists cap at 720 dp on large screens; the dock caps at 480 dp.

## Components

- **Dock**: floating card at thumb height on every main screen; at most one `PrimaryPill` and
  a few `DockAction`s (icon above a word).
- **DocumentCard**: fixed 116 dp plate (thumbnail, name, pages · date · size); a status chip
  crossfades in place so the list never reflows; long-press selects.
- **SheetAction**: bottom-sheet row with icon, name and one line saying what it does.
- **StatusChip**: Enhancing / Reading text / Couldn't finish, in neutral colors.
- Every control is an icon plus a word; icon-only buttons (back, close) carry TalkBack labels.

## Motion

One press response: spring to 0.97 (damping 0.8). State chips crossfade. Camera capture: a
white flash at 30% for 80 ms and a haptic tick; the shutter ring fills while auto-capture
waits. With system animations off, the press scale and flash are skipped.

## Not yet built from §7

The container transform from card to document, and screenshot tests at font scales 1.0/1.3.
