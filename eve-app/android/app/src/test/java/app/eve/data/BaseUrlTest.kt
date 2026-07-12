package app.eve.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure JVM tests for the base-URL gate. These pin the crash-bug repro cases: a double scheme,
 * blank/whitespace, plain garbage, and a host-less scheme are all rejected (so they never reach
 * Ktor's throwing `takeFrom`), while real tailnet/emulator URLs are accepted and normalized.
 */
class BaseUrlTest {

    @Test
    fun rejects_double_scheme() {
        assertNull(BaseUrl.normalize("https://https://x"))
        assertFalse(BaseUrl.isValid("https://https://x"))
    }

    @Test
    fun rejects_whitespace_only() {
        assertNull(BaseUrl.normalize("  "))
    }

    @Test
    fun rejects_internal_whitespace() {
        assertNull(BaseUrl.normalize("https://h ost.ts.net:8443"))
    }

    @Test
    fun rejects_non_url() {
        assertNull(BaseUrl.normalize("notaurl"))
    }

    @Test
    fun rejects_scheme_without_host() {
        assertNull(BaseUrl.normalize("http://"))
        assertNull(BaseUrl.normalize("https://"))
    }

    @Test
    fun accepts_tailnet_https_with_port() {
        assertEquals(
            "https://host.example.ts.net:8443",
            BaseUrl.normalize("https://host.example.ts.net:8443"),
        )
        assertTrue(BaseUrl.isValid("https://host.example.ts.net:8443"))
    }

    @Test
    fun accepts_exact_failing_device_url_regression() {
        // Regression: this EXACT string was reported rejected on-device with a valid-URL error.
        val input = "https://host.example.ts.net:8443"
        assertEquals(input, BaseUrl.normalize(input))
        assertTrue(BaseUrl.isValid(input))
        // Same address with a trailing newline a soft keyboard may append must still pass.
        assertEquals(input, BaseUrl.normalize("$input\n"))
    }

    @Test
    fun accepts_host_without_port() {
        assertEquals("https://host.ts.net", BaseUrl.normalize("https://host.ts.net"))
        assertTrue(BaseUrl.isValid("https://host.ts.net"))
    }

    @Test
    fun accepts_emulator_loopback_http() {
        assertEquals("http://10.0.2.2:8799", BaseUrl.normalize("http://10.0.2.2:8799"))
    }

    @Test
    fun trims_whitespace_and_trailing_slash() {
        assertEquals(
            "https://host.ts.net:8443",
            BaseUrl.normalize("  https://host.ts.net:8443/  "),
        )
    }
}
