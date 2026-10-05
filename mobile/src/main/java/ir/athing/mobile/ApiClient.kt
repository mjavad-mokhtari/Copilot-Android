package ir.athing.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ApiException(val statusCode: Int, message: String) : IllegalStateException(message)

class SessionVault(context: Context) {
    private val prefs = context.getSharedPreferences("eting_secure_session", Context.MODE_PRIVATE)
    private val alias = "eting.mobile.session.v1"
    private val gson = com.google.gson.Gson()

    @Synchronized private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
            generateKey()
        }
    }

    fun get(name: String): String? = runCatching {
        val packed = prefs.getString(name, null) ?: return null
        val bytes = Base64.decode(packed, Base64.NO_WRAP)
        val iv = bytes.copyOfRange(0, 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }.getOrNull()

    fun put(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val packed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    fun remove(name: String) { prefs.edit().remove(name).apply() }
    fun clear() { prefs.edit().clear().apply() }
}

class SessionCookieJar(private val vault: SessionVault) : CookieJar {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.firstOrNull { it.name == "__Host-eting_session" }?.let { vault.put("session", it.value) }
    }
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (url.host != "athing.ir") return emptyList()
        val token = vault.get("session") ?: return emptyList()
        return listOf(Cookie.Builder().name("__Host-eting_session").value(token).hostOnlyDomain(url.host).path("/").secure().httpOnly().build())
    }
}

data class Reminder(val id: String, val title: String, val dueAt: String, val status: String)
data class ChatConversation(
    val sessionId: String,
    val title: String,
    val titleCustom: Boolean,
    val space: String,
    val project: String,
    val scope: String,
    val provider: String,
    val createdAt: Long,
    val updatedAt: Long,
)
data class ChatLine(
    val messageId: String,
    val role: String,
    val text: String,
    val createdAt: Long,
    val error: Boolean = false,
    val query: String = "",
    val answer: String = "",
    val sourcesJson: String = "[]",
    val taskItemsJson: String = "[]",
) {
    val fromUser: Boolean get() = role == "user"
}

