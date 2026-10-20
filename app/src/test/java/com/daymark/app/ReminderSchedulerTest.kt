package com.daymark.app

import android.Manifest
import android.app.Application
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.daymark.app.data.*
import com.daymark.app.domain.*
import com.daymark.app.reminders.ReminderScheduler
import java.time.*
import java.util.TimeZone
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReminderSchedulerTest {
    private lateinit var context: Context
    private lateinit var helper: DaymarkDatabase
    private lateinit var store: DaymarkStore
    private lateinit var scheduler: ReminderScheduler
    private lateinit var originalZone: TimeZone
    private val now = Instant.parse("2026-10-19T08:00:00Z")
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
    @Before fun setup() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("reminders-test.db")
        helper = DaymarkDatabase(context, "reminders-test.db")
        store = DaymarkStore(helper)
        scheduler = ReminderScheduler(context, store)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }
    @After fun close() { helper.close(); TimeZone.setDefault(originalZone) }

    @Test fun `each event has two independent alarms plus midnight rollover`() {
        store.saveEvent(Event(title = "Exam", start = LocalDateTime.parse("2026-10-21T14:23")))
        scheduler.refresh(now, ZoneOffset.UTC)
        val times = alarms.map { it.triggerAtTime }.toSet()
        assertEquals(setOf(
            Instant.parse("2026-10-20T14:23:00Z").toEpochMilli(),
            Instant.parse("2026-10-21T13:53:00Z").toEpochMilli(),
            Instant.parse("2026-10-20T00:00:00Z").toEpochMilli(),
        ), times)
        scheduler.refresh(now, ZoneOffset.UTC)
        assertEquals(3, alarms.size)
    }

    @Test fun `deleting event cancels both reminders`() {
        val id = store.saveEvent(Event(title = "Exam", start = LocalDateTime.parse("2026-10-21T14:23")))
        scheduler.refresh(now, ZoneOffset.UTC)
        scheduler.delete(id)
        assertEquals(1, alarms.size)
        assertNull(store.event(id))
    }

    @Test fun `denied precise permission still schedules fallback reminders`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        store.saveEvent(Event(title = "Exam", start = LocalDateTime.parse("2026-10-21T14:23")))
        scheduler.refresh(now, ZoneOffset.UTC)
        assertFalse(scheduler.exactAllowed)
        assertEquals(3, alarms.size)
        assertTrue(alarms.all { it.allowWhileIdle })
    }

    @Test fun `edited event replaces scheduled time and alarm version`() {
        val id = store.saveEvent(Event(title = "Exam", start = LocalDateTime.parse("2026-10-21T14:23")))
        scheduler.refresh(now, ZoneOffset.UTC)
        store.saveEvent(store.event(id)!!.copy(start = LocalDateTime.parse("2026-10-22T15:17")))
        scheduler.refresh(now, ZoneOffset.UTC)
        assertEquals(3, alarms.size)
        val eventAlarms = alarms.filter { shadowOf(it.operation).savedIntent.action == ReminderScheduler.REMINDER }
        assertTrue(eventAlarms.all { shadowOf(it.operation).savedIntent.getLongExtra("version", -1) == 2L })
        assertTrue(eventAlarms.map { it.triggerAtTime }.contains(Instant.parse("2026-10-22T14:47:00Z").toEpochMilli()))
    }

    @Test fun `old alarm version cannot send a stale notification`() {
        val id = store.saveEvent(Event(title = "Exam", start = LocalDateTime.parse("2026-10-19T08:30")))
        store.saveEvent(store.event(id)!!.copy(title = "Moved exam"))
        scheduler.receive(Intent().putExtra("event_id", id).putExtra("lead", 30L).putExtra("version", 1L).putExtra("occurrence", "2026-10-19T08:30"), now)
        assertTrue(shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
    }

    @Test fun `midnight refresh delivers an already due reminder before replacing alarms`() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(true)
        store.saveEvent(Event(title = "Midnight event", start = LocalDateTime.parse("2026-10-21T00:00")))
        scheduler.refresh(Instant.parse("2026-10-19T23:00:00Z"), ZoneOffset.UTC)
        scheduler.refresh(Instant.parse("2026-10-20T00:00:00Z"), ZoneOffset.UTC)
        assertEquals(1, shadowOf(manager).allNotifications.size)
        scheduler.refresh(Instant.parse("2026-10-20T00:01:00Z"), ZoneOffset.UTC)
        assertEquals(1, shadowOf(manager).allNotifications.size)
    }

    @Test fun `creating a near event does not send a missed 24-hour reminder`() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(true)
        store.saveEvent(Event(title = "Soon", start = LocalDateTime.parse("2026-10-19T09:00")))
        scheduler.refresh(now, ZoneOffset.UTC)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertEquals(2, alarms.size) // 30-minute reminder and next midnight
    }
}
