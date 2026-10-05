package ir.athing.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.JsonObject
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
data class SyncedChatLine(val text: String, val fromUser: Boolean)

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
    private var sessionId = vault.get("chat_session") ?: java.util.UUID.randomUUID().toString().also { vault.put("chat_session", it) }

    private suspend fun call(method: String, path: String, payload: JsonObject? = null, authRequired: Boolean = true): JsonObject = withContext(Dispatchers.IO) {
        val body = payload?.toString()?.toRequestBody(jsonType)
        val builder = Request.Builder().url(base + path).header("Origin", base).method(method, if (method == "GET") null else body ?: "{}".toRequestBody(jsonType))
        vault.get("account")?.takeIf { it.isNotBlank() }?.let { builder.header("X-Eting-Account", it) }
        vault.get("csrf")?.takeIf { method != "GET" && path != "/api/v1/auth/login" }?.let { builder.header("X-CSRF-Token", it) }
        val response = client.newCall(builder.build()).execute()
        response.use {
            val text = it.body?.string().orEmpty()
            val parsed = runCatching { JsonParser.parseString(text).asJsonObject }.getOrElse { JsonObject() }
            if (!it.isSuccessful) {
                val detail = parsed.get("detail")?.asString ?: "خطا در ارتباط با ائتینگ (${it.code})"
                throw ApiException(it.code, detail)
            }
            if (authRequired && it.code == 204) throw IllegalStateException("نشست معتبر نیست.")
            parsed
        }
    }

    suspend fun login(username: String, password: String): String {
        val payload = JsonObject().apply { addProperty("username", username); addProperty("password", password) }
        val result = call("POST", "/api/v1/auth/login", payload, false)
        result.get("csrf_token")?.asString?.let { vault.put("csrf", it) }
        return (result.get("username")?.asString ?: username).also { vault.put("account", it) }
    }
    suspend fun me(): String = (call("GET", "/api/v1/auth/me").get("username")?.asString ?: "").also {
        if (it.isNotBlank()) vault.put("account", it)
    }
    suspend fun refreshSession(): String {
        val result = call("POST", "/api/v1/auth/refresh", JsonObject())
        result.get("csrf_token")?.asString?.let { vault.put("csrf", it) }
        return (result.get("username")?.asString ?: "").also { if (it.isNotBlank()) vault.put("account", it) }
    }
    fun cachedAccount(): String = vault.get("account").orEmpty()
    fun hasStoredSession(): Boolean = !vault.get("session").isNullOrBlank()
    fun clearAuth() { vault.remove("session"); vault.remove("csrf"); vault.remove("account") }
    suspend fun logout() { runCatching { call("POST", "/api/v1/auth/logout", JsonObject()) }; clearAuth() }

    fun rememberPendingPushToken(token: String) {
        if (token.isNotBlank()) vault.put("pending_fcm_token", token)
    }

    suspend fun chat(text: String): String {
        val payload = JsonObject().apply {
            addProperty("query", text); addProperty("session_id", sessionId); addProperty("response_mode", "short")
            addProperty("space", "delivery"); addProperty("scope", "team"); addProperty("limit", 6)
            addProperty("include_sources", true); addProperty("use_global_if_empty", false)
        }
        val result = call("POST", "/api/v1/chat", payload)
        return result.get("answer")?.asString ?: "پاسخی دریافت نشد."
    }

    suspend fun chatHistory(): List<SyncedChatLine> {
        val sessions = call("GET", "/api/v1/chat/sync/sessions?limit=80").getAsJsonArray("sessions")
        sessions?.firstOrNull()?.asJsonObject?.get("session_id")?.asString?.takeIf { it.isNotBlank() }?.let {
            sessionId = it
            vault.put("chat_session", it)
        }
        val result = call("GET", "/api/v1/chat/sync/sessions/$sessionId?limit=200")
        return result.getAsJsonArray("messages")?.mapNotNull { item ->
            runCatching {
                val obj = item.asJsonObject
                SyncedChatLine(
                    text = obj.get("text")?.asString.orEmpty(),
                    fromUser = obj.get("role")?.asString == "user",
                )
            }.getOrNull()?.takeIf { it.text.isNotBlank() }
        } ?: emptyList()
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
