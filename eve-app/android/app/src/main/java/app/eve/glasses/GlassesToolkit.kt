package app.eve.glasses

/**
 * Single source of truth for whether the **Meta Wearables Device Access Toolkit** (DAT) is bundled
 * into this build. It is NOT, today: the SDK ships only as a token-gated GitHub Packages developer
 * preview and cannot be shipped in a public app yet (GA slated 2026). See build.gradle.kts for the
 * exact commented-out coordinates + repo, and glasses/gated/ for the code-complete real integration.
 *
 * Facts (researched 2026-07-05, all under https://wearables.developer.meta.com/docs):
 *  - Repo:  https://maven.pkg.github.com/facebook/meta-wearables-dat-android  (GitHub Packages)
 *  - Coords: com.meta.wearable:mwdat-core:0.8.0, :mwdat-camera:0.8.0, :mwdat-mockdevice:0.8.0
 *  - Auth:  requires a GitHub PAT with read:packages — NOT anonymously resolvable.
 *  - Broker: the Meta AI app mediates pairing + per-app authorization (developer mode enrollment).
 *  - Camera: Wearables.createSession() → DeviceSession.addStream() → Stream.capturePhoto()/videoStream.
 *  - Audio:  NO toolkit audio API — TTS plays out the glasses as a standard Bluetooth A2DP/SCO
 *            device via AudioManager.setCommunicationDevice(...). See [GlassesAudioRouter].
 *  - Devices: Ray-Ban Meta Gen 1/2, Oakley Meta HSTN today; Oakley Vanguard / RB Display coming.
 *
 * When the SDK is wired in (dependency uncommented, gated sources promoted), flip [IS_BUNDLED] via
 * the real impls' [GlassesCameraSource.isToolkitAvailable] — this constant stays the honest default.
 */
object GlassesToolkit {
    /** False until the DAT dependency + gated sources are actually compiled into the app. */
    const val IS_BUNDLED: Boolean = false

    /** The DAT SDK version the gated integration targets (kept in lockstep with build.gradle.kts). */
    const val TARGET_SDK_VERSION: String = "0.8.0"

    /** Minimum Android OS the toolkit supports (API 29). The app's own minSdk is lower (26). */
    const val MIN_ANDROID_API: Int = 29
}
