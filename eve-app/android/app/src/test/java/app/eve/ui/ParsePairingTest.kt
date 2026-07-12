package app.eve.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The pure pairing-payload parser (the QR Atlas shows). JVM-testable — no android.net.Uri. */
class ParsePairingTest {

    @Test
    fun parses_a_valid_eve_payload() {
        val raw = "eve://connect?base=https%3A%2F%2Fhost.ts.net%3A8443&token=tok-123"
        val c = parsePairingPayload(raw)
        assertEquals("https://host.ts.net:8443", c?.baseUrl)
        assertEquals("tok-123", c?.token)
    }

    @Test
    fun decodes_a_token_with_special_chars() {
        val raw = "eve://connect?base=https%3A%2F%2Fh%3A8443&token=a%2Fb%2Bc%3Dd"
        assertEquals("a/b+c=d", parsePairingPayload(raw)?.token)
    }

    @Test
    fun rejects_a_non_eve_scheme() {
        assertNull(parsePairingPayload("https://evil.example?base=x&token=y"))
    }

    @Test
    fun rejects_a_missing_token() {
        assertNull(parsePairingPayload("eve://connect?base=https%3A%2F%2Fh%3A8443"))
    }

    @Test
    fun rejects_garbage() {
        assertNull(parsePairingPayload("not a uri at all"))
        assertNull(parsePairingPayload(""))
    }
}
