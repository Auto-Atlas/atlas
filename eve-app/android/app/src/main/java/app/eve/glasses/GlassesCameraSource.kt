package app.eve.glasses

/**
 * A camera that lives on the user's Meta glasses (Ray-Ban Meta Gen 1/2, Oakley Meta HSTN, …),
 * reached through Meta's **Wearables Device Access Toolkit** (DAT). This is the seam the vision
 * capture path routes to when a `capture_frame` event is sourced from the glasses.
 *
 * Two implementations back it:
 *  - [StubGlassesCameraSource] — the DEFAULT bound impl. The DAT artifact is a token-gated developer
 *    preview (see build.gradle.kts + [GlassesToolkit]); until it's bundled, this reports
 *    not-available / not-connected and every capture is an honest error.
 *  - `RealGlassesCameraSource` (glasses/gated/RealGlassesCameraSource.kt.gated) — the code-complete
 *    DAT-backed impl, EXCLUDED from compilation until the SDK is wired in. See that file's header.
 *
 * The interface is pure Kotlin (no Android/DAT types) so the routing that depends on it is testable
 * on the JVM with a fake.
 */
interface GlassesCameraSource {
    /**
     * Whether the DAT SDK is actually bundled into this build. The stub returns false so the UI can
     * tell the user "toolkit not bundled" honestly instead of pretending the feature is one toggle
     * away. The real impl returns true.
     */
    val isToolkitAvailable: Boolean

    /**
     * Whether glasses are paired + a DAT session is live right now (camera reachable). Always false
     * on the stub. The router treats a glasses request with `isConnected == false` as an error, never
     * a phone fallback.
     */
    val isConnected: Boolean

    /**
     * Capture ONE frame from the glasses and return it as an upload-ready base64 JPEG (NO_WRAP, the
     * same shape the phone path uploads). [prompt] is Atlas's "what I want to see" hint — passed
     * through for any on-glasses/on-phone indicator, never validated.
     */
    suspend fun capture(prompt: String): GlassesCaptureResult
}

/** Result of a glasses capture: an upload-ready base64 JPEG, or an honest failure reason. */
sealed interface GlassesCaptureResult {
    /** [jpegBase64] is a base64-encoded JPEG (NO_WRAP) ready for POST /v1/vision/frame. */
    data class Ok(val jpegBase64: String) : GlassesCaptureResult

    /** Human-readable, log-only reason the capture couldn't happen (no server error channel exists). */
    data class Err(val reason: String) : GlassesCaptureResult
}
