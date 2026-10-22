package com.daymark.app

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import com.daymark.app.data.*
import com.daymark.app.domain.*
import java.time.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DaymarkStoreTest {
    private lateinit var helper: DaymarkDatabase
    private lateinit var store: DaymarkStore
    private val first = LocalDate.parse("2026-10-19")
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("test.db")
        helper = DaymarkDatabase(context, "test.db")
        store = DaymarkStore(helper)
    }
    @After fun close() { helper.close() }

    private fun reminder(at: LocalDateTime, lead: Long) = Reminder(at, at.toInstant(ZoneOffset.UTC).minusSeconds(lead * 60))
    private fun saveSchedule(event: Event, lead: Long, at: LocalDateTime) = store.saveSchedule(event, lead, reminder(at, lead).trigger)
    private fun markNotified(id: Long, version: Long, at: LocalDateTime, lead: Long) =
        store.markNotified(Event(id = id, title = "", start = at, version = version), reminder(at, lead), lead)


    @Test fun `all priorities carry through every missed day and rollover is idempotent`() {
        Priority.entries.forEach { store.addTask("Task ${it.name}", it, first) }
        val roots = store.tasks(first).associate { it.priority to it.rootId }
        val today = first.plusDays(4)
        repeat(3) { store.rollOver(today) }
        assertEquals(15, store.allTasks().size)
        assertEquals(3, store.allTasks().count { it.status == TaskStatus.OPEN })
        (0L..4L).forEach { offset ->
            val snapshots = store.tasks(first.plusDays(offset))
            assertEquals(Priority.entries.toSet(), snapshots.map { it.priority }.toSet())
            snapshots.forEach { snapshot ->
                assertEquals(roots[snapshot.priority], snapshot.rootId)
                assertEquals("Task ${snapshot.priority.name}", snapshot.title)
                assertEquals(first, snapshot.originDay)
                assertEquals(if (offset == 4L) TaskStatus.OPEN else TaskStatus.MOVED, snapshot.status)
                assertEquals(if (offset == 4L) null else first.plusDays(offset + 1), snapshot.movedTo)
            }
        }
    }

    @Test fun `done and stale tasks never carry over for any priority`() {
        Priority.entries.forEach { priority ->
            listOf(TaskStatus.DONE, TaskStatus.STALE).forEach { status ->
                val id = store.addTask("${priority.name} ${status.name}", priority, first)
                val task = store.tasks(first).single { it.id == id }
                store.updateTask(task.copy(status = status), first)
            }
        }
        store.rollOver(first.plusDays(7))
        assertEquals(6, store.allTasks().size)
        assertTrue(store.tasks(first.plusDays(7)).isEmpty())
    }

    @Test fun `changing an overdue task to any priority preserves automatic carry-over`() {
        Priority.entries.forEach { priority ->
            val id = store.addTask("Change to ${priority.name}", Priority.P1, first)
            val task = store.tasks(first).single { it.id == id }
            store.updateTask(task.copy(priority = priority), first.plusDays(2))
            val carried = store.tasks(first.plusDays(2)).single { it.rootId == task.rootId }
            assertEquals(priority, carried.priority)
            assertEquals(TaskStatus.OPEN, carried.status)
            assertEquals(first, carried.originDay)
        }
        assertEquals(9, store.allTasks().size)
    }

    @Test fun `completing or staling carried tasks stops further carry-over`() {
        Priority.entries.forEach { store.addTask("Task ${it.name}", it, first) }
        val nextDay = first.plusDays(1)
        store.rollOver(nextDay)
        store.tasks(nextDay).forEach { task ->
            store.updateTask(task.copy(status = if (task.priority == Priority.P2) TaskStatus.STALE else TaskStatus.DONE), nextDay)
        }
        store.rollOver(nextDay.plusDays(3))
        assertEquals(6, store.allTasks().size)
        assertTrue(store.tasks(nextDay.plusDays(3)).isEmpty())
    }

    @Test fun `changing P0 to P2 retains automatic carry-over`() {
        store.addTask("Less urgent now", Priority.P0, first)
        val task = store.tasks(first).single()
        store.updateTask(task.copy(priority = Priority.P2), first)
        store.rollOver(first.plusDays(1))
        assertEquals(Priority.P2, store.tasks(first.plusDays(1)).single().priority)
        assertEquals(task.rootId, store.tasks(first.plusDays(1)).single().rootId)
    }

    @Test fun `P1 and P2 can switch without losing status or title`() {
        store.addTask("Read a chapter", Priority.P1, first)
        store.updateTask(store.tasks(first).single().copy(priority = Priority.P2), first)
        assertEquals(Priority.P2, store.tasks(first).single().priority)
        store.updateTask(store.tasks(first).single().copy(priority = Priority.P1), first)
        assertEquals("Read a chapter", store.tasks(first).single().title)
        assertEquals(TaskStatus.OPEN, store.tasks(first).single().status)
    }

    @Test fun `historical carried snapshot cannot resurrect duplicate tasks`() {
        store.addTask("Focus", Priority.P0, first)
        val snapshot = store.tasks(first).single()
        store.rollOver(first.plusDays(1))
        store.updateTask(snapshot.copy(title = "Obsolete edit"), first.plusDays(1))
        assertEquals(TaskStatus.MOVED, store.tasks(first).single().status)
        assertEquals("Focus", store.tasks(first.plusDays(1)).single().title)
    }

    @Test fun `deleting an entry retains other historical snapshots`() {
        store.addTask("Focus", Priority.P0, first)
        store.rollOver(first.plusDays(1))
        store.deleteTask(store.tasks(first.plusDays(1)).single().id)
        assertTrue(store.tasks(first.plusDays(1)).isEmpty())
        assertEquals(1, store.tasks(first).size)
    }

    @Test fun `future tasks of all priorities are not brought forward`() {
        Priority.entries.forEach { store.addTask("Later ${it.name}", it, first.plusDays(3)) }
        store.rollOver(first)
        assertTrue(store.tasks(first).isEmpty())
        assertEquals(3, store.tasks(first.plusDays(3)).size)
    }

    @Test fun `event edits invalidate old alarms and deleting an event removes its reminder state`() {
        val event = Event(title = "Birthday", start = LocalDateTime.parse("2026-10-20T17:23"), repeat = Repeat.YEARLY)
        val id = store.saveEvent(event)
        assertEquals(event.start, store.event(id)!!.start)
        assertEquals(1L, store.event(id)!!.version)
        saveSchedule(store.event(id)!!, 30, event.start)
        assertTrue(markNotified(id, 1, event.start, 30))
        assertFalse(markNotified(id, 1, event.start, 30))
        store.saveEvent(store.event(id)!!.copy(title = "Dinner"))
        assertEquals(2L, store.event(id)!!.version)
        assertEquals(event.start, store.event(id)!!.notified30m)
        store.deleteEvent(id)
        assertNull(store.event(id))
        assertFalse(markNotified(id, 2, event.start, 30))
    }

    @Test fun `only tasks and events exist with the expected indexes`() {
        helper.readableDatabase.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata'", null).use { cursor ->
            val tables = buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            assertEquals(setOf("tasks", "events"), tables)
        }
        helper.readableDatabase.rawQuery("SELECT name FROM sqlite_master WHERE type = 'index' AND sql IS NOT NULL", null).use { cursor ->
            val indexes = buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            assertEquals(setOf("tasks_day", "one_open_per_root", "events_start", "events_next_24h", "events_next_30m"), indexes)
        }
    }

    @Test fun `repeat accepts only the five supported values on insert and update`() {
        val db = helper.writableDatabase
        Repeat.entries.forEach { repeat ->
            db.execSQL("INSERT INTO events (title, start, repeat) VALUES (?, ?, ?)", arrayOf("Event", "2026-10-20T10:00", repeat.name))
        }
        listOf("", "daily", "FORTNIGHTLY").forEach { invalid ->
            assertThrows(SQLiteConstraintException::class.java) {
                db.execSQL("INSERT INTO events (title, start, repeat) VALUES (?, ?, ?)", arrayOf("Invalid", "2026-10-20T10:00", invalid))
            }
            assertThrows(SQLiteConstraintException::class.java) {
                db.execSQL("UPDATE events SET repeat = ? WHERE id = 1", arrayOf(invalid))
            }
        }
        assertEquals(Repeat.entries.toSet(), store.events().map { it.repeat }.toSet())
    }

    @Test fun `indexed epoch timestamps return only due events and are cleared after notification`() {
        val now = Instant.parse("2026-10-20T09:30:00Z")
        val id = store.saveEvent(Event(title = "Due", start = first.plusDays(1).atTime(10, 0)))
        val later = store.saveEvent(Event(title = "Later", start = first.plusDays(1).atTime(11, 0)))
        store.saveSchedule(store.event(id)!!, 30, now)
        store.saveSchedule(store.event(later)!!, 30, now.plusSeconds(3600))
        assertEquals(listOf(id), store.dueEvents(now).map { it.id })
        helper.readableDatabase.rawQuery("SELECT typeof(next_30m_at), next_30m_at FROM events WHERE id = ?", arrayOf(id.toString())).use {
            assertTrue(it.moveToFirst()); assertEquals("integer", it.getString(0)); assertEquals(now.epochSecond, it.getLong(1))
        }
        helper.readableDatabase.rawQuery("EXPLAIN QUERY PLAN SELECT * FROM events WHERE next_24h_at <= ? OR next_30m_at <= ?", arrayOf(now.epochSecond.toString(), now.epochSecond.toString())).use { cursor ->
            val plan = buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }.joinToString(" ")
            assertTrue(plan, plan.contains("events_next_24h")); assertTrue(plan, plan.contains("events_next_30m"))
        }
        assertTrue(store.markNotified(store.event(id)!!, Reminder(first.plusDays(1).atTime(10, 0), now), 30))
        assertTrue(store.dueEvents(now).isEmpty())
    }

    @Test fun `reminder claims reject old versions and older occurrences after a clock rollback`() {
        val at = first.atTime(10, 0)
        val id = store.saveEvent(Event(title = "Daily", start = at, repeat = Repeat.DAILY))
        val event = store.event(id)!!
        saveSchedule(event, 30, at.plusDays(1))
        assertFalse(markNotified(id, event.version + 1, at.plusDays(1), 30))
        assertTrue(markNotified(id, event.version, at.plusDays(1), 30))
        saveSchedule(event, 30, at)
        assertFalse(markNotified(id, event.version, at, 30))
        assertEquals(at.plusDays(1), store.event(id)!!.notified30m)
    }

    @Test fun `schedule edits reset reminder state but title edits retain the processed occurrence`() {
        val at = first.atTime(10, 0)
        val id = store.saveEvent(Event(title = "Daily", start = at, repeat = Repeat.DAILY))
        saveSchedule(store.event(id)!!, 30, at)
        assertTrue(markNotified(id, 1, at, 30))
        store.saveEvent(store.event(id)!!.copy(title = "Daily renamed"))
        assertEquals(at, store.event(id)!!.notified30m)
        saveSchedule(store.event(id)!!, 1440, at.plusDays(1))
        store.saveEvent(store.event(id)!!.copy(start = at.minusHours(1)))
        assertNull(store.event(id)!!.notified30m)
        assertNull(store.event(id)!!.next24h)
    }

    @Test fun `pending reminders and processed occurrence survive reopening`() {
        val at = first.atTime(10, 0)
        val id = store.saveEvent(Event(title = "Exam", start = at))
        saveSchedule(store.event(id)!!, 1440, at)
        saveSchedule(store.event(id)!!, 30, at)
        assertTrue(markNotified(id, 1, at, 1440))
        helper.close()
        helper = DaymarkDatabase(ApplicationProvider.getApplicationContext(), "test.db")
        store = DaymarkStore(helper)
        assertEquals(at, store.event(id)!!.notified24h)
        assertEquals(reminder(at, 30).trigger, store.event(id)!!.next30m)
        assertNull(store.event(id)!!.next24h)
    }

    @Test fun `SQLite survives closing and reopening the app store`() {
        store.addTask("Remember me", Priority.P1, first)
        helper.close()
        helper = DaymarkDatabase(ApplicationProvider.getApplicationContext(), "test.db")
        store = DaymarkStore(helper)
        assertEquals("Remember me", store.tasks(first).single().title)
    }
}
