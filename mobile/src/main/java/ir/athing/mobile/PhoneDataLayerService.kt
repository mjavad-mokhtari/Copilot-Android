package ir.athing.mobile

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

class PhoneDataLayerService : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event -> if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == "/reminders") {
            val payload = DataMapItem.fromDataItem(event.dataItem).dataMap.getString("json").orEmpty()
            getSharedPreferences("wear_sync", MODE_PRIVATE).edit().putString("reminders", payload).apply()
        } }
    }
    override fun onMessageReceived(event: com.google.android.gms.wearable.MessageEvent) {
        if (event.path != "/reminders/complete") return
        runCatching {
            val id = JSONObject(String(event.data)).getString("id")
            runBlocking { EtingApi(applicationContext).completeReminder(id) }
        }.onFailure { Log.w("EtingWear", "Watch reminder action failed", it) }
    }
}

suspend fun syncRemindersToWatch(context: android.content.Context, json: String) {
    val request = com.google.android.gms.wearable.PutDataMapRequest.create("/reminders").apply { dataMap.putString("json", json); dataMap.putLong("updated_at", System.currentTimeMillis()) }.asPutDataRequest().setUrgent()
    Wearable.getDataClient(context).putDataItem(request)
}
