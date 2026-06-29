package com.example.contactscleaner

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.contactscleaner.data.IgnoreDatabase
import com.example.contactscleaner.data.WhatsAppInteraction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WhatsAppNotificationListenerService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private lateinit var database: IgnoreDatabase

    override fun onCreate() {
        super.onCreate()
        database = IgnoreDatabase.getDatabase(applicationContext)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val packageName = sbn.packageName
        if (packageName == "com.whatsapp") {
            val extras = sbn.notification.extras ?: return
            
            // Get sender name (title of the notification)
            var senderName = extras.getString(Notification.EXTRA_TITLE) ?: ""
            
            // Clean up any extra info (e.g. "Name (Group)" or "Name (3 messages)")
            if (senderName.contains("(")) {
                senderName = senderName.substringBefore("(").trim()
            }
            if (senderName.contains(":")) {
                senderName = senderName.substringBefore(":").trim()
            }
            senderName = senderName.trim()

            if (senderName.isNotEmpty() && senderName != "WhatsApp" && !senderName.contains("new messages")) {
                val timestamp = sbn.postTime
                Log.d("WhatsAppListener", "Captured WhatsApp notification from: $senderName at $timestamp")
                
                serviceScope.launch {
                    try {
                        database.whatsappInteractionDao().insert(
                            WhatsAppInteraction(contactName = senderName, timestamp = timestamp)
                        )
                    } catch (e: Exception) {
                        Log.e("WhatsAppListener", "Failed to save WhatsApp interaction", e)
                    }
                }
            }
        }
    }
}
