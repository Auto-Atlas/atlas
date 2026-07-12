package app.eve.visual

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.eve.data.ApiClient
import app.eve.data.ApiError
import app.eve.data.ApiResult
import app.eve.data.models.StreamEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The load lifecycle of a card's image. Notes carry [NoImage]; image kinds progress
 *  Loading → Loaded / Expired / Failed. */
sealed interface ImageLoad {
    /** kind=note — nothing to fetch. */
    data object NoImage : ImageLoad
    data object Loading : ImageLoad
    data class Loaded(val bitmap: ImageBitmap) : ImageLoad
    /** 404 from /v1/visual/{id}: the spooled image aged out of its TTL. */
    data object Expired : ImageLoad
    data class Failed(val message: String) : ImageLoad
}

/** The latest surfaced card the Talk screen renders: the validated [visual] plus its [image] load. */
data class VisualCard(val visual: SurfaceVisual, val image: ImageLoad)

/**
 * Holds the single latest surfaced visual and drives its image fetch. Mirrors the camera feature's
 * [app.eve.vision.FrameCaptureController] shape: a container-held singleton the [StreamService]
 * dispatches `surface_visual` events to, exposing a [StateFlow] the Talk UI observes. Kept out of
 * the Talk ViewModel so the card survives navigation and there's exactly ONE consumer of the event
 * (the ViewModel's own stream subscription ignores unknown types).
 *
 * A new visual REPLACES the previous one (single latest); the in-flight load is cancelled so a slow
 * fetch never clobbers a newer card. The JPEG decode ([decodeImage]) is injectable so the pure
 * orchestration can be exercised without Android graphics in a test.
 */
class VisualHub(
    private val api: ApiClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val decodeImage: (ByteArray) -> ImageBitmap? = ::decodeJpeg,
) {
    private val _state = MutableStateFlow<VisualCard?>(null)
    val state: StateFlow<VisualCard?> = _state.asStateFlow()

    private var loadJob: Job? = null

    /** Handle a stream event. No-ops on anything but a well-formed `surface_visual`. */
    fun onEvent(event: StreamEvent) {
        if (!event.isSurfaceVisual) return
        val visual = SurfaceVisual.parse(event.kind, event.title, event.visualId, event.text) ?: return
        loadJob?.cancel()
        if (!visual.isImage) {
            _state.value = VisualCard(visual, ImageLoad.NoImage)
            return
        }
        _state.value = VisualCard(visual, ImageLoad.Loading)
        loadJob = scope.launch { load(visual) }
    }

    private suspend fun load(visual: SurfaceVisual) {
        val id = visual.visualId ?: return
        val image = when (val r = api.fetchVisual(id)) {
            is ApiResult.Ok ->
                decodeImage(r.value)?.let { ImageLoad.Loaded(it) }
                    ?: ImageLoad.Failed("This image couldn't be decoded.")
            is ApiResult.Err ->
                if (r.error is ApiError.NotFound) ImageLoad.Expired
                else ImageLoad.Failed("Couldn't load this image.")
        }
        // Apply only if this is still the current card — a newer visual must win.
        val cur = _state.value
        if (cur != null && cur.visual == visual) _state.value = cur.copy(image = image)
    }

    /** User dismissed the card; also drops any in-flight load and frees the bitmap. */
    fun dismiss() {
        loadJob?.cancel()
        _state.value = null
    }
}

private fun decodeJpeg(bytes: ByteArray): ImageBitmap? =
    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
