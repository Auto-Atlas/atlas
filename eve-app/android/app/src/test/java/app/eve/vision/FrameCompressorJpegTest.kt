package app.eve.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Verifies the real encode path with Robolectric's NATIVE graphics: a large captured Bitmap is
 * downscaled (longest edge ≤ 1280, aspect kept) and re-encoded as an actual JPEG (0xFF 0xD8 magic).
 * We don't test CameraX itself here — only the compress helper it hands the frame to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FrameCompressorJpegTest {

    @Test
    fun `downscales a large frame and emits real jpeg bytes`() {
        val src = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888)

        val jpeg = FrameCompressor.toJpeg(src, rotationDegrees = 0, maxEdge = 1280, quality = 80)

        // JPEG SOI magic bytes.
        assertTrue("expected non-empty output", jpeg.size > 2)
        assertEquals("byte0 should be 0xFF", 0xFF.toByte(), jpeg[0])
        assertEquals("byte1 should be 0xD8", 0xD8.toByte(), jpeg[1])

        // Decode back and confirm the frame was actually downscaled within the 1280 box.
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        assertTrue("decoded should be within the cap", maxOf(decoded.width, decoded.height) <= 1280)
        assertEquals("longest edge should hit the cap", 1280, maxOf(decoded.width, decoded.height))
        assertEquals("aspect ratio preserved (4:3 -> 1280x960)", 960, minOf(decoded.width, decoded.height))
    }

    @Test
    fun `small frame is not upscaled and still encodes to jpeg`() {
        val src = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)

        val jpeg = FrameCompressor.toJpeg(src, rotationDegrees = 90, maxEdge = 1280, quality = 80)

        assertEquals(0xFF.toByte(), jpeg[0])
        assertEquals(0xD8.toByte(), jpeg[1])
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        // 90° rotation swaps the axes but must not enlarge the longest edge past the source's 640.
        assertTrue(maxOf(decoded.width, decoded.height) <= 640)
    }
}
