package com.daymark.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.app.daymark
import com.daymark.app.domain.*
import com.daymark.app.sync.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

data class CloudUi(
    val url: String = "", val key: String = "", val email: String = "", val signedIn: Boolean = false,
    val workspace: Workspace? = null, val workspaces: List<Workspace> = emptyList(), val busy: Boolean = false,
    val message: String? = null, val syncStatus: String = "", val inviteCode: String? = null,
    val members: List<String> = emptyList(), val owner: Boolean = false, val pending: Boolean = false,
)
data class DaymarkState(
    val today: LocalDate = LocalDate.now(), val selected: LocalDate = today,
    val tasks: List<Task> = emptyList(), val allTasks: List<Task> = emptyList(), val events: List<Event> = emptyList(),
    val notificationsAllowed: Boolean = false, val exactAllowed: Boolean = false,
    val loading: Boolean = true, val error: String? = null,
    val editorSaving: Boolean = false, val editorSaved: Boolean = false, val editorError: String? = null,
    val storeId: String = "daymark.db",
    val cloud: CloudUi = CloudUi(), val zoneId: String = ZoneId.systemDefault().id,
)
class DaymarkViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.daymark
    private val mutable = MutableStateFlow(DaymarkState())
    val state = mutable.asStateFlow()
    private val mutex = Mutex()
    private var refreshQueued = false
    init { refresh(true) }

    private fun cloudInfo(old: CloudUi): CloudUi {
        val c = app.cloud
        return old.copy(url = c.url, key = c.publicKey, email = c.email, signedIn = c.signedIn,
            workspace = c.workspace, owner = c.signedIn && c.workspace?.owner == c.userId, pending = c.hasPending)
    }
    private fun loadState(editor: Boolean = false) {
        val store = app.store
        val scheduler = app.scheduler
        scheduler.refresh()
        val today = LocalDate.now(store.zone)
        val old = mutable.value
        val selected = if (old.selected == old.today || old.zoneId != store.zone.id) today else old.selected
        val tasks = store.tasks(selected)
        val allTasks = store.allTasks()
        val events = store.events()
        mutable.update { current ->
            current.copy(today = today, selected = selected, tasks = tasks, allTasks = allTasks, events = events,
                storeId = store.storageId, zoneId = store.zone.id, notificationsAllowed = scheduler.notificationsAllowed, exactAllowed = scheduler.exactAllowed,
                loading = false, cloud = cloudInfo(current.cloud), editorSaving = if (editor) false else current.editorSaving,
                editorSaved = editor || current.editorSaved)
        }
    }
    private fun sync() {
        if (app.cloud.activeStore == null) return
        try {
            app.cloud.snapshot()
            mutable.update { it.copy(cloud = it.cloud.copy(syncStatus = "Up to date")) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is CloudFailure && e.status == 403) {
                app.cloud.selectWorkspace(null)
                mutable.update { it.copy(error = "Your access to the shared list was removed. Showing your original local data.", cloud = cloudInfo(it.cloud).copy(syncStatus = "Access removed", inviteCode = null, members = emptyList())) }
            } else {
                mutable.update { it.copy(cloud = it.cloud.copy(syncStatus = "Sync unavailable · showing saved data")) }
            }
        }
    }
    private fun runOperation(editor: Boolean = false, write: Boolean = false, syncCloud: Boolean = false, onFinished: () -> Unit = {}, block: () -> Unit = {}) {
        val expectedStoreId = if (write) mutable.value.storeId else null
        if (editor) {
            if (mutable.value.editorSaving) return
            mutable.update { it.copy(editorSaving = true, editorSaved = false, editorError = null) }
        }
        viewModelScope.launch {
            mutex.withLock {
                try {
                    withContext(Dispatchers.IO) {
                        require(expectedStoreId == null || expectedStoreId == app.store.storageId) {
                            "The active list changed. Reopen the entry before editing."
                        }
                        block()
                        if (syncCloud) sync()
                        loadState(editor)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (e: Exception) {
                    if (e is CloudFailure && e.status == 409) withContext(Dispatchers.IO) { sync(); loadState() }
                    val message = e.message?.takeIf { e is CloudFailure || e is IllegalArgumentException }
                        ?: "Couldn't save your changes. Check your connection and try again."
                    mutable.update {
                        if (editor) it.copy(loading = false, editorSaving = false, editorError = message, cloud = cloudInfo(it.cloud))
                        else it.copy(loading = false, error = message, cloud = cloudInfo(it.cloud))
                    }
                } finally { onFinished() }
            }
        }
    }
    fun refresh(reschedule: Boolean = false) {
        if (refreshQueued) return
        refreshQueued = true
        runOperation(syncCloud = true, onFinished = { refreshQueued = false }) { if (reschedule) app.scheduler.refresh() }
    }
    fun select(day: LocalDate) = runOperation { mutable.update { it.copy(selected = day) } }
    fun addTask(title: String, priority: Priority, day: LocalDate) = runOperation(editor = true, write = true, syncCloud = true) {
        if (app.cloud.activeStore == null) app.store.addTask(title, priority, day)
        else app.cloud.mutate("add_task", JSONObject().put("title", title.trim()).put("priority", priority.name).put("day", day.toString()))
    }
    fun updateTask(task: Task, fromEditor: Boolean = false) = runOperation(editor = fromEditor, write = true, syncCloud = true) {
        if (app.cloud.activeStore == null) app.store.updateTask(task, LocalDate.now())
        else app.cloud.mutate("update_task", CloudClient.taskJson(task))
    }
    fun deleteTask(id: Long, expectedVersion: Long? = null) = runOperation(editor = true, write = true, syncCloud = true) {
        if (app.cloud.activeStore == null) app.store.deleteTask(id)
        else app.cloud.mutate("delete_task", CloudClient.taskJson(app.store.allTasks().single { it.id == id }).apply { expectedVersion?.let { put("sync_version", it) } })
    }
    fun saveEvent(event: Event) = runOperation(editor = true, write = true, syncCloud = true) {
        if (app.cloud.activeStore == null) app.scheduler.save(event)
        else app.cloud.mutate("save_event", CloudClient.eventJson(event))
    }
    fun deleteEvent(id: Long, expectedVersion: Long? = null) = runOperation(editor = true, write = true, syncCloud = true) {
        if (app.cloud.activeStore == null) app.scheduler.delete(id)
        else app.cloud.mutate("delete_event", CloudClient.eventJson(app.store.event(id) ?: error("Event unavailable")).apply { expectedVersion?.let { put("version", it) } })
    }
    fun clearEditorResult() { mutable.update { it.copy(editorSaved = false, editorError = null) } }
    fun clearError() { mutable.update { it.copy(error = null) } }

    private fun cloudOperation(block: () -> String?) {
        if (mutable.value.cloud.busy) return
        mutable.update { it.copy(cloud = it.cloud.copy(busy = true, message = null)) }
        viewModelScope.launch {
            mutex.withLock {
                try {
                    withContext(Dispatchers.IO) {
                        val message = block()
                        sync()
                        loadState()
                        mutable.update { it.copy(cloud = cloudInfo(it.cloud).copy(busy = false, message = message)) }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    withContext(Dispatchers.IO) { loadState() }
                    mutable.update { it.copy(cloud = cloudInfo(it.cloud).copy(busy = false,
                        message = if (e is CloudFailure || e is IllegalArgumentException) e.message else "Connection failed. Check your network and try again.")) }
                }
            }
        }
    }
    private fun refreshWorkspaceList() {
        val lists = app.cloud.workspaces()
        mutable.update { it.copy(cloud = it.cloud.copy(workspaces = lists)) }
    }
    fun configureCloud(url: String, key: String) = cloudOperation { app.cloud.configure(url, key); "Project saved. Sign in below." }
    fun authenticate(email: String, password: String, register: Boolean) = cloudOperation {
        val message = app.cloud.authenticate(email, password, register)
        if (app.cloud.signedIn) refreshWorkspaceList()
        message
    }
    fun listWorkspaces() = cloudOperation {
        if (app.cloud.signedIn) refreshWorkspaceList()
        null
    }
    fun createWorkspace(name: String) = cloudOperation {
        app.cloud.create(name)
        refreshWorkspaceList()
        mutable.update { it.copy(cloud = it.cloud.copy(inviteCode = null, members = emptyList())) }
        "Shared list created. Use Show invitation to invite someone."
    }
    fun joinWorkspace(code: String) = cloudOperation {
        app.cloud.join(code)
        refreshWorkspaceList()
        mutable.update { it.copy(cloud = it.cloud.copy(inviteCode = null, members = emptyList())) }
        "Joined shared list."
    }
    fun selectWorkspace(workspace: Workspace?) = cloudOperation {
        app.cloud.selectWorkspace(workspace)
        mutable.update { it.copy(cloud = it.cloud.copy(inviteCode = null, members = emptyList(), syncStatus = "")) }
        null
    }
    fun signOut() = cloudOperation {
        app.cloud.signOut()
        mutable.update { it.copy(cloud = CloudUi()) }
        "Signed out. Your original local data is available on this phone."
    }
    fun showInvite(rotate: Boolean = false) = cloudOperation {
        val result = app.cloud.invite(rotate)
        val members = result.getJSONArray("members")
        mutable.update { it.copy(cloud = it.cloud.copy(inviteCode = result.getString("code"), members = (0 until members.length()).map(members::getString))) }
        if (rotate) "Previous invitation code is now invalid." else null
    }
    fun removeMember(id: String) = cloudOperation {
        app.cloud.removeMember(id)
        val result = app.cloud.invite()
        val members = result.getJSONArray("members")
        mutable.update { it.copy(cloud = it.cloud.copy(members = (0 until members.length()).map(members::getString))) }
        "Member removed. Rotate the invitation code to prevent them rejoining."
    }
    fun importLocal() = cloudOperation {
        app.localStore.rollOver(LocalDate.now()); app.cloud.importLocal(app.localStore)
        "Local data imported. The originals remain on this phone. Repeating this import does not duplicate entries."
    }
    fun retryPending() = cloudOperation { app.cloud.retryPending(); "Pending save completed." }
}
