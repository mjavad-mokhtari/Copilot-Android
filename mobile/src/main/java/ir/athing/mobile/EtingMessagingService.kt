package ir.athing.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class EtingMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val api = EtingApi(applicationContext)
        api.rememberPendingPushToken(token)
        CoroutineScope(Dispatchers.IO).launch { runCatching { api.registerPushToken(token, "phone") } }
    }
    override fun onMessageReceived(message: RemoteMessage) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("eting_reminders", "یادآورهای ائتینگ", NotificationManager.IMPORTANCE_HIGH))
        val title = message.data["title"] ?: message.notification?.title ?: "یادآور ائتینگ"
        val body = message.data["body"] ?: message.notification?.body ?: "زمان انجام یادآوری رسیده است."
        val notification = NotificationCompat.Builder(this, "eting_reminders")
            .setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build()
        manager.notify((message.data["task_id"] ?: title).hashCode(), notification)
    }
}
