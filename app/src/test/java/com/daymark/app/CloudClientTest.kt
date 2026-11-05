package com.daymark.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.daymark.app.sync.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CloudClientTest {
    private lateinit var context: Context
    private val user = "00000000-0000-0000-0000-000000000001"
    private val list = Workspace("00000000-0000-0000-0000-000000000004", "Shared", "UTC", user)
    private class MemorySession(var value: JSONObject?) : CloudSessionStorage {
        override fun read() = value
        override fun write(value: JSONObject?) { this.value = value }
    }
    private fun session(expires: Long = Long.MAX_VALUE) = JSONObject().put("access_token","access").put("refresh_token","refresh")
        .put("expires_at",expires).put("user",JSONObject().put("id",user).put("email","test@example.com"))
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("cloud_config",Context.MODE_PRIVATE).edit().clear()
            .putString("url","https://test.supabase.co").putString("public_key","sb_publishable_test")
            .putString("workspace",list.json().toString()).commit()
    }
    @Test fun `configuration rejects privileged keys and unsafe URLs`() {
        CloudClient.validateConfiguration("https://test.supabase.co/", "sb_publishable_test")
        listOf("http://test.supabase.co", "https://user:pass@test.supabase.co", "https://test.supabase.co/path", "https://test.supabase.co?x=1").forEach { url ->
            assertThrows(IllegalArgumentException::class.java) { CloudClient.validateConfiguration(url,"sb_publishable_test") }
        }
        assertThrows(IllegalArgumentException::class.java) { CloudClient.validateConfiguration("https://test.supabase.co","sb_secret_never") }
        val privileged = "eyJhbGciOiJIUzI1NiJ9.eyJyb2xlIjoic2VydmljZV9yb2xlIn0.signature"
        assertThrows(IllegalArgumentException::class.java) { CloudClient.validateConfiguration("https://test.supabase.co",privileged) }
    }
    @Test fun `uncertain writes retain the same mutation UUID on retry`() {
        var offline = true
        val ids = mutableListOf<String>()
        val client = CloudClient(context, CloudTransport { _, _, _, body ->
            ids += body.getJSONObject("payload").getString("request_id")
            if (offline) throw IOException("offline")
            "{}"
        }, MemorySession(session()))
        val payload = JSONObject().put("title","Task").put("priority","P0").put("day","2026-11-05")
        assertThrows(IOException::class.java) { client.mutate("add_task",payload) }
        assertTrue(client.hasPending)
        // A different edit cannot replace a save whose outcome is uncertain.
        assertThrows(IllegalArgumentException::class.java) { client.mutate("delete_task",JSONObject().put("id",1)) }
        offline = false
        client.retryPending()
        assertEquals(2,ids.size)
        assertEquals(ids[0],ids[1])
        assertFalse(client.hasPending)
    }
    @Test fun `database conflicts clear pending saves without retrying stale edits`() {
        val client = CloudClient(context, CloudTransport { _, _, _, _ -> throw CloudFailure(409,"Task changed") }, MemorySession(session()))
        assertThrows(CloudFailure::class.java) { client.mutate("update_task",JSONObject().put("id",1)) }
        assertFalse(client.hasPending)
    }
    @Test fun `expired access token is refreshed and new refresh token persisted`() {
        val vault = MemorySession(session(0))
        val calls = mutableListOf<String>()
        val client = CloudClient(context, CloudTransport { url, _, token, body ->
            calls += url
            if (url.contains("grant_type=refresh_token")) {
                assertEquals("refresh",body.getString("refresh_token"))
                session().put("access_token","new-access").put("refresh_token","new-refresh").put("expires_in",3600).toString()
            } else { assertEquals("new-access",token); "[]" }
        },vault)
        assertTrue(client.workspaces().isEmpty())
        assertEquals(2,calls.size)
        assertEquals("new-refresh",vault.value!!.getString("refresh_token"))
    }
    @Test fun `shared caches are isolated across accounts and workspaces`() {
        val client = CloudClient(context, CloudTransport { _, _, _, _ -> "{}" },MemorySession(session()))
        val first = client.activeStore!!
        val otherList = list.copy(id="00000000-0000-0000-0000-000000000005")
        client.selectWorkspace(otherList)
        assertNotEquals(first.storageId,client.activeStore!!.storageId)
        client.selectWorkspace(list)
        val otherUser = CloudClient(context, CloudTransport { _, _, _, _ -> "{}" },MemorySession(session().put("user",JSONObject().put("id","different-user"))))
        assertNotEquals(first.storageId,otherUser.activeStore!!.storageId)
    }
    @Test fun `unchanged revision skips replacing the shared cache`() {
        var calls = 0
        val client = CloudClient(context, CloudTransport { _, _, _, body ->
            calls++
            if (calls == 1) {
                JSONObject().put("revision",5).put("tasks",org.json.JSONArray()).put("events",org.json.JSONArray()).toString()
            } else {
                assertEquals(5,body.getJSONObject("payload").getLong("revision"))
                JSONObject().put("revision",5).put("unchanged",true).toString()
            }
        },MemorySession(session()))
        client.snapshot()
        client.snapshot()
        assertEquals(2,calls)
        assertTrue(client.activeStore!!.allTasks().isEmpty())
    }

}
