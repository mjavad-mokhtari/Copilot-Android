package ir.athing.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.google.gson.Gson
import androidx.compose.ui.unit.dp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val api = EtingApi(applicationContext)
        setContent { EtingApp(api) }
    }
}

private data class ChatLine(val text: String, val fromUser: Boolean)
private enum class AppTab { CHAT, REMINDERS, SETTINGS }

@Composable
private fun EtingApp(api: EtingApi) {
    var username by remember { mutableStateOf(api.cachedAccount()) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(api.hasStoredSession() && api.cachedAccount().isBlank()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        if (api.hasStoredSession()) {
            runCatching { api.refreshSession() }
                .onSuccess { refreshed -> if (refreshed.isNotBlank()) username = refreshed }
                .onFailure { failure ->
                    if (failure is ApiException && failure.statusCode == 401) {
                        api.clearAuth()
                        username = ""
                    }
                }
        }
        loading = false
    }
    MaterialTheme(colorScheme = lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF6750A4), secondary = androidx.compose.ui.graphics.Color(0xFF625B71), background = androidx.compose.ui.graphics.Color(0xFFF8F7FA))) {
        CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl) {
            if (loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else if (username.isBlank()) LoginScreen(api, onLoggedIn = { username = it }, onError = { error = it }, error = error)
            else MainShell(
                api,
                username,
                onLogout = { scope.launch { api.logout(); username = "" } },
                onSessionExpired = { api.clearAuth(); username = "" },
            )
        }
    }
}

@Composable
private fun LoginScreen(api: EtingApi, onLoggedIn: (String) -> Unit, onError: (String) -> Unit, error: String) {
    var username by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("ائتینگ", style = MaterialTheme.typography.headlineLarge)
        Text("همراه چت و یادآورهای شما", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))
        OutlinedTextField(username, { username = it }, label = { Text("نام کاربری") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text("رمز عبور") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        Button(onClick = { busy = true; onError(""); scope.launch { runCatching { api.login(username.trim(), password) }.onSuccess { onLoggedIn(it); runCatching { FirebaseMessaging.getInstance().token }.onSuccess { tokenTask -> tokenTask.addOnSuccessListener { token -> scope.launch { runCatching { api.registerPushToken(token, "phone") } } } } }.onFailure { onError(it.message ?: "ورود ناموفق بود") }; busy = false } }, enabled = !busy && username.isNotBlank() && password.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("ورود")
        }
    }
}

