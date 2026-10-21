package com.daymark.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.daymark.app.domain.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Instant
import java.util.UUID

class DaymarkDatabase(private val context: Context, name: String = "daymark.db") : SQLiteOpenHelper(context, name, null, SCHEMA_VERSION) {
    init { setWriteAheadLoggingEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) = applySchemas(db, 1, SCHEMA_VERSION)

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
        applySchemas(db, oldVersion + 1, newVersion)

    private fun applySchemas(db: SQLiteDatabase, from: Int, to: Int) {
        for (version in from..to) {
            val file = version.toString().padStart(4, '0') + ".sql"
            val sql = context.assets.open(file).bufferedReader().use { it.readText() }
            sql.lineSequence().filterNot { it.trimStart().startsWith("--") }
                .joinToString("\n").split(';').map { it.trim() }.filter { it.isNotEmpty() }
                .forEach { db.execSQL(it) }
        }
    }

    companion object {
        private const val SCHEMA_VERSION = 1
    }
}

/** Every public operation is serialized, including background alarms and UI writes. */
class DaymarkStore(private val helper: DaymarkDatabase) {
    private val db get() = helper.writableDatabase

    @Synchronized fun tasks(day: LocalDate): List<Task> = queryTasks("day = ?", arrayOf(day.toString()))
    @Synchronized fun allTasks(): List<Task> = queryTasks("1", emptyArray())
    @Synchronized fun pending(today: LocalDate): List<Task> = queryTasks(
        "day < ? AND status = 'OPEN' AND priority != 'P0' AND review_dismissed = 0", arrayOf(today.toString())
    )

    @Synchronized fun addTask(title: String, priority: Priority, day: LocalDate): Long {
        require(title.isNotBlank())
        return insert(Task(rootId = UUID.randomUUID().toString(), title = title.trim(), priority = priority, day = day))
    }

    @Synchronized fun updateTask(task: Task, today: LocalDate) {
        require(task.title.isNotBlank())
        transaction {
            val current = findTask(task.id) ?: return@transaction
            if (current.status == TaskStatus.MOVED) return@transaction
            db.update("tasks", taskValues(task.copy(day = current.day, rootId = current.rootId, originDay = current.originDay)), "id = ?", arrayOf(task.id.toString()))
            rollOverInternal(today)
        }
    }

    @Synchronized fun deleteTask(id: Long) { db.delete("tasks", "id = ?", arrayOf(id.toString())) }

    @Synchronized fun rollOver(today: LocalDate) = transaction { rollOverInternal(today) }

    private fun rollOverInternal(today: LocalDate) {
        // Persist a snapshot for every missed day, not only the day the app is reopened.
        val overdue = queryTasks("day < ? AND status = 'OPEN' AND priority = 'P0'", arrayOf(today.toString()))
        for (task in overdue) {
            var current = task
            while (current.day < today) current = moveInternal(current, current.day.plusDays(1))
        }
    }

    @Synchronized fun carry(ids: Set<Long>, today: LocalDate) = transaction {
        ids.forEach { id ->
            findTask(id)?.takeIf { it.day < today && it.status == TaskStatus.OPEN }?.let { moveInternal(it, today) }
        }
    }

    @Synchronized fun dismiss(ids: Set<Long>) = transaction {
        ids.forEach { id -> db.execSQL("UPDATE tasks SET review_dismissed = 1 WHERE id = ? AND status = 'OPEN'", arrayOf(id)) }
    }

    private fun moveInternal(task: Task, to: LocalDate): Task {
        db.execSQL("UPDATE tasks SET status = 'MOVED', moved_to = ? WHERE id = ?", arrayOf<Any>(to.toString(), task.id))
        val next = task.copy(id = 0, day = to, movedTo = null, reviewDismissed = false)
        return next.copy(id = insert(next))
    }

    @Synchronized fun events(): List<Event> = db.rawQuery("SELECT * FROM events ORDER BY start, id", null).use { c ->
        buildList { while (c.moveToNext()) add(c.event()) }
    }

    @Synchronized fun event(id: Long): Event? = db.rawQuery("SELECT * FROM events WHERE id = ?", arrayOf(id.toString())).use { if (it.moveToFirst()) it.event() else null }

