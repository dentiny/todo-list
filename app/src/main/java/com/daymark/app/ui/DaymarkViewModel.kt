package com.daymark.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.daymark
import com.daymark.app.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate

data class DaymarkState(
    val today: LocalDate = LocalDate.now(),
    val selected: LocalDate = today,
    val tasks: List<Task> = emptyList(),
    val allTasks: List<Task> = emptyList(),
    val pending: List<Task> = emptyList(),
    val events: List<Event> = emptyList(),
    val notificationsAllowed: Boolean = false,
    val exactAllowed: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,
)

class DaymarkViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.daymark
    private val mutable = MutableStateFlow(DaymarkState())
    val state = mutable.asStateFlow()
    private val mutex = Mutex()

    init { refresh(true) }

    private fun runOperation(block: () -> Unit = {}) {
        viewModelScope.launch {
            mutex.withLock {
                try {
                    withContext(Dispatchers.IO) {
                        block()
                        val today = LocalDate.now()
                        app.store.rollOver(today)
                        val old = mutable.value
                        val selected = if (old.selected == old.today) today else old.selected
                        mutable.value = old.copy(
                            today = today, selected = selected, tasks = app.store.tasks(selected),
                            allTasks = app.store.allTasks(), pending = app.store.pending(today), events = app.store.events(),
                            notificationsAllowed = app.scheduler.notificationsAllowed, exactAllowed = app.scheduler.exactAllowed,
                            loading = false,
                        )
                    }
                } catch (_: Exception) {
                    mutable.update { it.copy(loading = false, error = "Couldn't save your changes. Please try again.") }
                }
            }
        }
    }

    fun refresh(reschedule: Boolean = false) = runOperation { if (reschedule) app.scheduler.refresh() }
    fun select(day: LocalDate) = runOperation { mutable.update { it.copy(selected = day) } }
    fun addTask(title: String, priority: Priority, day: LocalDate) = runOperation { app.store.addTask(title, priority, day) }
    fun updateTask(task: Task) = runOperation { app.store.updateTask(task, LocalDate.now()) }
    fun deleteTask(id: Long) = runOperation { app.store.deleteTask(id) }
    fun carry(ids: Set<Long>) = runOperation { app.store.carry(ids, LocalDate.now()) }
    fun saveAndCarry(task: Task) = runOperation {
        val today = LocalDate.now()
        app.store.updateTask(task, today)
        app.store.carry(setOf(task.id), today)
    }
    fun dismiss(ids: Set<Long>) = runOperation { app.store.dismiss(ids) }
    fun saveEvent(event: Event) = runOperation { app.scheduler.save(event) }
    fun deleteEvent(id: Long) = runOperation { app.scheduler.delete(id) }
    fun clearError() { mutable.update { it.copy(error = null) } }
}
