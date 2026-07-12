package app.eve.push

import app.eve.data.models.StreamEvent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the pure seam that decides whether a live stream event should push a fresh snapshot to the
 * watch. Only approval-set changes (pending/resolved/expired) trigger a Data-Layer write; tool-call,
 * delegation, vision and surface-visual events must NOT — no needless watch sync (battery honesty),
 * and there is NO polling: these events are the only refresh triggers from the stream.
 */
class StreamServiceWearRefreshTest {

    @Test
    fun approval_events_trigger_wear_refresh() {
        assertTrue(StreamService.shouldRefreshWear(StreamEvent(type = StreamEvent.TYPE_PENDING, id = "a")))
        assertTrue(StreamService.shouldRefreshWear(StreamEvent(type = StreamEvent.TYPE_RESOLVED, id = "a", ok = true)))
        assertTrue(StreamService.shouldRefreshWear(StreamEvent(type = StreamEvent.TYPE_EXPIRED, id = "a")))
    }

    @Test
    fun non_approval_events_do_not_trigger_wear_refresh() {
        assertFalse(StreamService.shouldRefreshWear(StreamEvent(type = StreamEvent.TYPE_TOOL_CALL)))
        assertFalse(StreamService.shouldRefreshWear(StreamEvent(type = StreamEvent.TYPE_TOOL_RESULT)))
        assertFalse(StreamService.shouldRefreshWear(StreamEvent(type = StreamEvent.TYPE_DELEGATION_START)))
        assertFalse(StreamService.shouldRefreshWear(StreamEvent(type = StreamEvent.TYPE_DELEGATION_STEP)))
        assertFalse(StreamService.shouldRefreshWear(StreamEvent(type = StreamEvent.TYPE_DELEGATION_END)))
        assertFalse(StreamService.shouldRefreshWear(StreamEvent(type = StreamEvent.TYPE_THINKING)))
        assertFalse(StreamService.shouldRefreshWear(StreamEvent(type = StreamEvent.TYPE_CAPTURE_FRAME)))
        assertFalse(StreamService.shouldRefreshWear(StreamEvent(type = StreamEvent.TYPE_SURFACE_VISUAL)))
        assertFalse(StreamService.shouldRefreshWear(StreamEvent(type = "some_future_type")))
    }
}
