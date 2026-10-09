package com.ayoub.ghostscript2

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log

class TaskReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "GhostTaskReceiver2"
        const val ACTION_ALARM_TRIGGER = "com.ayoub.ghostscript2.ACTION_ALARM_TRIGGER"
        const val EXTRA_TASK_ID = "extra_task_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        Log.i(TAG, "Alarm triggered for task: $taskId")

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ghostscript2:wake_$taskId"
        )

        wakeLock?.acquire(10_000)

        try {
            TaskManager.getInstance(context).onAlarmTriggered(taskId)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling alarm trigger", e)
        } finally {
            if (wakeLock?.isHeld == true) {
                wakeLock.release()
            }
        }
    }
}
