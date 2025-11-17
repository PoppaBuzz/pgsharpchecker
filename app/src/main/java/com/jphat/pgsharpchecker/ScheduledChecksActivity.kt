package com.jphat.pgsharpchecker

import android.app.TimePickerDialog
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.work.*
import java.util.Calendar
import java.util.concurrent.TimeUnit

class ScheduledChecksActivity : AppCompatActivity() {
    
    private lateinit var llScheduledTimes: LinearLayout
    private lateinit var btnAddTime: Button
    private lateinit var btnSave: Button
    private lateinit var btnBack: Button
    
    private val scheduledTimes = mutableListOf<Pair<Int, Int>>() // hour, minute pairs
    
    companion object {
        private const val PREFS_NAME = "PGSharpCheckerPrefs"
        private const val KEY_SCHEDULED_TIMES = "scheduled_times"
        private const val MAX_SCHEDULED_TIMES = 4
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scheduled_checks)
        
        llScheduledTimes = findViewById(R.id.llScheduledTimes)
        btnAddTime = findViewById(R.id.btnAddTime)
        btnSave = findViewById(R.id.btnSave)
        btnBack = findViewById(R.id.btnBack)
        
        loadScheduledTimes()
        updateTimesList()
        
        btnAddTime.setOnClickListener {
            if (scheduledTimes.size < MAX_SCHEDULED_TIMES) {
                showTimePicker()
            } else {
                Toast.makeText(this, "Maximum 4 scheduled times allowed", Toast.LENGTH_SHORT).show()
            }
        }
        
        btnSave.setOnClickListener {
            saveScheduledTimes()
            scheduleAllChecks()
            Toast.makeText(this, "Scheduled checks saved", Toast.LENGTH_SHORT).show()
            finish()
        }
        
        btnBack.setOnClickListener {
            finish()
        }
    }
    
    private fun showTimePicker() {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        
        TimePickerDialog(this, { _, selectedHour, selectedMinute ->
            scheduledTimes.add(Pair(selectedHour, selectedMinute))
            scheduledTimes.sortBy { it.first * 60 + it.second }
            updateTimesList()
        }, hour, minute, false).show()
    }
    
    private fun updateTimesList() {
        llScheduledTimes.removeAllViews()
        
        if (scheduledTimes.isEmpty()) {
            val emptyText = TextView(this)
            emptyText.text = "No scheduled checks. Tap 'Add Time' to schedule."
            emptyText.textSize = 14f
            emptyText.setPadding(16, 16, 16, 16)
            llScheduledTimes.addView(emptyText)
        } else {
            scheduledTimes.forEachIndexed { index, time ->
                val timeView = layoutInflater.inflate(R.layout.item_scheduled_time, llScheduledTimes, false)
                val tvTime = timeView.findViewById<TextView>(R.id.tvTime)
                val btnRemove = timeView.findViewById<Button>(R.id.btnRemove)
                
                tvTime.text = String.format("%02d:%02d", time.first, time.second)
                
                btnRemove.setOnClickListener {
                    scheduledTimes.removeAt(index)
                    updateTimesList()
                }
                
                llScheduledTimes.addView(timeView)
            }
        }
        
        btnAddTime.isEnabled = scheduledTimes.size < MAX_SCHEDULED_TIMES
    }
    
    private fun loadScheduledTimes() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val timesString = prefs.getString(KEY_SCHEDULED_TIMES, "") ?: ""
        
        scheduledTimes.clear()
        if (timesString.isNotEmpty()) {
            timesString.split(";").forEach { timeStr ->
                val parts = timeStr.split(":")
                if (parts.size == 2) {
                    scheduledTimes.add(Pair(parts[0].toInt(), parts[1].toInt()))
                }
            }
        }
    }
    
    private fun saveScheduledTimes() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val timesString = scheduledTimes.joinToString(";") { "${it.first}:${it.second}" }
        prefs.edit().putString(KEY_SCHEDULED_TIMES, timesString).apply()
    }
    
    private fun scheduleAllChecks() {
        // Cancel existing scheduled checks
        WorkManager.getInstance(this).cancelAllWorkByTag("scheduled_check")
        
        scheduledTimes.forEach { time ->
            scheduleCheckAt(time.first, time.second)
        }
    }
    
    private fun scheduleCheckAt(hour: Int, minute: Int) {
        val currentTime = Calendar.getInstance()
        val scheduledTime = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
        }
        
        // If time has passed today, schedule for tomorrow
        if (scheduledTime.before(currentTime)) {
            scheduledTime.add(Calendar.DAY_OF_MONTH, 1)
        }
        
        val delay = scheduledTime.timeInMillis - currentTime.timeInMillis
        
        val checkRequest = OneTimeWorkRequestBuilder<VersionCheckWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .addTag("scheduled_check")
            .addTag("scheduled_${hour}_${minute}")
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        
        WorkManager.getInstance(this).enqueue(checkRequest)
        
        // Schedule recurring daily check
        val dailyCheckRequest = PeriodicWorkRequestBuilder<VersionCheckWorker>(
            24, TimeUnit.HOURS
        )
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .addTag("scheduled_check")
            .addTag("scheduled_${hour}_${minute}")
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "scheduled_check_${hour}_${minute}",
            ExistingPeriodicWorkPolicy.REPLACE,
            dailyCheckRequest
        )
    }
}
