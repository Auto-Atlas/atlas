package app.eve.vision

import android.content.Context
import app.eve.data.ApiClient
import app.eve.data.EveConnection
import app.eve.glasses.GlassesCameraSource
import app.eve.glasses.GlassesCaptureResult
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end routing at the controller level (Robolectric only for a real [Context]; the capture
 * runs on an unconfined scope and we bound-wait for the async upload). Proves: a glasses-sourced
 * event captures from the glasses source and uploads the EXACT `/v1/vision/frame` body
 * ({request_id, jpeg_b64}); a glasses request with the toggle off (or glasses disconnected) is an
 * honest error that never captures nor uploads; and a phone event never touches the glasses source.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FrameCaptureGlassesRoutingTest {

    private val ctx: Context = RuntimeEnvironment.getApplication()

    private val conn: suspend () -> EveConnection = {
        EveConnection(baseUrl = "https://host.ts.net:8443", token = "secret-token-1234567890123456")
    }

    private class FakeGlasses(
        override val isConnected: Boolean,
        private val result: GlassesCaptureResult = GlassesCaptureResult.Ok(GLASSES_B64),
    ) : GlassesCameraSource {
        override val isToolkitAvailable: Boolean = true
        @Volatile var captureCalls: Int = 0
        @Volatile var lastPrompt: String? = null
        override suspend fun capture(prompt: String): GlassesCaptureResult {
            captureCalls++
            lastPrompt = prompt
            return result
        }
    }

    private class Recorder {
        @Volatile var posts = 0
        @Volatile var lastPath: String? = null
        @Volatile var lastBody: String? = null
        @Volatile var lastAuth: String? = null
    }

    private fun controller(
        rec: Recorder,
        glasses: GlassesCameraSource,
        glassesEnabled: Boolean,
    ): FrameCaptureController {
        val engine = MockEngine { request ->
            rec.lastPath = request.url.encodedPath
            rec.lastAuth = request.headers[HttpHeaders.Authorization]
            rec.lastBody = (request.body as? TextContent)?.text
            rec.posts++
            respond(
                content = """{"ok":true,"bytes":42}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val api = ApiClient(engine = engine, connection = conn)
        return FrameCaptureController(
            appContext = ctx,
            apiClient = api,
            currentActivity = { null }, // no foreground activity → phone path is a no-op (no upload)
            glassesSource = glasses,
            glassesEnabled = { glassesEnabled },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
    }

    /** Bounded wait for an async condition (the upload hops off onto ktor's dispatcher). */
    private fun waitUntil(timeoutMs: Long = 3_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !cond()) Thread.sleep(5)
    }

    /** Let any (non-)work settle, so a negative assertion isn't a race that just hasn't happened yet. */
    private fun settle() = Thread.sleep(150)

    @Test
    fun `glasses event uploads a frame from the glasses with the exact POST body`() {
        val rec = Recorder()
        val glasses = FakeGlasses(isConnected = true)
        val c = controller(rec, glasses, glassesEnabled = true)

        c.capture(CaptureRequest("deadbeef", "read the label", CaptureSource.GLASSES))
        waitUntil { rec.posts >= 1 }

        assertEquals(1, glasses.captureCalls, "glasses source must be used")
        assertEquals("read the label", glasses.lastPrompt)
        assertEquals(1, rec.posts, "exactly one upload")
        assertEquals("/v1/vision/frame", rec.lastPath)
        assertEquals("Bearer secret-token-1234567890123456", rec.lastAuth)
        val body = rec.lastBody ?: error("no request body captured")
        assertTrue(body.contains("\"request_id\":\"deadbeef\""), "body carries request_id: $body")
        assertTrue(body.contains("\"jpeg_b64\":\"$GLASSES_B64\""), "body carries the glasses frame b64: $body")
    }

    @Test
    fun `any event prefers glasses when enabled and connected`() {
        val rec = Recorder()
        val glasses = FakeGlasses(isConnected = true)
        val c = controller(rec, glasses, glassesEnabled = true)

        c.capture(CaptureRequest("deadbeef", "look", CaptureSource.ANY))
        waitUntil { rec.posts >= 1 }

        assertEquals(1, glasses.captureCalls, "any → glasses when available")
        assertEquals(1, rec.posts)
        assertEquals("/v1/vision/frame", rec.lastPath)
    }

    @Test
    fun `glasses event with toggle off is an honest error - no capture, no upload`() {
        val rec = Recorder()
        val glasses = FakeGlasses(isConnected = true)
        val c = controller(rec, glasses, glassesEnabled = false)

        c.capture(CaptureRequest("deadbeef", "look", CaptureSource.GLASSES))
        settle()

        assertEquals(0, glasses.captureCalls, "toggle off → glasses never captures")
        assertEquals(0, rec.posts, "toggle off → nothing uploaded (no phone fallback)")
    }

    @Test
    fun `glasses event while disconnected is an honest error - no capture, no upload`() {
        val rec = Recorder()
        val glasses = FakeGlasses(isConnected = false)
        val c = controller(rec, glasses, glassesEnabled = true)

        c.capture(CaptureRequest("deadbeef", "look", CaptureSource.GLASSES))
        settle()

        assertEquals(0, glasses.captureCalls)
        assertEquals(0, rec.posts)
    }

    @Test
    fun `phone event never touches the glasses source`() {
        val rec = Recorder()
        val glasses = FakeGlasses(isConnected = true)
        // Even with glasses enabled+connected, a PHONE-sourced event must not use the glasses.
        val c = controller(rec, glasses, glassesEnabled = true)

        c.capture(CaptureRequest("deadbeef", "look", CaptureSource.PHONE))
        settle()

        assertEquals(0, glasses.captureCalls, "phone event must never hit the glasses source")
        // No foreground activity → the phone path no-ops (no upload); the point is glasses stayed untouched.
        assertEquals(0, rec.posts)
        assertNull(rec.lastBody)
    }

    private companion object {
        // A tiny valid base64 payload standing in for a captured JPEG.
        const val GLASSES_B64 = "R0lGODlhAQABAAAAACH5BAEKAAEALAAAAAABAAEAAAICTAEAOw"
    }
}
