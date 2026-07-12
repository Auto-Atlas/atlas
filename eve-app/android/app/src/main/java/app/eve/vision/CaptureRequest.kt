package app.eve.vision

/**
 * A validated `capture_frame` request from the WS stream. The server sends
 * `{"type":"capture_frame","request_id":"<8-32 lowercase hex>","prompt":"<may be empty>"}`; this
 * mirrors `vision_frames.valid_id` so a hostile or malformed id is rejected on the phone before we
 * ever touch the camera or echo the id back to `/v1/vision/frame`.
 *
 * [prompt] is optional by contract (Atlas may just want "a look") — it is only used for the on-screen
 * "Atlas is looking…" indicator, never validated for content.
 */
data class CaptureRequest(
    val requestId: String,
    val prompt: String,
    /** Which camera Atlas wants this frame from — see [CaptureSource]. Defaults to [CaptureSource.ANY]. */
    val source: CaptureSource = CaptureSource.ANY,
) {
    companion object {
        // Must match approval_api's VisionFrame / vision_frames.valid_id contract exactly.
        private val ID_RE = Regex("^[a-f0-9]{8,32}$")

        /**
         * Parses a raw stream event's fields into a [CaptureRequest], or null if the id is missing
         * or not plain lowercase hex (8-32 chars). A missing/blank prompt is fine → empty string.
         * [source] is the wire "source" field ("any"|"phone"|"glasses"); missing/unknown → ANY.
         * Pure and side-effect-free so the validation is unit-testable without any Android runtime.
         */
        fun parse(requestId: String?, prompt: String?, source: String? = null): CaptureRequest? {
            val id = requestId?.trim().orEmpty()
            if (!ID_RE.matches(id)) return null
            return CaptureRequest(
                requestId = id,
                prompt = prompt?.trim().orEmpty(),
                source = CaptureSource.fromWire(source),
            )
        }
    }
}
