package com.daymark.app.reminders

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.daymark.app.MainActivity
import com.daymark.app.R
import com.daymark.app.data.DaymarkStore
import com.daymark.app.daymark
import com.daymark.app.domain.*
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

class ReminderScheduler(private val context: Context, private val store: DaymarkStore) {
    private val alarms get() = context.getSystemService(AlarmManager::class.java)
    val exactAllowed get() = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
    val notificationsAllowed get() = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    fun createChannel() {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Event reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Reminders 24 hours and 30 minutes before your events"
            }
        )
    }

    @Synchronized fun refresh(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()) {
        store.rollOver(now.atZone(zone).toLocalDate())
        // Deliver already-due persisted schedules before replacing alarms. This prevents
        // midnight/resume/reboot from cancelling a reminder that Android has not delivered yet.
        store.scheduledReminders().forEach { saved ->
            store.event(saved.eventId)?.takeIf { it.version == saved.version }?.let { event ->
                deliverIfDue(event, saved.occurrence, saved.lead, now, zone)
            }
        }
        store.events().forEach { event -> LEADS.forEach { schedule(event, it, now, zone) } }
        val midnight = now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
        setAlarm(midnight, midnightIntent())
    }

    @Synchronized fun save(event: Event) {
        store.saveEvent(event)
        refresh()
    }

    @Synchronized fun delete(eventId: Long) {
        LEADS.forEach { alarms.cancel(eventIntent(eventId, it)) }
        store.deleteEvent(eventId)
    }

    private fun schedule(event: Event, lead: Long, now: Instant, zone: ZoneId) {
        val reminder = EventRules.nextReminder(event, lead, now, zone)
        val intent = eventIntent(event.id, lead, event, reminder)
        alarms.cancel(intent)
        store.saveSchedule(event, lead, reminder?.occurrence)
        if (reminder != null) setAlarm(reminder.trigger, intent)
    }

    private fun setAlarm(at: Instant, intent: PendingIntent) {
        try {
            if (exactAllowed) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), intent)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), intent)
        } catch (_: SecurityException) {
            // Permission can be revoked between checking it and scheduling.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), intent)
        }
    }

    @Synchronized fun receive(intent: Intent, now: Instant = Instant.now()) {
        if (intent.action == MIDNIGHT) { refresh(now); return }
        val id = intent.getLongExtra("event_id", -1)
        val lead = intent.getLongExtra("lead", -1)
        val event = store.event(id)
        val at = intent.getStringExtra("occurrence")?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
        if (event != null && at != null && lead in LEADS && event.version == intent.getLongExtra("version", -1)) {
            val zone = ZoneId.systemDefault()
            deliverIfDue(event, at, lead, now, zone)
        }
        refresh(now)
    }

    private fun deliverIfDue(event: Event, at: LocalDateTime, lead: Long, now: Instant, zone: ZoneId) {
        val trigger = at.atZone(zone).toInstant().minusSeconds(lead * 60)
        if (trigger <= now && now < at.atZone(zone).toInstant() && notificationsAllowed && store.markDelivered(event.id, at, lead)) {
            show(event, at, lead)
        }
    }

    private fun show(event: Event, at: LocalDateTime, lead: Long) {
        val open = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).apply {
                data = Uri.parse("daymark://open/${event.id}/$at/$lead")
                putExtra("open_events", true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val whenText = at.format(DateTimeFormatter.ofPattern("EEE, MMM d · HH:mm", Locale.ENGLISH))
        val text = if (lead == 1440L) "24-hour reminder · $whenText" else "Coming up · $whenText"
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(event.title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build()
        try { NotificationManagerCompat.from(context).notify("${event.id}/$at/$lead", 0, notification) }
        catch (_: SecurityException) { /* Permission may have changed while the receiver was running. */ }
    }

    private fun eventIntent(id: Long, lead: Long, event: Event? = null, reminder: Reminder? = null): PendingIntent =
        PendingIntent.getBroadcast(context, 0, Intent(context, ReminderReceiver::class.java).apply {
            action = REMINDER
            data = Uri.parse("daymark://reminder/$id/$lead")
            putExtra("event_id", id); putExtra("lead", lead)
            event?.let { putExtra("version", it.version) }
            reminder?.let { putExtra("occurrence", it.occurrence.toString()) }
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun midnightIntent(): PendingIntent = PendingIntent.getBroadcast(context, 1,
        Intent(context, ReminderReceiver::class.java).setAction(MIDNIGHT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    companion object {
        const val CHANNEL = "events"
        const val MIDNIGHT = "com.daymark.MIDNIGHT"
        const val REMINDER = "com.daymark.REMINDER"
        val LEADS = listOf(1440L, 30L)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        context.daymark.backgroundScope.launch {
            try { context.daymark.scheduler.receive(intent) } finally { pending.finish() }
        }
    }
}

class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(
                Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            )) return
        val pending = goAsync()
        context.daymark.backgroundScope.launch {
            try { context.daymark.scheduler.refresh() } finally { pending.finish() }
        }
    }
}
