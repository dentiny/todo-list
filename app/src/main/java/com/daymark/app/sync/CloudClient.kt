package com.daymark.app.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.daymark.app.data.DaymarkDatabase
import com.daymark.app.data.DaymarkStore
import com.daymark.app.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.time.*
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CloudFailure(val status: Int, message: String) : Exception(message)
data class Workspace(val id: String, val name: String, val zone: String, val owner: String) {
    fun json() = JSONObject().put("id", id).put("name", name).put("time_zone", zone).put("owner_id", owner)
    companion object { fun parse(j: JSONObject) = Workspace(j.getString("id"), j.getString("name"), j.getString("time_zone"), j.getString("owner_id")) }
}

/** Refresh and access tokens are encrypted using a non-exportable Android Keystore key. */
interface CloudSessionStorage {
    fun read(): JSONObject?
    fun write(value: JSONObject?)
}
private class SessionVault(context: Context) : CloudSessionStorage {
    private val prefs = context.getSharedPreferences("cloud_session", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("daymark-session", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("daymark-session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun read(): JSONObject? {
        val value = prefs.getString("session", null) ?: return null
        return runCatching {
            val parts = value.split(":")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            JSONObject(String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8))
        }.getOrNull()
    }
    override fun write(value: JSONObject?) {
        if (value == null) { check(prefs.edit().remove("session").commit()); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(prefs.edit().putString("session", encrypted).commit())
    }
}

fun interface CloudTransport {
    fun post(url: String, key: String, token: String?, body: JSONObject): String
}
class HttpCloudTransport : CloudTransport {
    override fun post(url: String, key: String, token: String?, body: JSONObject): String {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000; connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("apikey", key)
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val error = runCatching { JSONObject(response) }.getOrNull()
                throw CloudFailure(status, error?.optString("msg")?.takeIf { it.isNotBlank() }
                    ?: error?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: "Cloud request failed ($status). Please try again.")
            }
            return response
        } finally { connection.disconnect() }
    }
}

