package com.jphat.pgsharpchecker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BootReceiver"
        private const val PREFS_NAME = "PGSharpCheckerPrefs"
        private const val KEY_AUTO_CHECK_ENABLED = "auto_check_enabled"
        private const val KEY_SCHEDULED_TIMES = "scheduled_times"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || 
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            
            Log.d(TAG, "Device booted, restarting scheduled checks")
            
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            
            val autoCheckEnabled = prefs.getBoolean(KEY_AUTO_CHECK_ENABLED, false)
            if (autoCheckEnabled) {
                Log.d(TAG, "Restarting 12-hour auto check")
                BackgroundCheckScheduler.schedulePeriodicCheck(context)
            }
            
            val scheduledTimesString = prefs.getString(KEY_SCHEDULED_TIMES, "") ?: ""
            if (scheduledTimesString.isNotEmpty()) {
                Log.d(TAG, "Restarting scheduled checks")
                val scheduledTimes = mutableListOf<Pair<Int, Int>>()
                scheduledTimesString.split(";").forEach { timeStr ->
                    val parts = timeStr.split(":")
                    if (parts.size == 2) {
                        scheduledTimes.add(Pair(parts[0].toInt(), parts[1].toInt()))
                    }
                }
                scheduledTimes.forEach { time ->
                    BackgroundCheckScheduler.scheduleExactCheck(context, time.first, time.second)
                }
            }
        }
    }
}
