package app.eve.reminder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-JVM guard on the `set_alarm` payload contract (no Android runtime). The server sends all
 * values as STRINGS; a bad/missing field must be dropped gracefully (null), never crash, and a valid
 * payload must round-trip its epoch + text. Past-due is a separate clock decision ([isFuture]).
 */
class ReminderPayloadTest {

    @Test
    fun `valid payload parses`() {
        val r = ReminderAlarmScheduler.parseReminder("a1b2c3d4e5f6", "1780000000", "call the vet")
        assertNotNull(r)
        assertEquals("a1b2c3d4e5f6", r.id)
        assertEquals(1780000000L, r.dueEpochSec)
        assertEquals("call the vet", r.what)
        assertEquals(1780000000000L, r.dueMs)
    }

    @Test
    fun `missing id is rejected`() {
        assertNull(ReminderAlarmScheduler.parseReminder(null, "1780000000", "x"))
        assertNull(ReminderAlarmScheduler.parseReminder("", "1780000000", "x"))
        assertNull(ReminderAlarmScheduler.parseReminder("   ", "1780000000", "x"))
    }

    @Test
    fun `missing what is rejected`() {
        assertNull(ReminderAlarmScheduler.parseReminder("id", "1780000000", null))
        assertNull(ReminderAlarmScheduler.parseReminder("id", "1780000000", ""))
    }

    @Test
    fun `non-numeric or non-positive due_epoch is rejected`() {
        assertNull(ReminderAlarmScheduler.parseReminder("id", null, "x"))
        assertNull(ReminderAlarmScheduler.parseReminder("id", "not-a-number", "x"))
        assertNull(ReminderAlarmScheduler.parseReminder("id", "0", "x"))
        assertNull(ReminderAlarmScheduler.parseReminder("id", "-5", "x"))
    }

    @Test
    fun `isFuture compares against the clock in millis`() {
        val r = ReminderAlarmScheduler.parseReminder("id", "1000", "x")!! // 1000s = 1_000_000 ms
        assertTrue(ReminderAlarmScheduler.isFuture(r, nowMs = 999_999L))
        assertFalse(ReminderAlarmScheduler.isFuture(r, nowMs = 1_000_000L)) // exactly due = not future
        assertFalse(ReminderAlarmScheduler.isFuture(r, nowMs = 2_000_000L))
    }
}
