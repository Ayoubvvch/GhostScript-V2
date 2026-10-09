package com.ayoub.ghostscript2

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ToastReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "GhostToastReceiver"
        const val ACTION_SHOW_TOAST = "com.ayoub.ghostscript2.SHOW_TOAST"
        const val ACTION_SHOW_WISDOM = "com.ayoub.ghostscript2.SHOW_WISDOM"
        const val CHANNEL_ID = "ghost_notifications_v2"
        const val CHANNEL_NAME = "GhostScript Messages"

        val defaultWisdomQuotes = arrayOf(
            "أينشتاين (Einstein): الخيال أهم من المعرفة، فالمعرفة محدودة بينما الخيال يحيط بالعالم ككل.",
            "أينشتاين (Einstein): العبقرية هي 1% إلهام و 99% جهد وعرق.",
            "أينشتاين (Einstein): الشخص الذي لم يرتكب خطأ لم يجرب أي شيء جديد أبداً.",
            "ماركوس أوريليوس (Marcus Aurelius): قوة عقلك ملكك وحدك لا الأحداث الخارجية. أدرك هذا وستجد قوة لا تُقهر.",
            "ابن خلدون (Ibn Khaldun): اتباع التقاليد لا يعني أن الأموات أحياء، بل أن الأحياء أموات.",
            "ستيف جوبز (Steve Jobs): وقتك محدود، فلا تضيعه في عيش حياة شخص آخر.",
            "سينيكا (Seneca): ليس لأن الأمور صعبة نحن لا نجرؤ، بل لأننا لا نجرؤ تصبح الأمور صعبة.",
            "الفارابي (Al-Farabi): من أراد أن ينال الحكمة فعليه بالتركيز والإخلاص في طلب العلم.",
            "أينشتاين (Einstein): في قلب كل صعوبة تكمن فرصة عظيمة."
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        var text = intent.getStringExtra("text")
            ?: intent.getStringExtra("msg")
            ?: intent.getStringExtra("message")

        if (action == ACTION_SHOW_WISDOM || text.isNullOrBlank()) {
            text = defaultWisdomQuotes.random()
        }

        val messageToShow = text
        Log.i(TAG, "Displaying message via Toast Overlay, Activity & Notification: $messageToShow")

        // 1. Show guaranteed floating WindowManager overlay
        try {
            ToastOverlayService.show(context, messageToShow)
        } catch (e: Exception) {
            Log.e(TAG, "Error launching ToastOverlayService", e)
        }

        // 2. Also Launch 0-ms Trampoline Activity to trigger native system Toast
        try {
            val actIntent = Intent(context, ToastActivity::class.java).apply {
                putExtra("text", messageToShow)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            context.startActivity(actIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error launching ToastActivity", e)
        }

        // 3. Post Notification backup
        try {
            showNotification(context, messageToShow)
        } catch (e: Exception) {
            Log.e(TAG, "Error showing notification", e)
        }

        // 3. Append to Toast log
        try {
            val logDir = File(context.filesDir, "logs").apply { mkdirs() }
            val logFile = File(logDir, "toasts.log")
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            logFile.appendText("[$time] $messageToShow\n")
        } catch (_: Exception) {}
    }

    private fun showNotification(context: Context, message: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "GhostScript Toast and Motivational Messages"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("GhostScript")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
