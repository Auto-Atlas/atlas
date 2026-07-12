package app.eve.vision

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The single-in-flight guard: only one look_via_phone capture may run at a time so overlapping
 * `capture_frame` events are dropped rather than fighting over the camera.
 */
class InFlightGateTest {

    @Test
    fun `first acquire wins, second is dropped until release`() {
        val gate = InFlightGate()
        assertTrue(gate.tryAcquire(), "first capture should win the slot")
        assertTrue(gate.isBusy)
        assertFalse(gate.tryAcquire(), "overlapping capture must be dropped")
        assertFalse(gate.tryAcquire(), "still busy")

        gate.release()
        assertFalse(gate.isBusy)
        assertTrue(gate.tryAcquire(), "slot free again after release")
    }

    @Test
    fun `release is idempotent`() {
        val gate = InFlightGate()
        gate.release() // release while idle is a no-op, must not throw or over-open
        assertFalse(gate.isBusy)
        assertTrue(gate.tryAcquire())
        gate.release()
        gate.release()
        assertTrue(gate.tryAcquire())
    }
}
