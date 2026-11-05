package com.daymark.app

import android.app.AlarmManager
import androidx.test.core.app.ApplicationProvider
import com.daymark.app.domain.*
import com.daymark.app.sync.*
import com.daymark.app.ui.DaymarkViewModel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.util.ReflectionHelpers
import java.time.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class CloudWorkflowTest {
    private lateinit var app: DaymarkApplication
    private lateinit var originalCloud: Lazy<CloudClient>
    private val user = "00000000-0000-0000-0000-000000000001"
    private val workspace = Workspace(UUID.randomUUID().toString(), "Shared", "UTC", user)
    private val alarms get() = shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms
    private class MemorySession(private var value: JSONObject?) : CloudSessionStorage {
        override fun read() = value
        override fun write(value: JSONObject?) { this.value = value }
    }
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        originalCloud = ReflectionHelpers.getField(app, "cloud\$delegate")
        app.localStore.allTasks().forEach { app.localStore.deleteTask(it.id) }
        app.localStore.events().forEach { app.localStore.deleteEvent(it.id) }
        app.getSharedPreferences("cloud_config",0).edit().clear()
            .putString("url","https://test.supabase.co").putString("public_key","sb_publishable_test").commit()
    }
    @After fun restore() {
        ReflectionHelpers.setField(app,"cloud\$delegate",originalCloud)
    }
    private fun client(transport: CloudTransport): CloudClient {
        val session = JSONObject().put("access_token","access").put("refresh_token","refresh").put("expires_at",Long.MAX_VALUE)
            .put("user",JSONObject().put("id",user).put("email","test@example.com"))
        return CloudClient(app,transport,MemorySession(session)).also {
            ReflectionHelpers.setField(app,"cloud\$delegate",lazyOf(it))
        }
    }
    private fun await(check: () -> Boolean) {
        val deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
        while (!check() && System.nanoTime()<deadline) { ShadowLooper.runUiThreadTasks(); Thread.sleep(10) }
        assertTrue("Timed out waiting for operation",check())
    }
    @Test fun `queued shared edit cannot modify a same-ID local task after access is removed`() {
        val today = LocalDate.now(ZoneId.of("UTC"))
        val id = app.localStore.addTask("Local original",Priority.P0,today)
        val task = Task(id=id,rootId="shared",title="Shared original",priority=Priority.P0,day=today,syncVersion=4)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val c = client(CloudTransport { _, _, _, body ->
            assertEquals("snapshot",body.getString("action"))
            if (calls.incrementAndGet()>1) {
                entered.countDown()
                check(release.await(10,TimeUnit.SECONDS))
                throw CloudFailure(403,"Access removed")
            }
            val row = CloudClient.taskJson(task).put("root_id",task.rootId).put("day",today.toString())
                .put("origin_day",today.toString()).put("moved_to",JSONObject.NULL)
            JSONObject().put("revision",1).put("tasks",JSONArray().put(row)).put("events",JSONArray()).toString()
        })
        c.selectWorkspace(workspace)
        val model = DaymarkViewModel(app)
        await { !model.state.value.loading && !ReflectionHelpers.getField<Boolean>(model,"refreshQueued") }
        assertEquals("Shared original",model.state.value.tasks.single().title)
        model.refresh()
        await { entered.count==0L }
        model.updateTask(task.copy(title="Wrong target"),fromEditor=true)
        release.countDown()
        await { !model.state.value.editorSaving }
        assertEquals("Local original",app.localStore.allTasks().single().title)
        assertNotNull(model.state.value.editorError)
    }
    @Test fun `failed workspace creation leaves current reminders scheduled`() {
        app.localStore.saveEvent(Event(title="Existing event", start=LocalDateTime.now().plusDays(2).withSecond(0).withNano(0)))
        client(CloudTransport { _, _, _, _ -> throw CloudFailure(400,"Could not create list") })
        val model=DaymarkViewModel(app)
        await { !model.state.value.loading && !ReflectionHelpers.getField<Boolean>(model,"refreshQueued") }
        assertEquals(3,alarms.size)
        model.createWorkspace("New list")
        await { !model.state.value.cloud.busy }
        assertEquals(3,alarms.size)
    }
    @Test fun `old scheduler reference cannot recreate alarms after switching lists`() {
        app.localStore.saveEvent(Event(title="Local event", start=LocalDateTime.now().plusDays(2).withSecond(0).withNano(0)))
        val c=client(CloudTransport { _, _, _, _ -> "{}" })
        val old = app.scheduler
        old.refresh()
        c.selectWorkspace(workspace)
        app.scheduler.refresh()
        val currentId=app.store.storageId
        old.refresh()
        assertTrue(alarms.all { shadowOf(it.operation).savedIntent.getStringExtra("store_id")==currentId })
    }
}