class EtingApi(context: Context) {
    private val vault = SessionVault(context.applicationContext)
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .cookieJar(SessionCookieJar(vault))
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .writeTimeout(75, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()
    private val base = "https://athing.ir"
    private val refreshMutex = Mutex()
    private val sessionRefreshWindowMs = TimeUnit.HOURS.toMillis(24)
    private var sessionId = vault.get("chat_session") ?: java.util.UUID.randomUUID().toString().also { vault.put("chat_session", it) }
    fun activeChatSessionId(): String = sessionId
    fun setActiveChatSessionId(value: String) { sessionId = value; vault.put("chat_session", value) }

    private suspend fun call(method: String, path: String, payload: JsonObject? = null, authRequired: Boolean = true, retryAfterRefresh: Boolean = true): JsonObject = withContext(Dispatchers.IO) {
        val body = payload?.toString()?.toRequestBody(jsonType)
        val builder = Request.Builder().url(base + path).header("Origin", base).method(method, if (method == "GET") null else body ?: "{}".toRequestBody(jsonType))
        vault.get("csrf")?.takeIf { method != "GET" && path != "/api/v1/auth/login" }?.let { builder.header("X-CSRF-Token", it) }
        val response = client.newCall(builder.build()).execute().use { response ->
            Triple(response.code, response.isSuccessful, response.body?.string().orEmpty())
        }
        val (status, successful, text) = response
        val parsed = runCatching { JsonParser.parseString(text).asJsonObject }.getOrElse { JsonObject() }
        if (!successful) {
            val detail = parsed.get("detail")?.asString ?: "خطا در ارتباط با ائتینگ (" + status + ")"
            if (status == 401 && authRequired && retryAfterRefresh && path != "/api/v1/auth/refresh") {
                refreshSession()
                return@withContext call(method, path, payload, authRequired, false)
            }
            throw ApiException(status, detail)
        }
        if (authRequired && status == 204) throw ApiException(401, "نشست معتبر نیست.")
        parsed
    }
    suspend fun login(username: String, password: String): String {
        val payload = JsonObject().apply { addProperty("username", username); addProperty("password", password) }
        val result = call("POST", "/api/v1/auth/login", payload, false)
        result.get("csrf_token")?.asString?.let { vault.put("csrf", it) }
        return (result.get("username")?.asString ?: username).also {
            vault.put("account", it)
            vault.put("session_refreshed_at", System.currentTimeMillis().toString())
        }
    }
    suspend fun me(): String = call("GET", "/api/v1/auth/me").get("username")?.asString.orEmpty().also {
        if (it.isNotBlank()) vault.put("account", it)
    }
    fun cachedAccount(): String = vault.get("account").orEmpty()
    fun hasStoredSession(): Boolean = !vault.get("session").isNullOrBlank()
    fun clearAuth() { vault.remove("session"); vault.remove("csrf"); vault.remove("account"); vault.remove("session_refreshed_at") }
    private suspend fun refreshSessionLocked(): String {
        val result = call("POST", "/api/v1/auth/refresh", JsonObject(), false, false)
        result.get("csrf_token")?.asString?.let { vault.put("csrf", it) }
        val account = result.get("username")?.asString ?: cachedAccount()
        if (account.isNotBlank()) vault.put("account", account)
        vault.put("session_refreshed_at", System.currentTimeMillis().toString())
        return account
    }
    suspend fun refreshSession(): String = refreshMutex.withLock {
        if (!hasStoredSession()) throw ApiException(401, "نشست معتبر نیست.")
        refreshSessionLocked()
    }
    suspend fun refreshSessionIfDue(): String {
        if (!hasStoredSession()) return ""
        val account = refreshMutex.withLock {
            if (!hasStoredSession()) return@withLock ""
            val refreshedAt = vault.get("session_refreshed_at")?.toLongOrNull() ?: 0L
            if (refreshedAt > 0L && System.currentTimeMillis() - refreshedAt < sessionRefreshWindowMs) return@withLock cachedAccount()
            refreshSessionLocked()
        }
        return if (account.isNotBlank() || !hasStoredSession()) account else me()
    }
    suspend fun restoreSession(): String = refreshSessionIfDue()
    suspend fun logout() { runCatching { call("POST", "/api/v1/auth/logout", JsonObject()) }; clearAuth() }
    fun rememberPendingPushToken(token: String) {
        if (token.isNotBlank()) vault.put("pending_fcm_token", token)
    }

    suspend fun chatConversations(): List<ChatConversation> {
        val result = call("GET", "/api/v1/chat/history")
        return result.getAsJsonArray("conversations")?.mapNotNull { item ->
            runCatching {
                val row = item.asJsonObject
                ChatConversation(
                    sessionId = row.get("session_id").asString,
                    title = row.get("title")?.asString ?: "گفت‌وگوی جدید",
                    titleCustom = row.get("title_custom")?.asBoolean ?: false,
                    space = row.get("space")?.asString ?: "delivery",
                    project = row.get("project")?.asString ?: "",
                    scope = row.get("scope")?.asString ?: "team",
                    provider = row.get("provider")?.asString ?: "premium",
                    createdAt = row.get("created_at")?.asLong ?: 0L,
                    updatedAt = row.get("updated_at")?.asLong ?: 0L,
                )
            }.getOrNull()
        } ?: emptyList()
    }

    suspend fun chatMessages(sessionId: String): List<ChatLine> {
        val result = call("GET", "/api/v1/chat/history/$sessionId/messages")
        return result.getAsJsonArray("messages")?.mapNotNull { item ->
            runCatching {
                val row = item.asJsonObject
                ChatLine(
                    messageId = row.get("message_id").asString,
                    role = row.get("role").asString,
                    text = row.get("text").asString,
                    createdAt = row.get("created_at")?.asLong ?: 0L,
                    error = row.get("error")?.asBoolean ?: false,
                    query = row.get("query")?.asString ?: "",
                    answer = row.get("answer")?.asString ?: "",
                    sourcesJson = row.get("sources")?.toString() ?: "[]",
                    taskItemsJson = row.get("task_items")?.toString() ?: "[]",
                )
            }.getOrNull()
        } ?: emptyList()
    }

    suspend fun saveChatConversation(sessionId: String, title: String, titleCustom: Boolean = false) {
        val now = System.currentTimeMillis()
        val payload = JsonObject().apply {
            addProperty("session_id", sessionId); addProperty("title", title); addProperty("title_custom", titleCustom)
            addProperty("space", "delivery"); addProperty("project", ""); addProperty("scope", "team"); addProperty("provider", "premium")
            addProperty("created_at", now); addProperty("updated_at", now)
        }
        call("POST", "/api/v1/chat/history", payload)
    }

    suspend fun renameChatConversation(sessionId: String, title: String) {
        val current = chatConversations().firstOrNull { it.sessionId == sessionId }
        val now = System.currentTimeMillis()
        val payload = JsonObject().apply {
            addProperty("session_id", sessionId); addProperty("title", title); addProperty("title_custom", true)
            addProperty("space", current?.space ?: "delivery"); addProperty("project", current?.project ?: "")
            addProperty("scope", current?.scope ?: "team"); addProperty("provider", current?.provider ?: "premium")
            addProperty("created_at", current?.createdAt ?: now); addProperty("updated_at", now)
        }
        call("POST", "/api/v1/chat/history", payload)
    }

    suspend fun deleteChatConversation(sessionId: String) {
        call("DELETE", "/api/v1/chat/history/$sessionId", JsonObject())
    }

    private suspend fun saveChatMessages(sessionId: String, messages: List<ChatLine>) {
        if (messages.isEmpty()) return
        val rows = JsonArray()
        messages.forEach { message ->
            rows.add(JsonObject().apply {
                addProperty("message_id", message.messageId); addProperty("role", message.role); addProperty("text", message.text)
                addProperty("created_at", message.createdAt); addProperty("error", message.error)
                addProperty("query", message.query); addProperty("answer", message.answer)
                add("sources", runCatching { JsonParser.parseString(message.sourcesJson).asJsonArray }.getOrDefault(JsonArray()))
                add("task_items", runCatching { JsonParser.parseString(message.taskItemsJson).asJsonArray }.getOrDefault(JsonArray()))
            })
        }
        call("POST", "/api/v1/chat/history/$sessionId/messages", JsonObject().apply { add("messages", rows) })
    }

    suspend fun createChatConversation(): ChatConversation {
        val id = java.util.UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        saveChatConversation(id, "گفت‌وگوی جدید")
        setActiveChatSessionId(id)
        return ChatConversation(id, "گفت‌وگوی جدید", false, "delivery", "", "team", "premium", now, now)
    }

    suspend fun chat(text: String, sessionId: String, userMessageId: String): ChatLine {
        val now = System.currentTimeMillis()
        saveChatConversation(sessionId, text.take(38).ifBlank { "گفت‌وگوی جدید" })
        val userLine = ChatLine(userMessageId, "user", text, now)
        saveChatMessages(sessionId, listOf(userLine))
        val payload = JsonObject().apply {
            addProperty("query", text); addProperty("session_id", sessionId); addProperty("response_mode", "short")
            addProperty("space", "delivery"); addProperty("scope", "team"); addProperty("limit", 6)
            addProperty("include_sources", true); addProperty("use_global_if_empty", false)
        }
        val assistantLine = try {
            val result = call("POST", "/api/v1/chat", payload)
            val answer = result.get("answer")?.asString ?: "پاسخی دریافت نشد."
            ChatLine(java.util.UUID.randomUUID().toString(), "assistant", answer, System.currentTimeMillis(), query = text, answer = answer, sourcesJson = result.getAsJsonArray("sources")?.toString() ?: "[]")
        } catch (error: Exception) {
            ChatLine(java.util.UUID.randomUUID().toString(), "assistant", error.message ?: "ارتباط برقرار نشد.", System.currentTimeMillis(), true, query = text)
        }
        saveChatMessages(sessionId, listOf(assistantLine))
        return assistantLine
    }

    suspend fun reminders(): List<Reminder> {
        val result = call("GET", "/api/v1/tasks?status=open&limit=100")
        return result.getAsJsonArray("tasks")?.filter { item ->
            item.asJsonObject.get("kind")?.asString == "reminder"
        }?.mapNotNull { item ->
            runCatching { val obj = item.asJsonObject; Reminder(obj.get("id").asString, obj.get("title").asString, obj.get("due_at")?.asString ?: "", obj.get("status")?.asString ?: "open") }.getOrNull()
        } ?: emptyList()
    }

    suspend fun addReminder(title: String, dueAt: String) {
        val payload = JsonObject().apply { addProperty("kind", "reminder"); addProperty("title", title); addProperty("due_at", dueAt); addProperty("space", "delivery"); addProperty("scope", "team") }
        call("POST", "/api/v1/tasks", payload)
    }
    suspend fun completeReminder(id: String) {
        val payload = JsonObject().apply { addProperty("status", "done") }
        call("PATCH", "/api/v1/tasks/$id", payload)
    }
    suspend fun registerPushToken(token: String, deviceType: String) {
        rememberPendingPushToken(token)
        val payload = JsonObject().apply { addProperty("token", token); addProperty("device_type", deviceType) }
        call("POST", "/api/v1/mobile/devices", payload)
        vault.remove("pending_fcm_token")
    }

    suspend fun mobilePushDeviceCount(): Int = call("GET", "/api/v1/push/status")
        .get("mobile_device_count")?.asInt ?: 0

    suspend fun sendPushTest() {
        call("POST", "/api/v1/push/test", JsonObject())
    }
}
