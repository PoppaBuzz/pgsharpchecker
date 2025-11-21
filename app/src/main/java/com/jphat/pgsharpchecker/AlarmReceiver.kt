package com.jphat.pgsharpchecker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.*

class AlarmReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "AlarmReceiver"
        const val EXTRA_IS_PERIODIC = "is_periodic"
        const val EXTRA_HOUR = "hour"
        const val EXTRA_MINUTE = "minute"
        private const val PREFS_NAME = "PGSharpCheckerPrefs"
        private const val KEY_LAST_CHECK_TIME = "last_check_time"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Alarm triggered")
        
        val isPeriodic = intent.getBooleanExtra(EXTRA_IS_PERIODIC, false)
        val hour = intent.getIntExtra(EXTRA_HOUR, -1)
        val minute = intent.getIntExtra(EXTRA_MINUTE, -1)
        
        val checkRequest = OneTimeWorkRequestBuilder<VersionCheckWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        
        WorkManager.getInstance(context).enqueue(checkRequest)
        
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_LAST_CHECK_TIME, System.currentTimeMillis()).apply()
        
        if (isPeriodic) {
            if (hour != -1 && minute != -1) {
                BackgroundCheckScheduler.scheduleExactCheck(context, hour, minute)
            } else {
                BackgroundCheckScheduler.schedulePeriodicCheck(context)
            }
        }
    }
}
