package app.eve.vision

/**
 * Which camera a `capture_frame` should come from. The WS event now carries a `source` field
 * ("any" | "phone" | "glasses"); this normalizes it. A missing/blank/unknown value is the tolerant
 * default [ANY] so an older server (no source field) keeps working exactly as before — the phone.
 *
 *  - [ANY] — EVE doesn't care; prefer the glasses when they're enabled + connected, else the phone.
 *  - [PHONE] — the phone camera specifically. Glasses are irrelevant to this request.
 *  - [GLASSES] — the Meta glasses specifically. If glasses are off/not connected this is an honest
 *    ERROR (see [CaptureRouter]); we do NOT silently fall back to the phone for an explicit glasses
 *    request — that would send EVE a picture from the wrong camera.
 */
enum class CaptureSource {
    ANY,
    PHONE,
    GLASSES,
    ;

    companion object {
        fun fromWire(raw: String?): CaptureSource = when (raw?.trim()?.lowercase()) {
            "glasses" -> GLASSES
            "phone" -> PHONE
            // "any", "", null, or anything unmodeled → the tolerant default.
            else -> ANY
        }
    }
}

/** The resolved destination for a capture, after applying the toggle + connection state. */
enum class CaptureRoute {
    /** Snap with the phone camera (existing look_via_phone path). */
    PHONE,

    /** Snap with the Meta glasses camera via the Wearables Device Access Toolkit. */
    GLASSES,

    /**
     * An explicit glasses request that cannot be honoured (toggle off, or glasses not connected).
     * The controller logs an honest failure and captures NOTHING — the server's own timeout tells
     * EVE, and we never substitute a phone frame for a glasses request.
     */
    ERROR_GLASSES_UNAVAILABLE,
}

/**
 * Pure routing decision for a capture_frame — no Android types, so the whole matrix is unit-testable
 * on the JVM. This is the single source of truth for "phone events never hit the glasses source" and
 * "an explicit glasses request never falls back to the phone".
 */
object CaptureRouter {
    fun route(
        source: CaptureSource,
        glassesEnabled: Boolean,
        glassesConnected: Boolean,
    ): CaptureRoute {
        val glassesReady = glassesEnabled && glassesConnected
        return when (source) {
            CaptureSource.PHONE -> CaptureRoute.PHONE
            CaptureSource.GLASSES ->
                if (glassesReady) CaptureRoute.GLASSES else CaptureRoute.ERROR_GLASSES_UNAVAILABLE
            CaptureSource.ANY ->
                if (glassesReady) CaptureRoute.GLASSES else CaptureRoute.PHONE
        }
    }
}
