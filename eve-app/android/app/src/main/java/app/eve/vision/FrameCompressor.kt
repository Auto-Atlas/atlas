package app.eve.vision

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Turns a captured camera [Bitmap] into a small JPEG suitable for upload: longest edge clamped to
 * [MAX_EDGE_PX], re-encoded at [JPEG_QUALITY]. The server caps the decoded frame at 8 MB and we
 * target ≤1 MB, so a full-res phone photo (often 12 MP / several MB) MUST be downscaled first.
 *
 * The pure sizing maths ([scaledSize]) is split out so it is unit-testable with no graphics stack;
 * the actual bitmap ops need Android (Robolectric with native graphics in tests).
 */
object FrameCompressor {
    const val MAX_EDGE_PX = 1280
    const val JPEG_QUALITY = 80

    /** width/height of a captured JPEG frame — one plane of packed bytes. */
    data class Size(val width: Int, val height: Int)

    /**
     * Target dimensions that fit [src] within a [maxEdge]×[maxEdge] box, preserving aspect ratio.
     * Never upscales (an already-small frame is returned unchanged) and never returns a zero edge.
     * Pure integer maths — no Android types — so the downscale contract is testable directly.
     */
    fun scaledSize(src: Size, maxEdge: Int = MAX_EDGE_PX): Size {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxEdge || longest == 0) return src
        val ratio = maxEdge.toDouble() / longest.toDouble()
        val w = (src.width * ratio).toInt().coerceAtLeast(1)
        val h = (src.height * ratio).toInt().coerceAtLeast(1)
        return Size(w, h)
    }

    /**
     * Downscales + rotates [bitmap] and encodes it as JPEG bytes. [rotationDegrees] is CameraX's
     * `ImageInfo.rotationDegrees` — the frame is baked upright so EVE sees it the way the user does.
     */
    fun toJpeg(
        bitmap: Bitmap,
        rotationDegrees: Int = 0,
        maxEdge: Int = MAX_EDGE_PX,
        quality: Int = JPEG_QUALITY,
    ): ByteArray {
        val target = scaledSize(Size(bitmap.width, bitmap.height), maxEdge)
        val prepared = if (target.width != bitmap.width || target.height != bitmap.height ||
            rotationDegrees % 360 != 0
        ) {
            val matrix = Matrix().apply {
                if (bitmap.width > 0 && bitmap.height > 0) {
                    postScale(
                        target.width.toFloat() / bitmap.width.toFloat(),
                        target.height.toFloat() / bitmap.height.toFloat(),
                    )
                }
                if (rotationDegrees % 360 != 0) postRotate(rotationDegrees.toFloat())
            }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }
        return ByteArrayOutputStream().use { out ->
            prepared.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }
    }

    /** [toJpeg] then base64 (NO_WRAP — the server does plain `b64decode`, no newlines). */
    fun toJpegBase64(
        bitmap: Bitmap,
        rotationDegrees: Int = 0,
        maxEdge: Int = MAX_EDGE_PX,
        quality: Int = JPEG_QUALITY,
    ): String = Base64.encodeToString(toJpeg(bitmap, rotationDegrees, maxEdge, quality), Base64.NO_WRAP)
}
