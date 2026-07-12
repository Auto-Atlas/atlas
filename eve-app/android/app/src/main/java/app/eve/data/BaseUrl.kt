package app.eve.data

import java.net.URI

/**
 * Pure validation/normalization of the Atlas approval API base URL.
 *
 * A base URL reaches three throwing call sites once saved — Ktor's `takeFrom` in [ApiClient]
 * and the `URLBuilder().takeFrom(...).build()` in [StreamClient] (which then feeds
 * [app.eve.push.StreamService]). Garbage like a double scheme (`https://https://host`), a
 * trailing space, or a host-less `http://` makes those throw. ApiClient catches its own throw;
 * StreamService's reconnect loop would otherwise crash-loop the foreground service. The real fix
 * is to never SAVE such input — this validator is the gate on the Connect screen, and is reused
 * by the resilience layer to fail fast and honestly.
 *
 * Uses `java.net.URI` (NOT `android.net.Uri`) so it runs on the plain JVM under unit test.
 */
object BaseUrl {

    /**
     * Validate and normalize a candidate base URL.
     *
     * @return the trimmed, trailing-slash-stripped URL if it is a real http/https URL with a
     *         non-empty host; otherwise null (caller surfaces an inline error / offline state).
     */
    fun normalize(raw: String): String? {
        // Strip leading/trailing whitespace AND any stray control chars (a soft-keyboard can
        // append a trailing newline or non-breaking space that `trim()` alone may miss).
        val trimmed = raw.trim().trim(' ', '\uFEFF', '\u200B')
        if (trimmed.isBlank()) return null
        // Reject anything with internal whitespace ("http://h ost", "https:// host").
        if (trimmed.any { it.isWhitespace() }) return null

        val scheme = when {
            trimmed.startsWith("https://", ignoreCase = true) -> "https"
            trimmed.startsWith("http://", ignoreCase = true) -> "http"
            else -> return null
        }

        // Catch a double scheme (`https://https://host`) before URI hides it inside the path.
        val afterScheme = trimmed.substringAfter("://")
        if (afterScheme.startsWith("http://", ignoreCase = true) ||
            afterScheme.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }

        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return null
        }
        if (uri.scheme == null || !uri.scheme.equals(scheme, ignoreCase = true)) return null

        // A real host is required; `http://` alone has a null host. Some hosts (and ports on
        // certain runtimes) leave `uri.host` null while `uri.authority` is populated — fall back
        // to deriving the host from the authority (strip userinfo + `:port`) so a valid tailnet
        // address like `host.ts.net:8443` is never wrongly rejected.
        val host = uri.host ?: uri.authority
            ?.substringAfterLast('@')
            ?.substringBefore(':')
        if (host.isNullOrBlank()) return null

        return trimmed.trimEnd('/')
    }

    /** True when [raw] is a savable, well-formed base URL. */
    fun isValid(raw: String): Boolean = normalize(raw) != null
}
