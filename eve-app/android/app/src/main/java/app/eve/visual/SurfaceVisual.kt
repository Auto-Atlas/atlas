package app.eve.visual

/**
 * A validated `surface_visual` card from the WS stream — EVE choosing to SHOW something instead of
 * only saying it. The server sends
 * `{"type":"surface_visual","kind":"desktop_screen"|"image"|"note","title":"…",
 *   "visual_id":"<16 lowercase hex, empty for notes>","url":"/v1/visual/<id>","text":"<note text>"}`
 * (see visual_tool.py / approval_api.py's /v1/visual endpoints).
 *
 * This is the PURE presentation-mapping layer (no Android, no Compose, no I/O) so the parse rules
 * are fully JVM-unit-testable, mirroring [app.eve.vision.CaptureRequest]:
 *  - an unknown/blank [kind] → null (the card is ignored, never rendered blank),
 *  - an image kind (desktop_screen / image) with a missing or non-hex `visual_id` → null (we'd have
 *    nothing to fetch), matching the server's `visual_store.valid_id` (`^[a-f0-9]{8,32}$`),
 *  - a `note` with empty text → null (nothing to show),
 *  - a blank title falls back to a sensible per-kind default so the card header is never empty.
 */
data class SurfaceVisual(
    val kind: Kind,
    val title: String,
    /** Non-null (validated hex) for image kinds; null for notes. */
    val visualId: String?,
    /** The note/log body for [Kind.NOTE]; empty string for image kinds. */
    val text: String,
) {
    enum class Kind { DESKTOP_SCREEN, IMAGE, NOTE }

    /** True when this card carries a fetchable image (desktop_screen / image) rather than text. */
    val isImage: Boolean get() = kind != Kind.NOTE

    companion object {
        // Must match visual_store.valid_id exactly: ids become filenames on the server.
        private val ID_RE = Regex("^[a-f0-9]{8,32}$")
        private const val MAX_TITLE = 120
        private const val MAX_TEXT = 8_000

        /**
         * Parses a raw stream event's fields into a [SurfaceVisual], or null if the card is
         * malformed / not showable. Pure and side-effect-free.
         */
        fun parse(kind: String?, title: String?, visualId: String?, text: String?): SurfaceVisual? {
            val k = when (kind?.trim()) {
                "desktop_screen" -> Kind.DESKTOP_SCREEN
                "image" -> Kind.IMAGE
                "note" -> Kind.NOTE
                else -> return null
            }
            val cleanTitle = title?.trim().orEmpty().take(MAX_TITLE)
            return when (k) {
                Kind.NOTE -> {
                    val body = text?.trim().orEmpty()
                    if (body.isEmpty()) return null
                    SurfaceVisual(k, cleanTitle.ifBlank { "From EVE" }, null, body.take(MAX_TEXT))
                }
                else -> {
                    val id = visualId?.trim().orEmpty()
                    if (!ID_RE.matches(id)) return null
                    SurfaceVisual(k, cleanTitle.ifBlank { defaultTitle(k) }, id, "")
                }
            }
        }

        private fun defaultTitle(k: Kind): String = when (k) {
            Kind.DESKTOP_SCREEN -> "Your desktop"
            Kind.IMAGE -> "Image"
            Kind.NOTE -> "From EVE"
        }
    }
}
