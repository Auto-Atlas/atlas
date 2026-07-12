package app.eve.ritual

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.Calendar
import java.util.TimeZone
import org.junit.Test

/** Pure time-math for the morning-ritual alarm — no Android, fixed UTC calendar + clock. */
class RitualSchedulerTest {

    private fun utcCalendar() = Calendar.getInstance(TimeZone.getTimeZone("UTC"))

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        val c = utcCalendar()
        c.clear()
        c.set(year, month, day, hour, minute, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    @Test
    fun `rolls to tomorrow when the time already passed today`() {
        val now = at(2026, Calendar.JUNE, 22, 9, 0) // 09:00, after 05:00
        val next = RitualScheduler.nextTriggerMs(now, 5, 0, utcCalendar())
        assertEquals(at(2026, Calendar.JUNE, 23, 5, 0), next)
    }

    @Test
    fun `schedules today when the time is still ahead`() {
        val now = at(2026, Calendar.JUNE, 22, 3, 30) // 03:30, before 05:00
        val next = RitualScheduler.nextTriggerMs(now, 5, 0, utcCalendar())
        assertEquals(at(2026, Calendar.JUNE, 22, 5, 0), next)
    }

    @Test
    fun `the exact same minute counts as passed and rolls forward`() {
        val now = at(2026, Calendar.JUNE, 22, 5, 0) // exactly 05:00
        val next = RitualScheduler.nextTriggerMs(now, 5, 0, utcCalendar())
        assertEquals(at(2026, Calendar.JUNE, 23, 5, 0), next)
    }

    @Test
    fun `next trigger is always strictly in the future`() {
        val now = at(2026, Calendar.DECEMBER, 31, 23, 59)
        val next = RitualScheduler.nextTriggerMs(now, 5, 0, utcCalendar())
        assertTrue(next > now)
        // Rolls across the year boundary to Jan 1.
        assertEquals(at(2027, Calendar.JANUARY, 1, 5, 0), next)
    }
}
