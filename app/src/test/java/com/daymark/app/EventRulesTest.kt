package com.daymark.app

import com.daymark.app.domain.*
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class EventRulesTest {
    private fun event(start: String, repeat: Repeat) = Event(title = "A date", start = LocalDateTime.parse(start), repeat = repeat)

    @Test fun `one-time events have no occurrences after their start`() {
        val event = event("2026-10-20T09:17", Repeat.ONCE)
        assertEquals(event.start, EventRules.next(event, event.start))
        assertNull(EventRules.next(event, event.start.plusMinutes(1)))
        assertNull(EventRules.onDay(event, LocalDate.parse("2026-10-21")))
    }

    @Test fun `monthly recurrence returns to original day after a short month`() {
        val event = event("2026-01-31T10:23", Repeat.MONTHLY)
        assertEquals(LocalDateTime.parse("2026-02-28T10:23"), EventRules.next(event, LocalDateTime.parse("2026-02-01T00:00")))
        assertEquals(LocalDateTime.parse("2026-03-31T10:23"), EventRules.next(event, LocalDateTime.parse("2026-03-01T00:00")))
    }

    @Test fun `leap-day birthdays use February 28 and recover February 29`() {
        val event = event("2024-02-29T08:05", Repeat.YEARLY)
        assertEquals(LocalDateTime.parse("2025-02-28T08:05"), EventRules.next(event, LocalDateTime.parse("2025-01-01T00:00")))
        assertEquals(LocalDateTime.parse("2028-02-29T08:05"), EventRules.next(event, LocalDateTime.parse("2028-01-01T00:00")))
    }

    @Test fun `daily and weekly recurrence keep time to the minute`() {
        val daily = event("2026-10-19T13:07", Repeat.DAILY)
        assertEquals(LocalDateTime.parse("2026-10-21T13:07"), EventRules.next(daily, LocalDateTime.parse("2026-10-20T13:08")))
        val weekly = daily.copy(repeat = Repeat.WEEKLY)
        assertEquals(LocalDateTime.parse("2026-10-26T13:07"), EventRules.next(weekly, LocalDateTime.parse("2026-10-20T00:00")))
    }

    @Test fun `far-future recurrences are calculated from anchor`() {
        val event = event("2000-01-31T09:00", Repeat.MONTHLY)
        assertEquals(LocalDateTime.parse("2100-02-28T09:00"), EventRules.next(event, LocalDateTime.parse("2100-02-01T00:00")))
    }

    @Test fun `each reminder lead schedules independently for daily events`() {
        val event = event("2026-10-19T09:00", Repeat.DAILY)
        val now = Instant.parse("2026-10-20T08:00:00Z")
        assertEquals(LocalDateTime.parse("2026-10-21T09:00"), EventRules.nextReminder(event, 1440, now, ZoneOffset.UTC)?.occurrence)
        assertEquals(LocalDateTime.parse("2026-10-20T09:00"), EventRules.nextReminder(event, 30, now, ZoneOffset.UTC)?.occurrence)
    }

    @Test fun `elapsed 24-hour reminder stays exact across daylight saving changes`() {
        val zone = ZoneId.of("America/Los_Angeles")
        listOf("2026-03-08T09:00", "2026-11-01T09:00").forEach { start ->
            val event = event(start, Repeat.ONCE)
            val reminder = EventRules.nextReminder(event, 1440, event.start.atZone(zone).toInstant().minusSeconds(172800), zone)!!
            assertEquals(Duration.ofHours(24), Duration.between(reminder.trigger, event.start.atZone(zone).toInstant()))
        }
    }

    @Test fun `past reminder is skipped while future 30-minute reminder remains`() {
        val event = event("2026-10-20T09:00", Repeat.ONCE)
        val now = Instant.parse("2026-10-20T08:00:00Z")
        assertNull(EventRules.nextReminder(event, 1440, now, ZoneOffset.UTC))
        assertEquals(Instant.parse("2026-10-20T08:30:00Z"), EventRules.nextReminder(event, 30, now, ZoneOffset.UTC)?.trigger)
        assertNull(EventRules.nextReminder(event, 30, Instant.parse("2026-10-20T08:30:00Z"), ZoneOffset.UTC))
    }

    @Test fun `next day preview includes recurring events and excludes wrong days`() {
        val event = event("2020-10-20T09:00", Repeat.YEARLY)
        assertNotNull(EventRules.onDay(event, LocalDate.parse("2026-10-20")))
        assertNull(EventRules.onDay(event, LocalDate.parse("2026-10-19")))
    }
}
