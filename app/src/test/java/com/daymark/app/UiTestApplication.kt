package com.daymark.app

import com.daymark.app.data.DaymarkDatabase
import com.daymark.app.data.DaymarkStore
import com.daymark.app.domain.*
import com.daymark.app.sync.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** In-process server for UI tests; no Supabase requests or real passwords are used. */
open class UiTestApplication : DaymarkApplication() {
    protected open val initiallySignedIn = true
    val server by lazy { DaymarkStore(DaymarkDatabase(this,"ui-server.db")) }
    private val user = "00000000-0000-0000-0000-000000000001"
    private val workspace = Workspace(SharedProject.WORKSPACE_ID,SharedProject.NAME,ZoneId.systemDefault().id,user)
    private fun session() = JSONObject().put("access_token","test-access").put("refresh_token","test-refresh")
        .put("expires_at",Long.MAX_VALUE).put("user",JSONObject().put("id",user).put("email",SharedProject.EMAIL))
    override fun createCloudClient(): CloudClient {
        val storage = object : CloudSessionStorage {
            var value: JSONObject? = if (initiallySignedIn) session() else null
            override fun read() = value
            override fun write(value: JSONObject?) { this.value = value }
        }
        return CloudClient(this,CloudTransport { url, _, _, body ->
            if (url.contains("/auth/v1/token")) {
                if (body.optString("password") != "friend-password") throw CloudFailure(400,"Invalid login credentials")
                session().put("expires_in",3600).toString()
            } else if (url.contains("/auth/v1/logout")) ""
            else {
                val payload = body.getJSONObject("payload")
                val today = LocalDate.now()
                when (body.getString("action")) {
                    "create" -> workspace.json().toString()
                    "list" -> JSONArray().put(workspace.json()).toString()
                    "snapshot" -> {
                        server.rollOver(today)
                        val tasks = JSONArray().apply { server.allTasks().forEach { task ->
                            put(CloudClient.taskJson(task).put("root_id",task.rootId).put("day",task.day.toString())
                                .put("origin_day",task.originDay.toString()).put("moved_to",task.movedTo?.toString() ?: JSONObject.NULL))
                        } }
                        val events = JSONArray().apply { server.events().forEach { put(CloudClient.eventJson(it)) } }
                        JSONObject().put("revision",System.nanoTime()).put("tasks",tasks).put("events",events).toString()
                    }
                    "add_task" -> { server.addTask(payload.getString("title"),Priority.valueOf(payload.getString("priority")),LocalDate.parse(payload.getString("day"))); "{}" }
                    "update_task" -> {
                        val task = server.allTasks().single { it.id == payload.getLong("id") }
                        server.updateTask(task.copy(title=payload.getString("title"),priority=Priority.valueOf(payload.getString("priority")),status=TaskStatus.valueOf(payload.getString("status"))),today)
                        "{}"
                    }
                    "delete_task" -> { server.deleteTask(payload.getLong("id")); "{}" }
                    "save_event" -> {
                        server.saveEvent(Event(id=payload.getLong("id"),title=payload.getString("title"),start=LocalDateTime.parse(payload.getString("start")),repeat=Repeat.valueOf(payload.getString("repeat"))))
                        "{}"
                    }
                    "delete_event" -> { server.deleteEvent(payload.getLong("id")); "{}" }
                    else -> error("Unexpected request in UI test")
                }
            }
        },storage).also { if (initiallySignedIn) it.selectWorkspace(workspace) }
    }
}
class LoggedOutTestApplication : UiTestApplication() {
    override val initiallySignedIn = false
}
