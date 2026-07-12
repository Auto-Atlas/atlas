package app.eve.visual

import android.graphics.Bitmap
import app.eve.data.ApiClient
import app.eve.data.EveConnection
import app.eve.data.models.StreamEvent
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Drives the full surface_visual orchestration end-to-end with Robolectric's NATIVE graphics: a real
 * JPEG comes back over Ktor's MockEngine and VisualHub decodes it with the real BitmapFactory path.
 * Only the wire (MockEngine) is faked — the parse, fetch, decode and state transitions are the app's
 * real code. The hub is fire-and-forget on its own (real) coroutine scope, so we poll the StateFlow
 * to its terminal image state rather than driving a virtual clock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VisualHubTest {

    private val conn: suspend () -> EveConnection = {
        EveConnection(baseUrl = "https://host.ts.net:8443", token = "secret-token-1234567890123456")
    }

    private fun realJpeg(): ByteArray {
        val bmp = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return out.toByteArray()
    }

    private fun hub(engine: MockEngine) = VisualHub(ApiClient(engine = engine, connection = conn))

    private fun event(kind: String, title: String, visualId: String, text: String) = StreamEvent(
        type = StreamEvent.TYPE_SURFACE_VISUAL,
        kind = kind,
        title = title,
        visualId = visualId,
        text = text,
    )

    /** Poll the hub to a terminal (non-Loading) image state, or fail after [timeoutMs]. */
    private fun awaitSettled(hub: VisualHub, timeoutMs: Long = 5_000): VisualCard {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val card = hub.state.value
            if (card != null && card.image !is ImageLoad.Loading) return card
            Thread.sleep(10)
        }
        error("visual never settled: ${hub.state.value?.image}")
    }

    @Test
    fun image_event_fetches_decodes_and_lands_on_loaded() {
        val jpeg = realJpeg()
        val engine = MockEngine {
            respond(jpeg, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/jpeg"))
        }
        val hub = hub(engine)

        hub.onEvent(event("image", "chart.png", "0123456789abcdef", ""))
        val card = awaitSettled(hub)

        assertEquals("chart.png", card.visual.title)
        val loaded = assertIs<ImageLoad.Loaded>(card.image)
        assert(loaded.bitmap.width > 0)
    }

    @Test
    fun expired_image_maps_404_to_expired() {
        val engine = MockEngine { respond("gone", HttpStatusCode.NotFound) }
        val hub = hub(engine)

        hub.onEvent(event("desktop_screen", "Your desktop", "deadbeefdeadbeef", ""))

        assertIs<ImageLoad.Expired>(awaitSettled(hub).image)
    }

    @Test
    fun note_event_shows_immediately_without_a_fetch() {
        var hits = 0
        val engine = MockEngine { hits++; respond("x", HttpStatusCode.OK) }
        val hub = hub(engine)

        hub.onEvent(event("note", "Build log", "", "compilation failed"))
        val card = awaitSettled(hub)

        assertIs<ImageLoad.NoImage>(card.image)
        assertEquals("compilation failed", card.visual.text)
        assertEquals(0, hits, "a note must never hit the image endpoint")
    }

    @Test
    fun malformed_event_is_ignored_and_leaves_state_null() {
        val engine = MockEngine { respond("x", HttpStatusCode.OK) }
        val hub = hub(engine)

        hub.onEvent(event("video", "nope", "deadbeef", "")) // bad kind
        hub.onEvent(event("image", "nope", "NOTHEX", "")) // bad id
        hub.onEvent(StreamEvent(type = "thinking")) // not a visual at all

        assertNull(hub.state.value)
    }

    @Test
    fun a_newer_visual_replaces_the_previous_and_dismiss_clears() {
        val engine = MockEngine {
            respond(realJpeg(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/jpeg"))
        }
        val hub = hub(engine)

        hub.onEvent(event("image", "first", "aaaaaaaaaaaaaaaa", ""))
        hub.onEvent(event("note", "second", "", "later note")) // replaces the image synchronously

        val card = awaitSettled(hub)
        assertEquals("second", card.visual.title)
        assertIs<ImageLoad.NoImage>(card.image)

        hub.dismiss()
        assertNull(hub.state.value)
    }
}