/** All entry points are called under the ViewModel mutex, off the main thread. */
class CloudClient(private val context: Context, private val transport: CloudTransport = HttpCloudTransport(), sessionStorage: CloudSessionStorage? = null) {
    private val prefs = context.getSharedPreferences("cloud_config", Context.MODE_PRIVATE)
    private val vault = sessionStorage ?: SessionVault(context)
    private var session = vault.read()
    var workspace: Workspace? = prefs.getString("workspace", null)?.let { runCatching { Workspace.parse(JSONObject(it)) }.getOrNull() }
        private set
    private var cache: DaymarkStore? = null
    private var cacheRevision: Long? = null
    val url get() = prefs.getString("url", "").orEmpty()
    val publicKey get() = prefs.getString("public_key", "").orEmpty()
    val signedIn get() = session != null
    val email get() = session?.optJSONObject("user")?.optString("email").orEmpty()
    val userId get() = session?.optJSONObject("user")?.optString("id").orEmpty()
    val hasPending get() = prefs.contains(pendingKey())
    val activeStore: DaymarkStore? @Synchronized get() {
        val w = workspace ?: return null
        if (!signedIn) return null
        return cache ?: DaymarkStore(DaymarkDatabase(context, "shared-${scope()}.db"), false, ZoneId.of(w.zone)).also { cache = it }
    }
    private fun scope() = MessageDigest.getInstance("SHA-256").digest("$url|$userId|${workspace?.id}".toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun pendingKey() = "pending-${scope()}"
    fun configure(endpoint: String, key: String) {
        require(!signedIn) { "Sign out before changing projects." }
        validateConfiguration(endpoint, key)
        check(prefs.edit().putString("url", endpoint.trim().trimEnd('/')).putString("public_key", key.trim()).remove("workspace").commit())
        workspace = null; cache = null; cacheRevision = null
    }
    fun authenticate(email: String, password: String, register: Boolean): String {
        require(url.isNotBlank()) { "Add your Supabase project URL and public key first." }
        val result = JSONObject(transport.post(url + if (register) "/auth/v1/signup" else "/auth/v1/token?grant_type=password",
            publicKey, null, JSONObject().put("email", email.trim()).put("password", password)))
        if (!result.has("access_token")) return "Check your email to confirm your account, then sign in."
        setSession(result)
        // Never reuse another user's selected workspace or cache.
        selectWorkspace(null)
        return "Signed in. Create a shared list or join with an invitation code."
    }
    private fun setSession(value: JSONObject) {
        value.put("expires_at", Instant.now().epochSecond + value.optLong("expires_in", 3600))
        vault.write(value); session = value
    }
    private fun token(force: Boolean = false): String {
        val current = session ?: throw CloudFailure(401, "Please sign in again.")
        if (force || current.optLong("expires_at") <= Instant.now().epochSecond + 60) {
            val refreshed = try {
                JSONObject(transport.post("$url/auth/v1/token?grant_type=refresh_token", publicKey, null,
                    JSONObject().put("refresh_token", current.getString("refresh_token"))))
            } catch (e: CloudFailure) {
                if (e.status == 400 || e.status == 401) throw CloudFailure(401, "Your session expired. Sign out and sign in again.")
                throw e
            }
            setSession(refreshed)
        }
        return session!!.getString("access_token")
    }
    private fun rpc(action: String, payload: JSONObject = JSONObject(), target: String? = workspace?.id): String {
        val body = JSONObject().put("action", action).put("workspace", target ?: JSONObject.NULL).put("payload", payload)
        val access = token()
        return try { transport.post("$url/rest/v1/rpc/daymark_rpc", publicKey, access, body) }
        catch (e: CloudFailure) {
            if (e.status != 401) throw e
            transport.post("$url/rest/v1/rpc/daymark_rpc", publicKey, token(true), body)
        }
    }
    fun workspaces(): List<Workspace> = JSONArray(rpc("list")).objects().map(Workspace::parse)
    fun create(name: String): Workspace = Workspace.parse(JSONObject(rpc("create", JSONObject()
        .put("id", UUID.randomUUID().toString()).put("name", name).put("time_zone", ZoneId.systemDefault().id))))
        .also { selectWorkspace(it) }
    fun join(code: String): Workspace = Workspace.parse(JSONObject(rpc("join", JSONObject().put("code", code.trim())))).also { selectWorkspace(it) }
    @Synchronized fun selectWorkspace(value: Workspace?) {
        check(prefs.edit().apply { if (value == null) remove("workspace") else putString("workspace", value.json().toString()) }.commit())
        cache = null; cacheRevision = null; workspace = value
    }
    fun signOut() {
        // Local sign-out works offline. Tokens are erased even when server logout fails.
        runCatching { transport.post("$url/auth/v1/logout?scope=local", publicKey, token(), JSONObject()) }
        vault.write(null); session = null; selectWorkspace(null)
    }
    fun snapshot() {
        val store = activeStore ?: error("Choose a shared list first.")
        val payload = JSONObject()
        cacheRevision?.let { payload.put("revision", it) }
        val result = JSONObject(rpc("snapshot", payload))
        if (result.optBoolean("unchanged")) return
        val tasks = result.getJSONArray("tasks").objects().map { j -> Task(
            id = j.getLong("id"), rootId = j.getString("root_id"), title = j.getString("title"),
            priority = Priority.valueOf(j.getString("priority")), status = TaskStatus.valueOf(j.getString("status")),
            day = LocalDate.parse(j.getString("day")), originDay = LocalDate.parse(j.getString("origin_day")),
            movedTo = if (j.isNull("moved_to")) null else LocalDate.parse(j.getString("moved_to")), syncVersion = j.getLong("sync_version")) }
        val events = result.getJSONArray("events").objects().map { j -> Event(id = j.getLong("id"), title = j.getString("title"),
            start = LocalDateTime.parse(j.getString("start")), repeat = Repeat.valueOf(j.getString("repeat")), version = j.getLong("version")) }
        store.replaceSnapshot(tasks, events)
        cacheRevision = result.getLong("revision")
    }
    fun mutate(action: String, payload: JSONObject) {
        val key = pendingKey()
        val fingerprint = action + "|" + payload.toString()
        val pending = prefs.getString(key, null)?.let(::JSONObject)
        require(pending == null || pending.getString("fingerprint") == fingerprint) {
            "A previous save needs retrying. Open Shared lists and tap Retry pending save first."
        }
        val request = pending ?: JSONObject().put("action", action).put("fingerprint", fingerprint)
            .put("payload", payload.put("request_id", UUID.randomUUID().toString()))
        if (action == "add_task" && !request.getJSONObject("payload").has("root_id")) request.getJSONObject("payload").put("root_id", UUID.randomUUID().toString())
        check(prefs.edit().putString(key, request.toString()).commit())
        sendPending(request, key)
    }
    fun retryPending() {
        val key = pendingKey()
        prefs.getString(key, null)?.let { sendPending(JSONObject(it), key) }
    }
    private fun sendPending(request: JSONObject, key: String) {
        try { rpc(request.getString("action"), request.getJSONObject("payload")) }
        catch (e: CloudFailure) {
            // A returned database error rolled back the entire transaction. Network errors are uncertain.
            if (e.status in listOf(400, 403, 404, 409, 422)) check(prefs.edit().remove(key).commit())
            throw e
        }
        check(prefs.edit().remove(key).commit())
    }
    fun invite(rotate: Boolean = false): JSONObject = JSONObject(rpc(if (rotate) "rotate_invite" else "invite"))
    fun removeMember(id: String) { rpc("remove_member", JSONObject().put("user_id", id)) }
    fun importLocal(store: DaymarkStore) {
        val key = "import-${scope()}"
        val requestId = prefs.getString(key, null) ?: UUID.randomUUID().toString().also { check(prefs.edit().putString(key, it).commit()) }
        val tasks = JSONArray().apply { store.allTasks().forEach { t -> put(taskJson(t).put("root_id",t.rootId)
            .put("day",t.day.toString()).put("origin_day",t.originDay.toString()).put("moved_to", t.movedTo?.toString() ?: JSONObject.NULL)) } }
        val events = JSONArray().apply { store.events().forEach { put(eventJson(it)) } }
        rpc("import", JSONObject().put("request_id", requestId).put("tasks", tasks).put("events", events))
    }
    companion object {
        fun validateConfiguration(endpoint: String, key: String) {
            val uri = runCatching { URI(endpoint.trim().trimEnd('/')) }.getOrNull()
            require(uri != null && uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
                uri.query == null && uri.fragment == null && uri.path.orEmpty().isEmpty()) { "Use your HTTPS Supabase project URL without a path." }
            val clean = key.trim()
            val anon = if (clean.startsWith("eyJ")) runCatching {
                JSONObject(String(Base64.decode(clean.split('.')[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))).optString("role") == "anon"
            }.getOrDefault(false) else false
            require(clean.startsWith("sb_publishable_") || anon) { "Use a publishable key or legacy anon key. Never enter a secret or service_role key." }
        }
        fun taskJson(t: Task) = JSONObject().put("id",t.id).put("title",t.title).put("priority",t.priority.name).put("status",t.status.name).put("sync_version",t.syncVersion)
        fun eventJson(e: Event) = JSONObject().put("id",e.id).put("title",e.title).put("start",e.start.toString()).put("repeat",e.repeat.name).put("version",e.version)
    }
}
private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
