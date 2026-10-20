package com.daymark.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.daymark.app.domain.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class DaymarkDatabase(context: Context, name: String = "daymark.db") : SQLiteOpenHelper(context, name, null, 1) {
    init { setWriteAheadLoggingEnabled(true) }
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE tasks (
            id INTEGER PRIMARY KEY AUTOINCREMENT, root_id TEXT NOT NULL, title TEXT NOT NULL,
            priority TEXT NOT NULL CHECK(priority IN ('P0','P1','P2')),
            status TEXT NOT NULL CHECK(status IN ('OPEN','DONE','STALE','MOVED')),
            day TEXT NOT NULL, origin_day TEXT NOT NULL, moved_to TEXT,
            review_dismissed INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE INDEX tasks_day ON tasks(day)")
        db.execSQL("CREATE UNIQUE INDEX one_open_per_root ON tasks(root_id) WHERE status = 'OPEN'")
        db.execSQL("""CREATE TABLE events (
            id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, start TEXT NOT NULL,
            repeat TEXT NOT NULL, version INTEGER NOT NULL DEFAULT 1)""")
        db.execSQL("""CREATE TABLE delivered (
            event_id INTEGER NOT NULL REFERENCES events(id) ON DELETE CASCADE,
            occurrence TEXT NOT NULL, lead INTEGER NOT NULL,
            PRIMARY KEY(event_id, occurrence, lead))""")
        db.execSQL("""CREATE TABLE scheduled_reminders (
            event_id INTEGER NOT NULL REFERENCES events(id) ON DELETE CASCADE,
            lead INTEGER NOT NULL, occurrence TEXT NOT NULL, version INTEGER NOT NULL,
            PRIMARY KEY(event_id, lead))""")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("A migration is required for database version $newVersion")
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
        val values = ContentValues().apply {
            put("title", event.title.trim()); put("start", event.start.toString()); put("repeat", event.repeat.name)
            put("version", if (event.id == 0L) 1L else (this@DaymarkStore.event(event.id)?.version ?: 0) + 1)
        }
        return if (event.id == 0L) db.insertOrThrow("events", null, values) else {
            db.update("events", values, "id = ?", arrayOf(event.id.toString())); event.id
        }
    }

    @Synchronized fun deleteEvent(id: Long) { db.delete("events", "id = ?", arrayOf(id.toString())) }

    @Synchronized fun scheduledReminders(): List<ScheduledReminder> = db.rawQuery("SELECT * FROM scheduled_reminders", null).use { c ->
        buildList { while (c.moveToNext()) add(ScheduledReminder(
            c.getLong(c.getColumnIndexOrThrow("event_id")), c.getLong(c.getColumnIndexOrThrow("lead")),
            LocalDateTime.parse(c.text("occurrence")), c.getLong(c.getColumnIndexOrThrow("version")),
        )) }
    }

    @Synchronized fun saveSchedule(event: Event, lead: Long, occurrence: LocalDateTime?) {
        if (occurrence == null) db.delete("scheduled_reminders", "event_id = ? AND lead = ?", arrayOf(event.id.toString(), lead.toString()))
        else db.insertWithOnConflict("scheduled_reminders", null, ContentValues().apply {
            put("event_id", event.id); put("lead", lead); put("occurrence", occurrence.toString()); put("version", event.version)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized fun markDelivered(eventId: Long, occurrence: LocalDateTime, lead: Long): Boolean =
        db.insertWithOnConflict("delivered", null, ContentValues().apply {
            put("event_id", eventId); put("occurrence", occurrence.toString()); put("lead", lead)
        }, SQLiteDatabase.CONFLICT_IGNORE) != -1L

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
    )
    private inline fun transaction(block: () -> Unit) {
        db.beginTransaction()
        try { block(); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
}
