package dev.olaf.oscanner.core.model

/**
 * Passport and ID photo sizes, with where the head goes: [head] is the share of the photo's
 * height from chin to crown, [top] the share left above the crown. From the published rules
 * (ICAO 9303 and the countries' own), aiming at the middle of each allowed range.
 */
enum class IdPhotoSize(val widthMm: Float, val heightMm: Float, val head: Float, val top: Float) {
    /** Most of the world (EU, UK, India, Australia, Singapore, …): head 32–36 mm of 45. */
    Mm35x45(35f, 45f, 0.76f, 0.08f),
    /** The United States: 2 × 2 in, head 1–1⅜ in. */
    In2x2(50.8f, 50.8f, 0.6f, 0.12f),
    /** China: 33 × 48 mm, head 28–33 mm. */
    Mm33x48(33f, 48f, 0.64f, 0.1f),
}

/** What goes behind the person: a plain color (RGB), or the photo's own background. */
enum class IdBackground(val rgb: Int?) {
    White(0xFFFFFF),
    LightGray(0xE8E8E8),
    LightBlue(0xD5E5F5),
    Keep(null),
}