@Composable
private fun MainShell(api: EtingApi, username: String, onLogout: () -> Unit, onSessionExpired: () -> Unit) {
    var tab by remember { mutableStateOf(AppTab.CHAT) }
    var pushStatus by remember { mutableStateOf("در حال ثبت این دستگاه برای اعلان‌ها…") }
    var mobileDeviceCount by remember { mutableIntStateOf(0) }
    var pushTestBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var notificationsAllowed by remember {
        mutableStateOf(Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsAllowed = granted
        pushStatus = if (granted) "اجازهٔ اعلان فعال است؛ وضعیت ثبت دستگاه در حال بررسی است." else "اجازهٔ اعلان داده نشد؛ اعلان‌ها روی این گوشی نمایش داده نمی‌شوند."
    }
    fun syncPushDevice() {
        val tokenTask = runCatching { FirebaseMessaging.getInstance().token }.getOrElse {
            pushStatus = "اعلان فشاری پیکربندی نشده است؛ فایل Firebase مخصوص این برنامه لازم است."
            return
        }
        pushStatus = "در حال دریافت و ثبت توکن اعلان…"
        tokenTask.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                pushStatus = "دریافت توکن Firebase ناموفق بود؛ اتصال Google Play Services را بررسی کن."
            } else {
                scope.launch {
                    runCatching { api.registerPushToken(task.result, "phone") }
                        .onSuccess {
                            runCatching { api.mobilePushDeviceCount() }
                                .onSuccess { count -> mobileDeviceCount = count; pushStatus = "این گوشی برای اعلان ثبت شد." }
                                .onFailure { pushStatus = "توکن ثبت شد، اما خواندن وضعیت اعلان ناموفق بود." }
                        }
                        .onFailure { pushStatus = "ثبت توکن اعلان ناموفق بود: ${it.message ?: "خطای نامشخص"}" }
                }
            }
        }
    }
    LaunchedEffect(username) { syncPushDevice() }
    DisposableEffect(lifecycleOwner, username) {
        var refreshJob: kotlinx.coroutines.Job? = null
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && refreshJob?.isActive != true) {
                refreshJob = scope.launch {
                    runCatching { api.refreshSession() }.onFailure { failure ->
                        if (failure is ApiException && failure.statusCode == 401) onSessionExpired()
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); refreshJob?.cancel() }
    }
    Scaffold(bottomBar = {
        NavigationBar {
            NavigationBarItem(tab == AppTab.CHAT, { tab = AppTab.CHAT }, icon = { Icon(Icons.Default.ChatBubbleOutline, null) }, label = { Text("گفت‌وگو") })
            NavigationBarItem(tab == AppTab.REMINDERS, { tab = AppTab.REMINDERS }, icon = { Icon(Icons.Default.NotificationsNone, null) }, label = { Text("یادآورها") })
            NavigationBarItem(tab == AppTab.SETTINGS, { tab = AppTab.SETTINGS }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("حساب") })
        }
    }) { padding ->
        when (tab) {
            AppTab.CHAT -> ChatScreen(api, Modifier.padding(padding))
            AppTab.REMINDERS -> RemindersScreen(api, Modifier.padding(padding))
            AppTab.SETTINGS -> Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("حساب کاربری", style = MaterialTheme.typography.headlineSmall)
                Text(username)
                Text("فضای فعال: delivery")
                HorizontalDivider()
                Text("اعلان‌های گوشی", style = MaterialTheme.typography.titleMedium)
                Text(if (notificationsAllowed) "مجوز نمایش اعلان فعال است." else "مجوز نمایش اعلان غیرفعال است.")
                if (!notificationsAllowed && Build.VERSION.SDK_INT >= 33) {
                    OutlinedButton(onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("فعال‌کردن اجازهٔ اعلان") }
                }
                Text(pushStatus, style = MaterialTheme.typography.bodySmall)
                Text("دستگاه‌های ثبت‌شده: $mobileDeviceCount", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { syncPushDevice() }) { Text("همگام‌سازی دوبارهٔ اعلان‌ها") }
                Button(onClick = {
                    pushTestBusy = true
                    scope.launch {
                        runCatching { api.sendPushTest() }
                            .onSuccess { pushStatus = "اعلان آزمایشی ارسال شد؛ نمایش آن را در گوشی بررسی کن." }
                            .onFailure { pushStatus = "ارسال آزمایشی ناموفق بود: ${it.message ?: "خطای نامشخص"}" }
                        pushTestBusy = false
                    }
                }, enabled = !pushTestBusy && mobileDeviceCount > 0) {
                    if (pushTestBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("ارسال اعلان آزمایشی")
                }
                OutlinedButton(onClick = onLogout) { Text("خروج از حساب") }
            }
        }
    }
}

