package com.daymark.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.daymark
import com.daymark.app.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
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
    val events: List<Event> = emptyList(),
    val notificationsAllowed: Boolean = false,
    val exactAllowed: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,
    val editorSaving: Boolean = false,
    val editorSaved: Boolean = false,
    val editorError: String? = null,
)

class DaymarkViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.daymark
    private val mutable = MutableStateFlow(DaymarkState())
    val state = mutable.asStateFlow()
    private val mutex = Mutex()

    init { refresh(true) }

    private fun runOperation(editor: Boolean = false, block: () -> Unit = {}) {
        if (editor) {
            if (mutable.value.editorSaving) return
            mutable.update { it.copy(editorSaving = true, editorSaved = false, editorError = null) }
        }
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
                            allTasks = app.store.allTasks(), events = app.store.events(),
                            notificationsAllowed = app.scheduler.notificationsAllowed, exactAllowed = app.scheduler.exactAllowed,
                            loading = false, editorSaving = if (editor) false else old.editorSaving,
                            editorSaved = editor || old.editorSaved,
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    val message = "Couldn't save your changes. Please try again."
                    mutable.update {
                        if (editor) it.copy(loading = false, editorSaving = false, editorError = message)
                        else it.copy(loading = false, error = message)
                    }
                }
            }
        }
    }

    fun refresh(reschedule: Boolean = false) = runOperation { if (reschedule) app.scheduler.refresh() }
    fun select(day: LocalDate) = runOperation { mutable.update { it.copy(selected = day) } }
    fun addTask(title: String, priority: Priority, day: LocalDate) = runOperation(editor = true) { app.store.addTask(title, priority, day) }
    fun updateTask(task: Task, fromEditor: Boolean = false) = runOperation(editor = fromEditor) { app.store.updateTask(task, LocalDate.now()) }
    fun deleteTask(id: Long) = runOperation(editor = true) { app.store.deleteTask(id) }
    fun saveEvent(event: Event) = runOperation(editor = true) { app.scheduler.save(event) }
    fun deleteEvent(id: Long) = runOperation(editor = true) { app.scheduler.delete(id) }
    fun clearEditorResult() { mutable.update { it.copy(editorSaved = false, editorError = null) } }
    fun clearError() { mutable.update { it.copy(error = null) } }
}
