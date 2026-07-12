package app.eve.vision

import app.eve.vision.FrameCompressor.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure sizing maths for the downscale contract (longest edge ≤ 1280, aspect preserved, never
 * upscale) — no graphics stack needed, so the resize policy is verified directly.
 */
class FrameCompressorScaleTest {

    @Test
    fun `landscape shrinks longest edge to the cap`() {
        val out = FrameCompressor.scaledSize(Size(4000, 3000), maxEdge = 1280)
        assertEquals(1280, out.width)
        assertEquals(960, out.height) // 3000 * (1280/4000)
    }

    @Test
    fun `portrait shrinks longest edge to the cap`() {
        val out = FrameCompressor.scaledSize(Size(3000, 4000), maxEdge = 1280)
        assertEquals(1280, out.height)
        assertEquals(960, out.width)
    }

    @Test
    fun `square shrinks both edges to the cap`() {
        val out = FrameCompressor.scaledSize(Size(2000, 2000), maxEdge = 1280)
        assertEquals(1280, out.width)
        assertEquals(1280, out.height)
    }

    @Test
    fun `already-small frame is not upscaled`() {
        val out = FrameCompressor.scaledSize(Size(800, 600), maxEdge = 1280)
        assertEquals(Size(800, 600), out)
    }

    @Test
    fun `exactly at the cap is unchanged`() {
        val out = FrameCompressor.scaledSize(Size(1280, 720), maxEdge = 1280)
        assertEquals(Size(1280, 720), out)
    }

    @Test
    fun `no edge collapses to zero`() {
        val out = FrameCompressor.scaledSize(Size(5000, 3), maxEdge = 1280)
        assertTrue(out.width in 1..1280)
        assertTrue(out.height >= 1)
    }
}
