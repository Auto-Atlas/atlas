package app.eve.vision

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// (source-parsing assertions below)

/**
 * Pure-JVM guard on the `capture_frame` payload contract (no Android runtime). The id must be plain
 * lowercase hex 8-32 chars (mirrors the server's vision_frames.valid_id) — anything else is dropped
 * BEFORE the camera runs, and a missing/blank prompt is fine (EVE may just want "a look").
 */
class CaptureRequestTest {

    @Test
    fun `valid id and prompt parse`() {
        val r = CaptureRequest.parse("a1b2c3d4", "what plant is this")
        assertNotNull(r)
        assertEquals("a1b2c3d4", r.requestId)
        assertEquals("what plant is this", r.prompt)
    }

    @Test
    fun `blank or missing prompt becomes empty string`() {
        assertEquals("", CaptureRequest.parse("a1b2c3d4e5f6", null)?.prompt)
        assertEquals("", CaptureRequest.parse("a1b2c3d4e5f6", "   ")?.prompt)
    }

    @Test
    fun `prompt is trimmed`() {
        assertEquals("read this", CaptureRequest.parse("deadbeef", "  read this  ")?.prompt)
    }

    @Test
    fun `missing id is rejected`() {
        assertNull(CaptureRequest.parse(null, "x"))
        assertNull(CaptureRequest.parse("", "x"))
        assertNull(CaptureRequest.parse("   ", "x"))
    }

    @Test
    fun `too short or too long id is rejected`() {
        assertNull(CaptureRequest.parse("a1b2c3", "x")) // 6 < 8
        assertNull(CaptureRequest.parse("a".repeat(33), "x")) // 33 > 32
    }

    @Test
    fun `boundary lengths are accepted`() {
        assertNotNull(CaptureRequest.parse("a".repeat(8), "x"))
        assertNotNull(CaptureRequest.parse("a".repeat(32), "x"))
    }

    @Test
    fun `source defaults to any and parses the wire field`() {
        assertEquals(CaptureSource.ANY, CaptureRequest.parse("a1b2c3d4", "x")?.source)
        assertEquals(CaptureSource.ANY, CaptureRequest.parse("a1b2c3d4", "x", null)?.source)
        assertEquals(CaptureSource.GLASSES, CaptureRequest.parse("a1b2c3d4", "x", "glasses")?.source)
        assertEquals(CaptureSource.PHONE, CaptureRequest.parse("a1b2c3d4", "x", "phone")?.source)
        assertEquals(CaptureSource.ANY, CaptureRequest.parse("a1b2c3d4", "x", "any")?.source)
        // unknown/future source is tolerated as ANY, not a rejection
        assertEquals(CaptureSource.ANY, CaptureRequest.parse("a1b2c3d4", "x", "hologram")?.source)
    }

    @Test
    fun `non-hex or uppercase id is rejected`() {
        assertNull(CaptureRequest.parse("A1B2C3D4", "x")) // uppercase
        assertNull(CaptureRequest.parse("g1h2i3j4", "x")) // non-hex letters
        assertNull(CaptureRequest.parse("a1b2-c3d4", "x")) // punctuation
        assertNull(CaptureRequest.parse("a1b2 c3d4", "x")) // internal space
    }
}
