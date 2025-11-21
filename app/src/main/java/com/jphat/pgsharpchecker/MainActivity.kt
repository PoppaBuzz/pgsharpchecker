package com.jphat.pgsharpchecker

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.work.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    
    private lateinit var tvInstalledVersion: TextView
    private lateinit var tvLatestVersion: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvLastChecked: TextView
    private lateinit var tvNextCheck: TextView
    private lateinit var ivStatusIcon: ImageView
    private lateinit var btnCheckNow: Button
    private lateinit var btnEnableAutoCheck: Button
    private lateinit var btnDownloadUpdate: Button
    private lateinit var btnScheduleChecks: Button
    
    private var isAutoCheckEnabled = false
    private var updateAvailable = false
    
    enum class VersionStatus {
        UP_TO_DATE, UPDATE_AVAILABLE, ERROR, CHECKING
    }
    
    companion object {
        private const val NOTIFICATION_PERMISSION_CODE = 100
        private const val QUERY_PACKAGES_PERMISSION_CODE = 101
        private const val PREFS_NAME = "PGSharpCheckerPrefs"
        private const val KEY_AUTO_CHECK_ENABLED = "auto_check_enabled"
        private const val KEY_LAST_CHECK_TIME = "last_check_time"
        private const val CHECK_INTERVAL_HOURS = 12L
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // Initialize views
        tvInstalledVersion = findViewById(R.id.tvInstalledVersion)
        tvLatestVersion = findViewById(R.id.tvLatestVersion)
        tvStatus = findViewById(R.id.tvStatus)
        tvLastChecked = findViewById(R.id.tvLastChecked)
        tvNextCheck = findViewById(R.id.tvNextCheck)
        ivStatusIcon = findViewById(R.id.ivStatusIcon)
        btnCheckNow = findViewById(R.id.btnCheckNow)
        btnEnableAutoCheck = findViewById(R.id.btnEnableAutoCheck)
        btnDownloadUpdate = findViewById(R.id.btnDownloadUpdate)
        btnScheduleChecks = findViewById(R.id.btnScheduleChecks)
        
        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_CODE
                )
            }
        }
        
        requestExactAlarmPermission()
        
        // Load saved auto-check state
        loadAutoCheckState()
        
        // Display installed PGSharp version
        displayInstalledVersion()
        
        // Update button appearance based on state
        updateAutoCheckButton()
        
        // Display last check time
        displayLastCheckTime()
        
        // Display next check time
        displayNextCheckTime()
        
        // Download button click
        btnDownloadUpdate.setOnClickListener {
            openPGSharpWebsite()
        }
        
        // Schedule checks button
        btnScheduleChecks.setOnClickListener {
            startActivity(android.content.Intent(this, ScheduledChecksActivity::class.java))
        }
        
        // Manual check button
        btnCheckNow.setOnClickListener {
            performManualCheck()
        }
        
        // Enable/disable automatic periodic checking
        btnEnableAutoCheck.setOnClickListener {
            if (isAutoCheckEnabled) {
                disablePeriodicVersionCheck()
            } else {
                schedulePeriodicVersionCheck()
            }
        }
    }
    
    private fun displayInstalledVersion() {
        val packages = listOf(
            "com.nianticlabs.pokemongo",
            "com.pgsharp.pokemongo",
            "com.nianticproject.holoholo"
        )
        
        for (packageName in packages) {
            try {
                val packageInfo = packageManager.getPackageInfo(packageName, 0)
                val versionName = packageInfo.versionName
                tvInstalledVersion.text = getString(R.string.installed_pokemon_go, versionName)
                return
            } catch (e: PackageManager.NameNotFoundException) {
                // Try next package
            }
        }
        
        // If we get here, no package was found - let's search for it
        tvInstalledVersion.text = getString(R.string.pokemon_go_not_found)
        searchForPokemonGoPackage()
    }
    
    private fun performManualCheck() {
        updateStatus(VersionStatus.CHECKING)
        
        val checkRequest = OneTimeWorkRequestBuilder<VersionCheckWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        
        WorkManager.getInstance(this).enqueue(checkRequest)
        
        WorkManager.getInstance(this).getWorkInfoByIdLiveData(checkRequest.id)
            .observe(this) { workInfo ->
                if (workInfo != null) {
                    when (workInfo.state) {
                        WorkInfo.State.SUCCEEDED -> {
                            val latestVersion = workInfo.outputData.getString("latest_version")
                            val installedVersion = workInfo.outputData.getString("installed_version")
                            updateAvailable = workInfo.outputData.getBoolean("update_available", false)
                            
                            tvInstalledVersion.text = getString(R.string.installed_pokemon_go, installedVersion)
                            tvLatestVersion.text = getString(R.string.latest_on_pgsharp, latestVersion)
                            
                            if (updateAvailable) {
                                updateStatus(VersionStatus.UPDATE_AVAILABLE)
                                tvStatus.text = getString(R.string.newer_version_available, installedVersion, latestVersion)
                            } else {
                                updateStatus(VersionStatus.UP_TO_DATE)
                            }
                            
                            displayLastCheckTime()
                        }
                        WorkInfo.State.FAILED -> {
                            updateStatus(VersionStatus.ERROR)
                        }
                        WorkInfo.State.RUNNING -> {
                            updateStatus(VersionStatus.CHECKING)
                        }
                        else -> {}
                    }
                }
            }
    }
    
    private fun searchForPokemonGoPackage() {
        // Search all installed packages for anything containing "pokemon" or "niantic"
        val pm = packageManager
        val packages = pm.getInstalledApplications(0)
        
        val foundPackages = packages.filter { 
            (it.packageName.contains("pokemon", ignoreCase = true) ||
            it.packageName.contains("niantic", ignoreCase = true) ||
            it.packageName.contains("pgsharp", ignoreCase = true) ||
            it.packageName.contains("pogo", ignoreCase = true)) &&
            it.packageName != packageName  // Exclude this app itself
        }
        
        if (foundPackages.isNotEmpty()) {
            val packageNames = foundPackages.joinToString("\n") { 
                "${it.packageName} - ${pm.getApplicationLabel(it)}"
            }
            tvInstalledVersion.text = getString(R.string.found_packages_text, packageNames)
            Toast.makeText(
                this, 
                getString(R.string.found_packages, foundPackages.size), 
                Toast.LENGTH_LONG
            ).show()
        } else {
            tvInstalledVersion.text = getString(R.string.no_pokemon_go_found)
            Toast.makeText(this, getString(R.string.pokemon_go_not_found_toast), Toast.LENGTH_LONG).show()
        }
    }
    
    private fun loadAutoCheckState() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        isAutoCheckEnabled = prefs.getBoolean(KEY_AUTO_CHECK_ENABLED, false)
    }
    
    private fun saveAutoCheckState(enabled: Boolean) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_AUTO_CHECK_ENABLED, enabled).apply()
        isAutoCheckEnabled = enabled
    }
    
    private fun saveLastCheckTime() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putLong(KEY_LAST_CHECK_TIME, System.currentTimeMillis()).apply()
    }
    
    private fun displayLastCheckTime() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val lastCheckTime = prefs.getLong(KEY_LAST_CHECK_TIME, 0)
        
        if (lastCheckTime == 0L) {
            tvLastChecked.text = getString(R.string.never_checked)
        } else {
            val timeAgo = getTimeAgo(lastCheckTime)
            tvLastChecked.text = getString(R.string.last_checked, timeAgo)
        }
    }
    
    private fun getTimeAgo(timeMillis: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timeMillis
        
        return when {
            diff < 60000 -> "just now"
            diff < 3600000 -> "${diff / 60000} minutes ago"
            diff < 86400000 -> "${diff / 3600000} hours ago"
            diff < 604800000 -> "${diff / 86400000} days ago"
            else -> "${diff / 604800000} weeks ago"
        }
    }
    
    private fun displayNextCheckTime() {
        if (!isAutoCheckEnabled) {
            tvNextCheck.visibility = android.view.View.GONE
            return
        }
        
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val lastCheckTime = prefs.getLong(KEY_LAST_CHECK_TIME, 0)
        
        if (lastCheckTime == 0L) {
            tvNextCheck.visibility = android.view.View.GONE
            return
        }
        
        val nextCheckTime = lastCheckTime + (CHECK_INTERVAL_HOURS * 3600000)
        val now = System.currentTimeMillis()
        
        if (nextCheckTime > now) {
            val timeUntil = getTimeUntil(nextCheckTime)
            tvNextCheck.text = getString(R.string.next_check, timeUntil)
            tvNextCheck.visibility = android.view.View.VISIBLE
        } else {
            tvNextCheck.text = getString(R.string.next_check, "soon")
            tvNextCheck.visibility = android.view.View.VISIBLE
        }
    }
    
    private fun getTimeUntil(timeMillis: Long): String {
        val now = System.currentTimeMillis()
        val diff = timeMillis - now
        
        return when {
            diff < 60000 -> "in less than a minute"
            diff < 3600000 -> "in ${diff / 60000} minutes"
            diff < 86400000 -> "in ${diff / 3600000} hours"
            else -> "in ${diff / 86400000} days"
        }
    }
    
    private fun openPGSharpWebsite() {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
        intent.data = "https://www.pgsharp.com".toUri()
        startActivity(intent)
    }
    
    private fun updateAutoCheckButton() {
        if (isAutoCheckEnabled) {
            btnEnableAutoCheck.text = getString(R.string.btn_auto_enabled)
            btnEnableAutoCheck.setBackgroundColor(getColor(R.color.button_enabled))
        } else {
            btnEnableAutoCheck.text = getString(R.string.btn_enable_auto)
            btnEnableAutoCheck.setBackgroundColor(getColor(R.color.button_disabled))
        }
    }
    
    private fun schedulePeriodicVersionCheck() {
        BackgroundCheckScheduler.schedulePeriodicCheck(this)
        
        saveAutoCheckState(true)
        updateAutoCheckButton()
        displayNextCheckTime()
        
        Toast.makeText(
            this, 
            getString(R.string.auto_check_enabled_toast), 
            Toast.LENGTH_LONG
        ).show()
        
        tvStatus.text = getString(R.string.auto_check_enabled_status)
    }
    
    private fun disablePeriodicVersionCheck() {
        BackgroundCheckScheduler.cancelPeriodicCheck(this)
        
        saveAutoCheckState(false)
        updateAutoCheckButton()
        displayNextCheckTime()
        
        Toast.makeText(
            this, 
            getString(R.string.auto_check_disabled_toast), 
            Toast.LENGTH_LONG
        ).show()
        
        tvStatus.text = getString(R.string.auto_check_disabled_status)
    }
    
    private fun requestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
    }
    
    private fun updateStatus(status: VersionStatus) {
        when (status) {
            VersionStatus.UP_TO_DATE -> {
                tvStatus.text = "Your Pokemon Go version matches PGSharp!"
                tvStatus.setTextColor(getColor(R.color.status_success))
                ivStatusIcon.setImageResource(android.R.drawable.checkbox_on_background)
                ivStatusIcon.setColorFilter(getColor(R.color.status_success))
                btnDownloadUpdate.visibility = android.view.View.GONE
            }
            VersionStatus.UPDATE_AVAILABLE -> {
                tvStatus.text = "Update available! New version found."
                tvStatus.setTextColor(getColor(R.color.status_warning))
                ivStatusIcon.setImageResource(android.R.drawable.ic_dialog_info)
                ivStatusIcon.setColorFilter(getColor(R.color.status_warning))
                btnDownloadUpdate.visibility = android.view.View.VISIBLE
            }
            VersionStatus.ERROR -> {
                tvStatus.text = "Unable to check for updates. Please try again."
                tvStatus.setTextColor(getColor(R.color.status_error))
                ivStatusIcon.setImageResource(android.R.drawable.ic_dialog_alert)
                ivStatusIcon.setColorFilter(getColor(R.color.status_error))
                btnDownloadUpdate.visibility = android.view.View.GONE
            }
            VersionStatus.CHECKING -> {
                tvStatus.text = "Checking for updates..."
                tvStatus.setTextColor(getColor(R.color.status_info))
                ivStatusIcon.setImageResource(android.R.drawable.ic_popup_sync)
                ivStatusIcon.setColorFilter(getColor(R.color.status_info))
                btnDownloadUpdate.visibility = android.view.View.GONE
            }
        }
    }
}
