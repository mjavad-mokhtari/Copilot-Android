package ir.athing.mobile.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class WearMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { WatchReminders(this) } }
}

@Composable
private fun WatchReminders(activity: ComponentActivity) {
    var rows by remember { mutableStateOf(readRows(activity)) }
    val scope = rememberCoroutineScope()
    MaterialTheme(colorScheme = darkColorScheme(primary = androidx.compose.ui.graphics.Color(0xFFD0BCFF))) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("یادآورها", style = MaterialTheme.typography.titleMedium)
            if (rows.isEmpty()) Text("یادآوری بازی نیست", modifier = Modifier.padding(vertical = 18.dp))
            rows.forEach { row ->
                ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        Text(row.optString("title"), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { scope.launch { sendComplete(activity, row.optString("id")); rows = readRows(activity) } }) { Text("انجام شد") }
                    }
                }
            }
        }
    }
}

private fun readRows(context: android.content.Context): List<JSONObject> = runCatching {
    val array = JSONArray(context.getSharedPreferences("wear_reminders", 0).getString("json", "[]"))
    (0 until array.length()).map { array.getJSONObject(it) }
}.getOrDefault(emptyList())

private suspend fun sendComplete(activity: ComponentActivity, id: String) = withContext(Dispatchers.IO) {
    val nodes = Tasks.await(Wearable.getNodeClient(activity).connectedNodes)
    val payload = JSONObject().put("id", id).toString().toByteArray()
    nodes.forEach { Tasks.await(Wearable.getMessageClient(activity).sendMessage(it.id, "/reminders/complete", payload)) }
}
