package com.daymark.app.domain

import java.time.*
import java.time.temporal.ChronoUnit

enum class Priority(val subtitle: String) { P0("Must do"), P1("Nice to do"), P2("If there's room") }
enum class TaskStatus { OPEN, DONE, STALE, MOVED }
enum class Repeat(val label: String) { ONCE("Does not repeat"), DAILY("Every day"), WEEKLY("Every week"), MONTHLY("Every month"), YEARLY("Every year") }

data class Task(
    val id: Long = 0,
    val rootId: String,
    val title: String,
    val priority: Priority,
    val status: TaskStatus = TaskStatus.OPEN,
    val day: LocalDate,
    val originDay: LocalDate = day,
    val movedTo: LocalDate? = null,
    val reviewDismissed: Boolean = false,
)

data class Event(
    val id: Long = 0,
    val title: String,
    val start: LocalDateTime,
    val repeat: Repeat = Repeat.ONCE,
    val version: Long = 1,
)

data class Occurrence(val event: Event, val at: LocalDateTime)
data class Reminder(val occurrence: LocalDateTime, val trigger: Instant)
data class ScheduledReminder(val eventId: Long, val lead: Long, val occurrence: LocalDateTime, val version: Long)

/** Recurrences are calculated from the original anchor, so Jan 31 -> Feb 28 -> Mar 31. */
object EventRules {
    fun atIndex(event: Event, index: Long): LocalDateTime = when (event.repeat) {
        Repeat.ONCE -> event.start
        Repeat.DAILY -> event.start.plusDays(index)
        Repeat.WEEKLY -> event.start.plusWeeks(index)
        Repeat.MONTHLY -> event.start.plusMonths(index)
        Repeat.YEARLY -> event.start.plusYears(index)
    }

    fun next(event: Event, from: LocalDateTime): LocalDateTime? {
        if (event.start >= from) return event.start
        if (event.repeat == Repeat.ONCE) return null
        var index = when (event.repeat) {
            Repeat.DAILY -> ChronoUnit.DAYS.between(event.start.toLocalDate(), from.toLocalDate())
            Repeat.WEEKLY -> ChronoUnit.DAYS.between(event.start.toLocalDate(), from.toLocalDate()) / 7
            Repeat.MONTHLY -> ChronoUnit.MONTHS.between(YearMonth.from(event.start), YearMonth.from(from))
            Repeat.YEARLY -> (from.year - event.start.year).toLong()
            Repeat.ONCE -> 0
        }.coerceAtLeast(0)
        while (atIndex(event, index) < from) index++
        return atIndex(event, index)
    }

    fun onDay(event: Event, day: LocalDate): Occurrence? = next(event, day.atStartOfDay())
        ?.takeIf { it.toLocalDate() == day }?.let { Occurrence(event, it) }

    /** Leads are elapsed durations (24 h / 30 min), including across DST boundaries. */
    fun nextReminder(event: Event, leadMinutes: Long, now: Instant, zone: ZoneId): Reminder? {
        val threshold = now.plusSeconds(leadMinutes * 60)
        // Start a day earlier to include both sides of a daylight-saving overlap.
        var candidate = next(event, threshold.atZone(zone).toLocalDate().minusDays(1).atStartOfDay())
        while (candidate != null) {
            val trigger = candidate.atZone(zone).toInstant().minusSeconds(leadMinutes * 60)
            if (trigger > now) return Reminder(candidate, trigger)
            if (event.repeat == Repeat.ONCE) return null
            candidate = next(event, candidate.plusMinutes(1))
        }
        return null
    }
}
