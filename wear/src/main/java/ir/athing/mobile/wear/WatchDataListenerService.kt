package ir.athing.mobile.wear

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

class WatchDataListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { event -> if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == "/reminders") {
            val payload = DataMapItem.fromDataItem(event.dataItem).dataMap.getString("json").orEmpty()
            getSharedPreferences("wear_reminders", MODE_PRIVATE).edit().putString("json", payload).apply()
        } }
    }
}
