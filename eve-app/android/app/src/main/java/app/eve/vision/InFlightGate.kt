package app.eve.vision

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single-in-flight guard for camera captures: only ONE look_via_phone snapshot may run at a time,
 * so overlapping/duplicate `capture_frame` events are dropped rather than fighting over the camera.
 *
 * [tryAcquire] returns true exactly once until [release] is called; every extra caller gets false
 * and should no-op. Backed by an [AtomicBoolean] so it is safe to hit from the stream coroutine and
 * the camera callback threads. Pure enough to unit-test on the JVM with no Android runtime.
 */
class InFlightGate {
    private val busy = AtomicBoolean(false)

    /** True if this caller won the slot (was idle); false if a capture is already in flight. */
    fun tryAcquire(): Boolean = busy.compareAndSet(false, true)

    /** Releases the slot so the next capture can proceed. Idempotent. */
    fun release() {
        busy.set(false)
    }

    /** For tests/telemetry: whether a capture currently holds the slot. */
    val isBusy: Boolean get() = busy.get()
}
