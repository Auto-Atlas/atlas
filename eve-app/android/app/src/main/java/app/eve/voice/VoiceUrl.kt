package app.eve.voice

import java.net.URI

/**
 * Pure derivation of the voice (phone_bot) base URL. The voice endpoint is a DIFFERENT
 * host:port than the approval API (approval = :8443; phone_bot = :8444 over `tailscale serve`),
 * so we either take an explicit override verbatim or rebuild `scheme://host:voicePort` from the
 * approval base.
 *
 * Uses `java.net.URI` (NOT `android.net.Uri`) so it runs on the plain JVM under unit test.
 *
 * @param approvalBase the configured approval API base (e.g. https://host.ts.net:8443)
 * @param override     an explicit voice_url_override; wins verbatim when non-blank
 * @param voicePort    the phone_bot port (default 8444, the tailscale-serve voice mapping)
 * @return the effective voice URL, or null when nothing usable is configured (drives the
 *         "not configured" UI state).
 */
fun deriveVoiceUrl(approvalBase: String, override: String, voicePort: Int = 8444): String? {
    val trimmedOverride = override.trim()
    if (trimmedOverride.isNotBlank()) return trimmedOverride

    val base = approvalBase.trim()
    if (base.isBlank()) return null

    return try {
        val uri = URI(base)
        val scheme = uri.scheme ?: return null
        val host = uri.host ?: return null
        "$scheme://$host:$voicePort"
    } catch (_: Exception) {
        null
    }
}

/**
 * Pure derivation of the WATCH voice-door URL — the public wss endpoint the wrist streams to
 * (watch_bot behind `tailscale funnel --https=10000`; 10000 is one of Funnel's three allowed
 * ports and the documented Atlas convention). Pairing is automatic: same host as the approval
 * base, fixed door port and path — the user configures NOTHING unless they set an override.
 *
 * @param approvalBase the configured approval API base (e.g. https://host.ts.net:8443)
 * @param override     an explicit door URL; wins verbatim when non-blank (custom setups)
 * @return the effective wss door URL, or null when nothing usable is configured (the watch
 *         shows its named "not configured" state).
 */
fun deriveWatchVoiceDoorUrl(approvalBase: String, override: String, doorPort: Int = 10000): String? {
    val trimmedOverride = override.trim()
    if (trimmedOverride.isNotBlank()) return trimmedOverride

    val base = approvalBase.trim()
    if (base.isBlank()) return null

    return try {
        val host = URI(base).host ?: return null
        "wss://$host:$doorPort/v1/watch/voice"
    } catch (_: Exception) {
        null
    }
}
