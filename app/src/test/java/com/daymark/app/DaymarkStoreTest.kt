package com.daymark.app

import android.content.Context
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

    @Test fun `P0 carries through every missed day and rollover is idempotent`() {
        store.addTask("Prepare for exam", Priority.P0, first)
        val today = first.plusDays(4)
        repeat(3) { store.rollOver(today) }
        assertEquals(5, store.allTasks().size)
        assertEquals(1, store.allTasks().count { it.status == TaskStatus.OPEN })
        (0L..3L).forEach { offset ->
            val snapshot = store.tasks(first.plusDays(offset)).single()
            assertEquals(TaskStatus.MOVED, snapshot.status)
            assertEquals(first.plusDays(offset + 1), snapshot.movedTo)
        }
        assertEquals(first, store.tasks(today).single().originDay)
    }

    @Test fun `done and stale tasks never carry over`() {
        listOf(TaskStatus.DONE, TaskStatus.STALE).forEach { status ->
            store.addTask(status.name, Priority.P0, first)
            val task = store.tasks(first).last()
            store.updateTask(task.copy(status = status), first)
        }
        store.rollOver(first.plusDays(7))
        assertEquals(2, store.allTasks().size)
        assertTrue(store.tasks(first.plusDays(7)).isEmpty())
    }

    @Test fun `optional tasks wait indefinitely for explicit selection`() {
        val a = store.addTask("Read", Priority.P1, first)
        val b = store.addTask("Walk", Priority.P2, first)
        val today = first.plusDays(5)
        store.rollOver(today)
        assertEquals(setOf(a, b), store.pending(today).map { it.id }.toSet())
        store.carry(setOf(a), today)
        assertEquals(listOf(b), store.pending(today).map { it.id })
        assertEquals("Read", store.tasks(today).single().title)
        store.carry(setOf(a), today)
        assertEquals(1, store.tasks(today).size)
    }

    @Test fun `keeping in history dismisses review but allows carrying later`() {
        val id = store.addTask("Visit a museum", Priority.P2, first)
        store.dismiss(setOf(id))
        assertTrue(store.pending(first.plusDays(3)).isEmpty())
        assertEquals(TaskStatus.OPEN, store.tasks(first).single().status)
        store.carry(setOf(id), first.plusDays(4))
        assertEquals(TaskStatus.OPEN, store.tasks(first.plusDays(4)).single().status)
    }

    @Test fun `changing an old P1 to P0 immediately applies automatic carry-over`() {
        store.addTask("Important now", Priority.P1, first)
        val task = store.tasks(first).single()
        store.updateTask(task.copy(priority = Priority.P0), first.plusDays(2))
        assertEquals(Priority.P0, store.tasks(first.plusDays(2)).single().priority)
        assertEquals(3, store.allTasks().size)
        assertTrue(store.pending(first.plusDays(2)).isEmpty())
    }

    @Test fun `changing P0 to P2 stops automatic carry-over`() {
        store.addTask("Less urgent now", Priority.P0, first)
        val task = store.tasks(first).single()
        store.updateTask(task.copy(priority = Priority.P2), first)
        store.rollOver(first.plusDays(1))
        assertTrue(store.tasks(first.plusDays(1)).isEmpty())
        assertEquals(Priority.P2, store.pending(first.plusDays(1)).single().priority)
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

    @Test fun `future tasks are not brought forward or put in pending review`() {
        store.addTask("Later", Priority.P0, first.plusDays(3))
        store.addTask("Also later", Priority.P1, first.plusDays(3))
        store.rollOver(first)
        assertTrue(store.tasks(first).isEmpty())
        assertTrue(store.pending(first).isEmpty())
    }

    @Test fun `event edits invalidate old alarm versions and deletion removes delivery records`() {
        val event = Event(title = "Birthday", start = LocalDateTime.parse("2026-10-20T17:23"), repeat = Repeat.YEARLY)
        val id = store.saveEvent(event)
        assertEquals(event.start, store.event(id)!!.start)
        assertEquals(1L, store.event(id)!!.version)
        assertTrue(store.markDelivered(id, event.start, 30))
        assertFalse(store.markDelivered(id, event.start, 30))
        store.saveEvent(store.event(id)!!.copy(title = "Dinner"))
        assertEquals(2L, store.event(id)!!.version)
        store.deleteEvent(id)
        assertNull(store.event(id))
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM delivered", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
    }

    @Test fun `SQLite survives closing and reopening the app store`() {
        store.addTask("Remember me", Priority.P1, first)
        helper.close()
        helper = DaymarkDatabase(ApplicationProvider.getApplicationContext(), "test.db")
        store = DaymarkStore(helper)
        assertEquals("Remember me", store.tasks(first).single().title)
    }
}
