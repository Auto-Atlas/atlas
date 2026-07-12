package app.eve.glasses

/**
 * The DEFAULT bound [GlassesCameraSource] while the DAT SDK isn't compiled in. It is honest: the
 * toolkit is not available, nothing is ever connected, and every capture returns [GlassesCaptureResult.Err].
 * The router therefore turns any explicit glasses request into an honest error (never a phone
 * fallback), and an "any" request always resolves to the phone — the exact behaviour we want until
 * real glasses + the real toolkit are wired in.
 */
class StubGlassesCameraSource : GlassesCameraSource {
    override val isToolkitAvailable: Boolean = GlassesToolkit.IS_BUNDLED // false today
    override val isConnected: Boolean = false

    override suspend fun capture(prompt: String): GlassesCaptureResult =
        GlassesCaptureResult.Err(
            "Meta glasses toolkit not bundled in this build " +
                "(DAT ${GlassesToolkit.TARGET_SDK_VERSION} is a token-gated dev preview).",
        )
}
