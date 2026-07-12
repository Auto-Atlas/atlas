package app.eve.reminder

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Robolectric guard on the reminder store (real SharedPreferences) + the boot re-arm policy: a
 * persisted reminder round-trips by id, and [ReminderAlarmScheduler.rescheduleAll] keeps still-future
 * reminders but DROPS ones whose time already passed while the phone was off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReminderAlarmSchedulerTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun future() = System.currentTimeMillis() / 1000L + 3600L // +1h
    private fun past() = System.currentTimeMillis() / 1000L - 3600L    // -1h

    @After
    fun tearDown() {
        // The store is process-wide; clear it so tests don't bleed into each other.
        for (r in ReminderAlarmScheduler.all(context)) ReminderAlarmScheduler.remove(context, r.id)
    }

    @Test
    fun `persist and read back round-trips`() {
        val r = ReminderAlarmScheduler.Reminder("abc123def456", future(), "buy milk")
        ReminderAlarmScheduler.persist(context, r)

        val loaded = ReminderAlarmScheduler.get(context, "abc123def456")
        assertEquals(r, loaded)
        assertEquals(listOf(r), ReminderAlarmScheduler.all(context))
    }

    @Test
    fun `remove deletes both keys`() {
        val r = ReminderAlarmScheduler.Reminder("id0000000001", future(), "x")
        ReminderAlarmScheduler.persist(context, r)
        ReminderAlarmScheduler.remove(context, r.id)

        assertNull(ReminderAlarmScheduler.get(context, r.id))
        assertTrue(ReminderAlarmScheduler.all(context).isEmpty())
    }

    @Test
    fun `onFired drops the one-shot record`() {
        val r = ReminderAlarmScheduler.Reminder("firedid00001", future(), "x")
        ReminderAlarmScheduler.persist(context, r)
        ReminderAlarmScheduler.onFired(context, r.id)
        assertNull(ReminderAlarmScheduler.get(context, r.id))
    }

    @Test
    fun `boot re-arm keeps future reminders and drops past-due ones`() {
        val keep = ReminderAlarmScheduler.Reminder("keepfuture01", future(), "still ahead")
        val drop = ReminderAlarmScheduler.Reminder("droppast0001", past(), "already gone")
        ReminderAlarmScheduler.persist(context, keep)
        ReminderAlarmScheduler.persist(context, drop)

        ReminderAlarmScheduler.rescheduleAll(context)

        val remaining = ReminderAlarmScheduler.all(context).map { it.id }
        assertTrue("future reminder must survive boot", remaining.contains(keep.id))
        assertFalse("past-due reminder must be dropped on boot", remaining.contains(drop.id))
    }
}
