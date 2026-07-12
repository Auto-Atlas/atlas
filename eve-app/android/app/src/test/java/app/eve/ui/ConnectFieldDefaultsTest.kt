package app.eve.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the fresh-install prefill behaviour: with no saved value the field shows the convenience
 * default; with a saved non-blank value that value wins (defaults never clobber it). Blank/whitespace
 * and null saved values are all treated as "nothing persisted".
 */
class ConnectFieldDefaultsTest {

    private val default = "https://host.example.ts.net:8443"

    @Test
    fun nullSaved_usesDefault() {
        assertEquals(default, initialFieldValue(null, default))
    }

    @Test
    fun blankSaved_usesDefault() {
        assertEquals(default, initialFieldValue("", default))
    }

    @Test
    fun whitespaceSaved_usesDefault() {
        assertEquals(default, initialFieldValue("   ", default))
    }

    @Test
    fun nonBlankSaved_winsOverDefault() {
        assertEquals("https://saved.example:9000", initialFieldValue("https://saved.example:9000", default))
    }
}