@Composable
private fun ChatScreen(api: EtingApi, modifier: Modifier = Modifier) {
    val messages = remember { mutableStateListOf<ChatLine>() }
    var text by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }; var historyLoaded by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(true) }; var syncError by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var retryDelay = 5_000L
            while (isActive) {
                syncing = true
                runCatching { api.chatHistory() }
                    .onSuccess { history ->
                        if (!busy) {
                            messages.clear()
                            messages.addAll(history.map { ChatLine(it.text, it.fromUser) })
                        }
                        historyLoaded = true
                        syncError = ""
                        syncing = false
                        retryDelay = 5_000L
                    }
                    .onFailure { failure ->
                        historyLoaded = true
                        syncError = failure.message ?: "همگام‌سازی گفت‌وگو ناموفق بود."
                    }
                if (syncError.isBlank()) delay(60_000L) else delay(retryDelay.also { retryDelay = (retryDelay * 2).coerceAtMost(60_000L) })
            }
        }
    }
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("گفت‌وگوی ائتینگ", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 16.dp))
        if (syncing) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(if (syncError.isBlank()) "در حال همگام‌سازی گفت‌وگو…" else "همگام‌سازی انجام می‌شود؛ تلاش مجدد در پس‌زمینه", style = MaterialTheme.typography.bodySmall)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = false, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(messages) { line ->
                Surface(color = if (line.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth(if (line.fromUser) .86f else .96f).padding(start = if (line.fromUser) 36.dp else 0.dp)) {
                    Text(line.text, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth(.24f)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 10.dp)) {
            OutlinedTextField(text, { text = it }, modifier = Modifier.weight(1f), placeholder = { Text("پیامت را بنویس") }, maxLines = 4, shape = RoundedCornerShape(24.dp))
            Spacer(Modifier.width(8.dp))
            Button(enabled = historyLoaded && !busy && text.isNotBlank(), onClick = {
                val query = text.trim(); text = ""; messages.add(ChatLine(query, true)); busy = true
                scope.launch { runCatching { api.chat(query) }.onSuccess { messages.add(ChatLine(it, false)) }.onFailure { messages.add(ChatLine(it.message ?: "ارتباط برقرار نشد.", false)) }; busy = false }
            }, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) { Text("ارسال") }
        }
    }
}

@Composable
private fun RemindersScreen(api: EtingApi, modifier: Modifier = Modifier) {
    var tasks by remember { mutableStateOf<List<Reminder>>(emptyList()) }; var title by remember { mutableStateOf("") }; var error by remember { mutableStateOf("") }; var loading by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(true) }; var syncVersion by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, syncVersion) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var retryDelay = 5_000L
            while (isActive) {
                syncing = true
                runCatching { api.reminders() }
                    .onSuccess { rows ->
                        tasks = rows
                        error = ""
                        runCatching { syncRemindersToWatch(context, Gson().toJson(rows)) }
                        syncing = false
                        retryDelay = 5_000L
                    }
                    .onFailure { error = it.message ?: "بارگذاری یادآورها ناموفق بود." }
                if (error.isBlank()) delay(60_000L) else delay(retryDelay.also { retryDelay = (retryDelay * 2).coerceAtMost(60_000L) })
            }
        }
    }
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text("یادآورها", style = MaterialTheme.typography.headlineSmall)
        if (syncing) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(if (error.isBlank()) "در حال همگام‌سازی یادآورها…" else "ارتباط برقرار نشد؛ همگام‌سازی در پس‌زمینه ادامه دارد", style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(title, { title = it }, modifier = Modifier.weight(1f), label = { Text("یادآوری جدید") }, singleLine = true)
            Spacer(Modifier.width(8.dp))
            Button(enabled = title.isNotBlank() && !loading, onClick = {
                loading = true; error = ""
                val due = LocalDateTime.now().plusHours(1).atZone(ZoneId.of("Asia/Tehran")).toOffsetDateTime().toString()
                scope.launch { runCatching { api.addReminder(title.trim(), due) }.onSuccess { title = ""; error = ""; syncVersion++ }.onFailure { error = it.message ?: "ذخیره ناموفق بود." }; loading = false }
            }) { Text("افزودن") }
        }
        Text("زمان پیش‌فرض یادآور: یک ساعت دیگر", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
        LazyColumn(Modifier.fillMaxSize().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(tasks, key = { it.id }) { task ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(task.title, style = MaterialTheme.typography.titleMedium); if (task.dueAt.isNotBlank()) Text(task.dueAt, style = MaterialTheme.typography.bodySmall) }
                        TextButton(onClick = { scope.launch { runCatching { api.completeReminder(task.id) }.onSuccess { syncVersion++ }.onFailure { error = it.message ?: "تغییر وضعیت ناموفق بود." } } }) { Text("انجام شد") }
                    }
                }
            }
            if (tasks.isEmpty()) item { Text("یادآوری بازی وجود ندارد.", modifier = Modifier.padding(16.dp)) }
        }
    }
}
