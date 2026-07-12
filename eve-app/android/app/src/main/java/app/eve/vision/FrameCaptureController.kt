package app.eve.vision

import app.eve.ASSISTANT_NAME
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import app.eve.data.ApiClient
import app.eve.data.ApiResult
import app.eve.data.models.StreamEvent
import app.eve.glasses.GlassesCameraSource
import app.eve.glasses.GlassesCaptureResult
import app.eve.glasses.StubGlassesCameraSource
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The look_via_phone capture side. On a `capture_frame` stream event the app snaps ONE still with
 * CameraX (back camera), downscales/compresses it, and uploads it to `/v1/vision/frame` — the whole
 * round-trip the voice loop is waiting on.
 *
 * Foreground-only BY DESIGN. If no activity is in the foreground, or CAMERA permission is missing
 * (and not granted in time after the prompt), we do NOTHING but log — the server times out
 * gracefully and tells the user to open the app. There is no background camera path, no notification
 * trampoline. Only ONE capture runs at a time ([InFlightGate]); overlapping events are dropped.
 *
 * The camera work binds to the current foreground activity's lifecycle, so it dies with the screen.
 */
class FrameCaptureController(
    private val appContext: Context,
    private val apiClient: ApiClient,
    /** The current foreground Activity, or null when the app isn't visible (see ForegroundActivityTracker). */
    private val currentActivity: () -> Activity?,
    private val gate: InFlightGate = InFlightGate(),
    /**
     * The Meta glasses camera (DAT), or the honest stub when the toolkit isn't bundled. Used only
     * when a capture routes to [CaptureRoute.GLASSES]; the phone path never touches it.
     */
    private val glassesSource: GlassesCameraSource = StubGlassesCameraSource(),
    /**
     * Snapshot read of the "Meta glasses" toggle (local, default false). Suspend so it can read
     * DataStore; called once per capture so a mid-flight toggle change is respected on the NEXT event.
     */
    private val glassesEnabled: suspend () -> Boolean = { false },
    // Camera + UI must touch the main thread; immediate so a call already on main runs inline.
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob()),
) {
    /** Entry point from the stream: ignores everything that isn't a well-formed capture_frame. */
    fun onEvent(event: StreamEvent) {
        if (!event.isCaptureFrame) return
        val request = CaptureRequest.parse(event.requestId, event.prompt, event.source)
        if (request == null) {
            Log.w(TAG, "capture_frame ignored: missing/invalid request_id")
            return
        }
        capture(request)
    }

    /** Kicks off a capture unless one is already in flight. Safe to call from any thread. */
    fun capture(request: CaptureRequest) {
        if (!gate.tryAcquire()) {
            Log.i(TAG, "capture_frame dropped: a capture is already in flight")
            return
        }
        scope.launch {
            try {
                dispatch(request)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // Honest failure path: log only. The server's 25s timeout is the real signal to Atlas.
                Log.w(TAG, "capture_frame failed for ${request.requestId}: ${t.message}", t)
            } finally {
                gate.release()
            }
        }
    }

    /**
     * Route the request to the right camera (pure [CaptureRouter]) and run it. An explicit glasses
     * request with glasses off/not connected is an HONEST error — we log and capture nothing, never
     * substituting a phone frame; a "phone" request never reaches the glasses source.
     */
    private suspend fun dispatch(request: CaptureRequest) {
        val route = CaptureRouter.route(
            source = request.source,
            glassesEnabled = glassesEnabled(),
            glassesConnected = glassesSource.isConnected,
        )
        when (route) {
            CaptureRoute.PHONE -> runCapture(request)
            CaptureRoute.GLASSES -> runGlassesCapture(request)
            CaptureRoute.ERROR_GLASSES_UNAVAILABLE ->
                Log.w(
                    TAG,
                    "capture_frame ${request.requestId}: glasses requested but " +
                        "toggle off or glasses not connected — NOT falling back to phone (server will time out).",
                )
        }
    }

    /** Snap one frame from the Meta glasses (DAT) and upload it — same POST body as the phone path. */
    private suspend fun runGlassesCapture(request: CaptureRequest) {
        when (val r = glassesSource.capture(request.prompt)) {
            is GlassesCaptureResult.Ok -> upload(request.requestId, r.jpegBase64)
            is GlassesCaptureResult.Err ->
                Log.w(TAG, "capture_frame glasses failed for ${request.requestId}: ${r.reason}")
        }
    }

    private suspend fun runCapture(request: CaptureRequest) {
        var activity = currentActivity()
        if (activity == null) {
            Log.i(TAG, "capture_frame: no foreground activity — the app must be open. Skipping.")
            return
        }

        if (!hasCameraPermission()) {
            // Surface the standard runtime prompt through the foreground activity, then wait a short
            // while for the user to decide. If granted in time we proceed with THIS request; if not,
            // we just bail — the permission is now set for next time.
            Log.i(TAG, "capture_frame: CAMERA not granted — requesting permission")
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.CAMERA), PERMISSION_REQUEST_CODE)
            if (!awaitPermission(PERMISSION_WAIT_MS)) {
                Log.i(TAG, "capture_frame: CAMERA permission not granted in time — skipping this request")
                return
            }
            // The permission dialog paused/resumed the activity; re-acquire the live one.
            activity = currentActivity()
            if (activity == null) {
                Log.i(TAG, "capture_frame: app left foreground during permission prompt — skipping")
                return
            }
        }

        val lifecycleOwner = activity as? LifecycleOwner
        if (lifecycleOwner == null) {
            Log.w(TAG, "capture_frame: foreground activity is not a LifecycleOwner — cannot bind camera")
            return
        }

        // Never capture silently: show an unmistakable indicator while the shutter runs.
        showLookingIndicator(activity, request.prompt)

        val captured = snapFrame(lifecycleOwner) ?: run {
            Log.w(TAG, "capture_frame: camera produced no frame")
            return
        }
        val b64 = FrameCompressor.toJpegBase64(captured.bitmap, captured.rotationDegrees)
        upload(request.requestId, b64)
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private suspend fun awaitPermission(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (hasCameraPermission()) return true
            delay(PERMISSION_POLL_MS)
        }
        return hasCameraPermission()
    }

    private fun showLookingIndicator(activity: Activity, prompt: String) {
        val text = if (prompt.isBlank()) "$ASSISTANT_NAME is looking…" else "$ASSISTANT_NAME is looking… ($prompt)"
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
    }

    private data class Captured(val bitmap: Bitmap, val rotationDegrees: Int)

    /** Binds ImageCapture to [lifecycleOwner], snaps one frame in-memory, then releases the camera. */
    private suspend fun snapFrame(lifecycleOwner: LifecycleOwner): Captured? {
        val provider = ProcessCameraProvider.getInstance(appContext).await()
        val imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        return try {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, imageCapture)
            takePicture(imageCapture)
        } finally {
            // Release the camera regardless of outcome so the QR scanner / voice can use it later.
            provider.unbindAll()
        }
    }

    private suspend fun takePicture(imageCapture: ImageCapture): Captured =
        suspendCancellableCoroutine { cont ->
            imageCapture.takePicture(
                ContextCompat.getMainExecutor(appContext),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            // OnImageCapturedCallback delivers a JPEG-format proxy; toBitmap() decodes it.
                            val captured = Captured(image.toBitmap(), image.imageInfo.rotationDegrees)
                            if (cont.isActive) cont.resume(captured)
                        } catch (t: Throwable) {
                            if (cont.isActive) cont.resumeWithException(t)
                        } finally {
                            image.close()
                        }
                    }

                    override fun onError(exc: ImageCaptureException) {
                        if (cont.isActive) cont.resumeWithException(exc)
                    }
                },
            )
        }

    private suspend fun upload(requestId: String, jpegB64: String) {
        when (val r = apiClient.uploadVisionFrame(requestId, jpegB64)) {
            is ApiResult.Ok ->
                Log.i(TAG, "capture_frame: uploaded ${r.value.bytes ?: -1} bytes for $requestId")
            is ApiResult.Err ->
                Log.w(TAG, "capture_frame: upload failed for $requestId: ${r.error}")
        }
    }

    private suspend fun <T> ListenableFuture<T>.await(): T =
        suspendCancellableCoroutine { cont ->
            addListener(
                {
                    try {
                        if (cont.isActive) cont.resume(get())
                    } catch (t: Throwable) {
                        if (cont.isActive) cont.resumeWithException(t)
                    }
                },
                ContextCompat.getMainExecutor(appContext),
            )
            cont.invokeOnCancellation { cancel(false) }
        }

    companion object {
        private const val TAG = "FrameCapture"
        // Distinct from other requestPermissions codes in the app; the poll-for-grant path doesn't
        // rely on onRequestPermissionsResult, so this only needs to be non-colliding.
        const val PERMISSION_REQUEST_CODE = 4207
        private const val PERMISSION_WAIT_MS = 20_000L
        private const val PERMISSION_POLL_MS = 250L
    }
}
