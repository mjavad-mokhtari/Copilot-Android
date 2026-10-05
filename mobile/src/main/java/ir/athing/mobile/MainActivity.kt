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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.DeleteOutline
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
import androidx.compose.ui.text.font.FontWeight
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

private enum class AppTab { CHAT, REMINDERS, SETTINGS }

@Composable
private fun EtingApp(api: EtingApi) {
    var username by remember { mutableStateOf(api.cachedAccount()) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(api.hasStoredSession() && api.cachedAccount().isBlank()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        if (api.hasStoredSession()) {
            runCatching { api.restoreSession() }
                .onSuccess { restored -> if (restored.isNotBlank()) username = restored }
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
        Button(onClick = { busy = true; onError(""); scope.launch { runCatching { api.login(username.trim(), password) }.onSuccess { onLoggedIn(it); FirebaseMessaging.getInstance().token.addOnSuccessListener { token -> scope.launch { runCatching { api.registerPushToken(token, "phone") } } } }.onFailure { onError(it.message ?: "ورود ناموفق بود") }; busy = false } }, enabled = !busy && username.isNotBlank() && password.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
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
    var notificationsAllowed by remember {
        mutableStateOf(Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsAllowed = granted
        pushStatus = if (granted) "اجازهٔ اعلان فعال است؛ وضعیت ثبت دستگاه در حال بررسی است." else "اجازهٔ اعلان داده نشد؛ اعلان‌ها روی این گوشی نمایش داده نمی‌شوند."
    }
    fun syncPushDevice() {
        pushStatus = "در حال دریافت و ثبت توکن اعلان…"
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
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
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, username) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    runCatching { api.refreshSessionIfDue() }.onFailure { failure ->
                        if (failure is ApiException && failure.statusCode == 401) onSessionExpired()
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
    val conversations = remember { mutableStateListOf<ChatConversation>() }
    var text by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    var activeSession by remember { mutableStateOf(api.activeChatSessionId()) }
    var historyOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ChatConversation?>(null) }
    var renameText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        runCatching { api.chatConversations() }.onSuccess { rows ->
            conversations.clear(); conversations.addAll(rows)
            val selected = rows.firstOrNull { it.sessionId == activeSession } ?: rows.firstOrNull()
            if (selected != null && selected.sessionId != activeSession) activeSession = selected.sessionId
        }.onFailure { error = it.message ?: "گرفتن تاریخچه ممکن نشد." }
    }
    LaunchedEffect(activeSession) {
        if (activeSession.isBlank()) return@LaunchedEffect
        api.setActiveChatSessionId(activeSession)
        runCatching { api.chatMessages(activeSession) }.onSuccess { rows ->
            messages.clear(); messages.addAll(rows)
        }.onFailure { error = it.message ?: "بارگذاری گفت‌وگو ناموفق بود." }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(12000)
            if (!busy) {
                runCatching { api.chatConversations() }.onSuccess { rows ->
                    val hadActiveConversation = conversations.any { it.sessionId == activeSession }
                    conversations.clear(); conversations.addAll(rows)
                    if (hadActiveConversation && rows.none { it.sessionId == activeSession }) {
                        messages.clear()
                        val next = rows.firstOrNull()
                        if (next != null) activeSession = next.sessionId
                        else runCatching { api.createChatConversation() }.onSuccess { created -> conversations.add(created); activeSession = created.sessionId }
                    } else if (rows.none { it.sessionId == activeSession } && rows.isNotEmpty()) activeSession = rows.first().sessionId
                    if (rows.any { it.sessionId == activeSession }) {
                        runCatching { api.chatMessages(activeSession) }.onSuccess { remote ->
                            val byId = (messages.toList() + remote).associateBy { it.messageId }
                            messages.clear(); messages.addAll(byId.values.sortedBy { it.createdAt })
                        }
                    }
                }
            }
        }
    }

    val activeTitle = conversations.firstOrNull { it.sessionId == activeSession }?.title ?: "گفت‌وگوی جدید"
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("گفت‌وگو", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(activeTitle, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            TextButton(onClick = { historyOpen = true }) { Text("تاریخچه (${conversations.size})") }
            TextButton(enabled = !busy, onClick = {
                scope.launch {
                    runCatching { api.createChatConversation() }.onSuccess { conversation ->
                        conversations.add(0, conversation); messages.clear(); activeSession = conversation.sessionId; error = ""
                    }.onFailure { error = it.message ?: "گفت‌وگوی جدید ساخته نشد." }
                }
            }) { Text("جدید") }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = false, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(messages, key = { it.messageId }) { line ->
                Surface(color = if (line.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth(if (line.fromUser) .86f else .96f).padding(start = if (line.fromUser) 36.dp else 0.dp)) {
                    Text(line.text, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth(.24f)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 10.dp)) {
            OutlinedTextField(text, { text = it }, modifier = Modifier.weight(1f), placeholder = { Text("پیامت را بنویس") }, maxLines = 4, shape = RoundedCornerShape(24.dp))
            Spacer(Modifier.width(8.dp))
            Button(enabled = !busy && text.isNotBlank(), onClick = {
                val query = text.trim(); text = ""; error = ""
                val userLine = ChatLine(java.util.UUID.randomUUID().toString(), "user", query, System.currentTimeMillis())
                messages.add(userLine); busy = true
                scope.launch {
                    runCatching { api.chat(query, activeSession, userLine.messageId) }
                        .onSuccess { messages.add(it); runCatching { api.chatConversations() }.onSuccess { rows -> conversations.clear(); conversations.addAll(rows) } }
                        .onFailure { error = it.message ?: "ارسال پیام ناموفق بود." }
                    busy = false
                }
            }, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) { Text("ارسال") }
        }
    }

    if (historyOpen) AlertDialog(
        onDismissRequest = { historyOpen = false },
        title = { Text("گفت‌وگوهای حساب") },
        text = {
            if (conversations.isEmpty()) Text("هنوز گفت‌وگویی ثبت نشده است.")
            else LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(conversations, key = { it.sessionId }) { conversation ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { activeSession = conversation.sessionId; historyOpen = false }, modifier = Modifier.weight(1f)) {
                            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                                Text(conversation.title, maxLines = 1, fontWeight = if (conversation.sessionId == activeSession) FontWeight.Bold else FontWeight.Normal)
                                Text("${conversation.space} · ${conversation.project.ifBlank { "بدون پروژه" }}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            }
                        }
                        TextButton(onClick = { renaming = conversation; renameText = conversation.title; historyOpen = false }) { Text("نام") }
                        IconButton(onClick = {
                            scope.launch {
                                runCatching { api.deleteChatConversation(conversation.sessionId) }
                                    .onSuccess {
                                        conversations.removeAll { it.sessionId == conversation.sessionId }
                                        if (activeSession == conversation.sessionId) {
                                            messages.clear()
                                            val next = conversations.firstOrNull()
                                            if (next != null) activeSession = next.sessionId
                                            else runCatching { api.createChatConversation() }.onSuccess { created -> conversations.add(created); activeSession = created.sessionId }
                                        }
                                    }
                                    .onFailure { error = it.message ?: "حذف گفت‌وگو ناموفق بود." }
                            }
                        }) { Icon(Icons.Default.DeleteOutline, contentDescription = "حذف گفت‌وگو") }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = { historyOpen = false }) { Text("بستن") } },
    )

    renaming?.let { conversation ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("تغییر نام گفت‌وگو") },
            text = { OutlinedTextField(renameText, { renameText = it }, singleLine = true, label = { Text("عنوان") }) },
            confirmButton = { TextButton(onClick = {
                val title = renameText.trim()
                if (title.isNotEmpty()) scope.launch {
                    runCatching { api.renameChatConversation(conversation.sessionId, title) }
                        .onSuccess { runCatching { api.chatConversations() }.onSuccess { rows -> conversations.clear(); conversations.addAll(rows) }; renaming = null }
                        .onFailure { error = it.message ?: "تغییر نام انجام نشد." }
                }
            }) { Text("ذخیره") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("لغو") } },
        )
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
