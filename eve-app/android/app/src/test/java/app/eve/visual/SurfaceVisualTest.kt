package app.eve.visual

import app.eve.ASSISTANT_NAME
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-JVM guard on the `surface_visual` card contract (no Android runtime). A card that can't be
 * shown — unknown kind, image kind with a missing/bad `visual_id`, or an empty note — is dropped
 * BEFORE the UI ever tries to render it, mirroring visual_tool.py + visual_store.valid_id.
 */
class SurfaceVisualTest {

    @Test
    fun `desktop_screen with a valid hex id parses as an image card`() {
        val v = SurfaceVisual.parse("desktop_screen", "Your desktop right now", "0123456789abcdef", "")
        assertNotNull(v)
        assertEquals(SurfaceVisual.Kind.DESKTOP_SCREEN, v.kind)
        assertEquals("Your desktop right now", v.title)
        assertEquals("0123456789abcdef", v.visualId)
        assertTrue(v.isImage)
        assertEquals("", v.text)
    }

    @Test
    fun `image kind parses and carries no text`() {
        val v = SurfaceVisual.parse("image", "chart.png", "deadbeefcafe", "ignored maybe")
        assertNotNull(v)
        assertEquals(SurfaceVisual.Kind.IMAGE, v.kind)
        assertEquals("deadbeefcafe", v.visualId)
        assertTrue(v.isImage)
        assertEquals("", v.text)
    }

    @Test
    fun `note kind parses with text and no visual id`() {
        val v = SurfaceVisual.parse("note", "Build log", "", "line1\nline2")
        assertNotNull(v)
        assertEquals(SurfaceVisual.Kind.NOTE, v.kind)
        assertNull(v.visualId)
        assertTrue(!v.isImage)
        assertEquals("line1\nline2", v.text)
    }

    @Test
    fun `unknown or blank kind is ignored`() {
        assertNull(SurfaceVisual.parse(null, "t", "deadbeef", ""))
        assertNull(SurfaceVisual.parse("", "t", "deadbeef", ""))
        assertNull(SurfaceVisual.parse("video", "t", "deadbeef", ""))
        assertNull(SurfaceVisual.parse("DESKTOP_SCREEN", "t", "deadbeef", "")) // case-sensitive by contract
    }

    @Test
    fun `image kind with missing or non-hex id is ignored`() {
        assertNull(SurfaceVisual.parse("image", "t", null, ""))
        assertNull(SurfaceVisual.parse("image", "t", "", ""))
        assertNull(SurfaceVisual.parse("image", "t", "NOTHEX", "")) // uppercase / non-hex
        assertNull(SurfaceVisual.parse("image", "t", "abc", "")) // 3 < 8
        assertNull(SurfaceVisual.parse("desktop_screen", "t", "a".repeat(33), "")) // 33 > 32
        assertNull(SurfaceVisual.parse("image", "t", "dead beef", "")) // internal space
    }

    @Test
    fun `note with empty or blank text is ignored`() {
        assertNull(SurfaceVisual.parse("note", "t", "", null))
        assertNull(SurfaceVisual.parse("note", "t", "", ""))
        assertNull(SurfaceVisual.parse("note", "t", "", "   "))
    }

    @Test
    fun `blank title falls back to a per-kind default`() {
        assertEquals("Your desktop", SurfaceVisual.parse("desktop_screen", "", "deadbeef", "")?.title)
        assertEquals("Image", SurfaceVisual.parse("image", "  ", "deadbeef", "")?.title)
        assertEquals("From $ASSISTANT_NAME", SurfaceVisual.parse("note", "", "", "hi")?.title)
    }

    @Test
    fun `id and text are trimmed`() {
        val img = SurfaceVisual.parse("image", "t", "  deadbeef  ", "")
        assertEquals("deadbeef", img?.visualId)
        val note = SurfaceVisual.parse("note", "t", "", "  hello  ")
        assertEquals("hello", note?.text)
    }
}
