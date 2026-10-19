# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Stack

Kotlin + Jetpack Compose, CameraX, OpenCV, Tesseract OCR, on-device ML (FOSS only). minSdk 26 (Android 8.0). Confirmed by user 2026-10-20.

## Users

One person scanning their own paperwork on their own phone: receipts, forms, contracts, ID cards, book pages, class notes, whiteboards, worksheets. Usually standing at a desk or counter, one hand on the phone, wanting a clean PDF or image in seconds and then to send it somewhere else.

## Product Purpose

A clean-room Android document scanner whose day-to-day use matches CamScanner (capture → auto-crop → enhance → save/share, plus OCR and AI clean-up tools) without its ads, watermarks, paywalls, account walls or busy interface. Success: a page goes from camera to shared PDF in under 10 seconds and three taps, and a first-time user never needs an explanation of any control.

## Positioning

Private, on-device, no account, no ads, no watermark. Everything a mainstream scanner does for one person, nothing it does to monetize them.

## Operating Context

- Captured in variable light (kitchen tables, offices, classrooms), often with shadows from the user's own hand and phone.
- Output goes out through Android share sheet, Storage Access Framework (Drive/Dropbox/OneDrive providers), print, or email. No proprietary cloud.
- Documents live in a local library (folders, tags, full-text search over OCR text).

## Capabilities and Constraints

- All processing is on-device. The F-Droid build has no cloud AI: no Ask/Summarize, and no network calls except user-requested downloads of free model and language files. Confirmed by user 2026-10-20.
- Distribution: F-Droid first (Google Play deferred, because the 12-tester closed-test requirement can't be met yet). Licensed GPL-3.0; every dependency must be FOSS and buildable from source.
- No accounts, no ads, no watermark, no paywall.
- Clean-room: no CamScanner code, assets, decompiled material, or trade dress. Features are re-derived from public descriptions only. Avoid patented techniques; prefer public-domain or permissively licensed algorithms and models.
- Default theme is dark.

## Brand Commitments

- Working name: Oscanner (from the project folder; not final).
- Dark theme by default. Sleek, restrained; few buttons on screen at once.
- Self-documenting UI: every control's purpose must be obvious from icon + label + context, without tutorials.
- No generic "AI app" styling (no gradient text, glow, sparkle-everything).

## Evidence on Hand

None. No users, reviews, metrics or brand assets exist yet; do not fabricate any.

## Product Principles

1. The page is the hero. Chrome recedes; the document image always gets the most pixels.
2. One obvious next step per screen. Advanced tools are one tap away, never in the way.
3. Private by default. Nothing leaves the device unless the user explicitly sends it.
4. Honest tools. AI results are previewed and reversible; originals are never destroyed.
5. Fast hands. Primary actions sit in the thumb zone and work one-handed.

## Accessibility & Inclusion

Target WCAG 2.2 AA contrast in dark and light themes, TalkBack labels on every control, 48dp touch targets, respects system font scale up to 200% and reduced-motion.