    @Synchronized fun saveEvent(event: Event): Long {
        require(event.title.isNotBlank())
        require(event.start.second == 0 && event.start.nano == 0)
        val previous = if (event.id == 0L) null else this.event(event.id)
        val values = ContentValues().apply {
            put("title", event.title.trim()); put("start", event.start.toString()); put("repeat", event.repeat.name)
            put("version", (previous?.version ?: 0) + 1)
            // Pending alarms belong to the previous version. Title-only edits retain deduplication.
            putNull("next_24h_at"); putNull("next_30m_at")
            if (previous == null || previous.start != event.start || previous.repeat != event.repeat) {
                putNull("notified_24h"); putNull("notified_30m")
            }
        }
        return if (event.id == 0L) db.insertOrThrow("events", null, values) else {
            db.update("events", values, "id = ?", arrayOf(event.id.toString())); event.id
        }
    }

    @Synchronized fun deleteEvent(id: Long) { db.delete("events", "id = ?", arrayOf(id.toString())) }

    @Synchronized fun dueEvents(now: Instant): List<Event> = db.rawQuery(
        "SELECT * FROM events WHERE next_24h_at <= ? OR next_30m_at <= ?",
        arrayOf(now.epochSecond.toString(), now.epochSecond.toString()),
    ).use { c -> buildList { while (c.moveToNext()) add(c.event()) } }

    @Synchronized fun saveSchedule(event: Event, lead: Long, trigger: Instant?) {
        db.update("events", ContentValues().apply { put("next_${leadSuffix(lead)}_at", trigger?.epochSecond) },
            "id = ? AND version = ?", arrayOf(event.id.toString(), event.version.toString()))
    }

    /** Atomically claim a pending occurrence before posting, including concurrent/duplicate callbacks. */
    @Synchronized fun markNotified(event: Event, reminder: Reminder, lead: Long): Boolean {
        val suffix = leadSuffix(lead)
        val at = reminder.occurrence.toString()
        return db.update("events", ContentValues().apply { put("notified_$suffix", at); putNull("next_${suffix}_at") },
            "id = ? AND version = ? AND next_${suffix}_at = ? AND (notified_$suffix IS NULL OR notified_$suffix < ?)",
            arrayOf(event.id.toString(), event.version.toString(), reminder.trigger.epochSecond.toString(), at)) == 1
    }

    private fun leadSuffix(lead: Long): String = when (lead) { 1440L -> "24h"; 30L -> "30m"; else -> error("Invalid reminder lead") }

    private fun findTask(id: Long): Task? = queryTasks("id = ?", arrayOf(id.toString())).firstOrNull()
    private fun insert(task: Task) = db.insertOrThrow("tasks", null, taskValues(task))
    private fun taskValues(task: Task) = ContentValues().apply {
        put("root_id", task.rootId); put("title", task.title); put("priority", task.priority.name)
        put("status", task.status.name); put("day", task.day.toString()); put("origin_day", task.originDay.toString())
        put("moved_to", task.movedTo?.toString()); put("review_dismissed", if (task.reviewDismissed) 1 else 0)
    }
    private fun queryTasks(where: String, args: Array<String>): List<Task> = db.rawQuery(
        "SELECT * FROM tasks WHERE $where ORDER BY day DESC, id ASC", args
    ).use { c -> buildList { while (c.moveToNext()) add(c.task()) } }

    private fun Cursor.text(name: String): String = getString(getColumnIndexOrThrow(name))
    private fun Cursor.dateTime(name: String): LocalDateTime? = getString(getColumnIndexOrThrow(name))?.let(LocalDateTime::parse)
    private fun Cursor.instant(name: String): Instant? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else Instant.ofEpochSecond(getLong(it)) }
    private fun Cursor.task() = Task(
        id = getLong(getColumnIndexOrThrow("id")), rootId = text("root_id"), title = text("title"),
        priority = Priority.valueOf(text("priority")), status = TaskStatus.valueOf(text("status")),
        day = LocalDate.parse(text("day")), originDay = LocalDate.parse(text("origin_day")),
        movedTo = getString(getColumnIndexOrThrow("moved_to"))?.let(LocalDate::parse),
        reviewDismissed = getInt(getColumnIndexOrThrow("review_dismissed")) != 0,
    )
    private fun Cursor.event() = Event(
        id = getLong(getColumnIndexOrThrow("id")), title = text("title"), start = LocalDateTime.parse(text("start")),
        repeat = Repeat.valueOf(text("repeat")), version = getLong(getColumnIndexOrThrow("version")),
        next24h = instant("next_24h_at"), next30m = instant("next_30m_at"), notified24h = dateTime("notified_24h"), notified30m = dateTime("notified_30m"),
    )
    private inline fun transaction(block: () -> Unit) {
        db.beginTransaction()
        try { block(); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
}
