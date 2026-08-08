package com.jphat.pgsharpchecker

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.work.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {
    
    private lateinit var tvInstalledVersion: TextView
    private lateinit var tvLatestVersion: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvLastChecked: TextView
    private lateinit var tvNextCheck: TextView
    private lateinit var btnCheckNow: Button
    private lateinit var btnEnableAutoCheck: Button
    private lateinit var btnDownloadUpdate: Button
    private lateinit var btnDownloadPokemonGo: Button
    private lateinit var btnScheduleChecks: Button
    private lateinit var btnChangeTheme: Button
    private lateinit var llSettingsHeader: View
    private lateinit var llExpandableContent: View
    private lateinit var ivExpandIcon: android.widget.ImageView
    
    private var isAutoCheckEnabled = false
    private var updateAvailable = false
    private var currentInstalledVersion: String? = null
    private var isSettingsExpanded = false
    
    enum class VersionStatus {
        UP_TO_DATE, UPDATE_AVAILABLE, ERROR, CHECKING
    }
    
    companion object {
        private const val NOTIFICATION_PERMISSION_CODE = 100
        private const val PREFS_NAME = "PGSharpCheckerPrefs"
        private const val KEY_AUTO_CHECK_ENABLED = "auto_check_enabled"
        private const val KEY_LAST_CHECK_TIME = "last_check_time"
        private const val CHECK_INTERVAL_HOURS = 12L
        private const val KEY_THEME = "theme_preference"
        private const val KEY_LATEST_VERSION = "latest_version"
        private const val KEY_INSTALLED_VERSION = "installed_version"
        private const val KEY_UPDATE_AVAILABLE = "update_available"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        loadAndApplyTheme()
        super.onCreate(savedInstanceState)
        
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        setContentView(R.layout.activity_main)
        
        // Initialize views
        tvInstalledVersion = findViewById(R.id.tvInstalledVersion)
        tvLatestVersion = findViewById(R.id.tvLatestVersion)
        tvStatus = findViewById(R.id.tvStatus)
        tvLastChecked = findViewById(R.id.tvLastChecked)
        tvNextCheck = findViewById(R.id.tvNextCheck)
        btnCheckNow = findViewById(R.id.btnCheckNow)
        btnEnableAutoCheck = findViewById(R.id.btnEnableAutoCheck)
        btnDownloadUpdate = findViewById(R.id.btnDownloadUpdate)
        btnDownloadPokemonGo = findViewById(R.id.btnDownloadPokemonGo)
        btnScheduleChecks = findViewById(R.id.btnScheduleChecks)
        btnChangeTheme = findViewById(R.id.btnChangeTheme)
        llSettingsHeader = findViewById(R.id.llSettingsHeader)
        llExpandableContent = findViewById(R.id.llExpandableContent)
        ivExpandIcon = findViewById(R.id.ivExpandIcon)
        
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
        
        // Restore saved version info from previous checks
        displaySavedVersionInfo()
        
        // Display next check time
        displayNextCheckTime()
        
        // Download button click
        btnDownloadUpdate.setOnClickListener {
            openPGSharpWebsite()
        }
        
        // Download Pokémon GO button click
        btnDownloadPokemonGo.setOnClickListener {
            openPGSharpWebsite()
        }
        
        // Schedule checks button
        btnScheduleChecks.setOnClickListener {
            startActivity(Intent(this, ScheduledChecksActivity::class.java))
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

        btnChangeTheme.setOnClickListener {
            showThemeChooserDialog()
        }

        // Settings expand/collapse
        llSettingsHeader.setOnClickListener {
            toggleSettingsExpansion()
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
                currentInstalledVersion = versionName
                tvInstalledVersion.text = getString(R.string.installed_pokemon_go, versionName)
                btnDownloadPokemonGo.visibility = View.GONE
                btnCheckNow.isEnabled = true
                return
            } catch (e: PackageManager.NameNotFoundException) {
                // Try next package
            }
        }
        
        // If we get here, no package was found - let's search for it
        currentInstalledVersion = null
        tvInstalledVersion.text = getString(R.string.pokemon_go_not_found)
        btnDownloadPokemonGo.visibility = View.VISIBLE
        btnCheckNow.isEnabled = false
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
                            
                            // Persist version data so it survives app restarts
                            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
                                putString(KEY_LATEST_VERSION, latestVersion)
                                putString(KEY_INSTALLED_VERSION, installedVersion)
                                putBoolean(KEY_UPDATE_AVAILABLE, updateAvailable)
                            }
                            saveLastCheckTime()
                            
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
    
    // Best-effort fallback when none of the packages declared in <queries> are found.
    // On Android 11+ getInstalledApplications only returns visible packages, which is
    // acceptable here: we deliberately avoid QUERY_ALL_PACKAGES for privacy reasons.
    @SuppressLint("QueryPermissionsNeeded")
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
                resources.getQuantityString(R.plurals.found_packages, foundPackages.size, foundPackages.size),
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
        prefs.edit { putBoolean(KEY_AUTO_CHECK_ENABLED, enabled) }
        isAutoCheckEnabled = enabled
    }
    
    private fun saveLastCheckTime() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit { putLong(KEY_LAST_CHECK_TIME, System.currentTimeMillis()) }
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
    
    private fun displaySavedVersionInfo() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val latestVersion = prefs.getString(KEY_LATEST_VERSION, null)
        val installedVersion = prefs.getString(KEY_INSTALLED_VERSION, null)
        
        if (latestVersion != null && installedVersion != null) {
            tvLatestVersion.text = getString(R.string.latest_on_pgsharp, latestVersion)
            tvInstalledVersion.text = getString(R.string.installed_pokemon_go, installedVersion)

            // Re-derive the update state from the freshly read installed version so a
            // stale flag can't leave the app stuck on the Download button after the
            // user has already updated. Falls back to the stored flag if Pokémon GO
            // isn't currently detected.
            val currentVersion = currentInstalledVersion
            val updateAvailableNow = if (currentVersion != null) {
                VersionCheckWorker.isUpdateAvailable(currentVersion, latestVersion)
            } else {
                prefs.getBoolean(KEY_UPDATE_AVAILABLE, false)
            }
            if (updateAvailableNow) {
                updateStatus(VersionStatus.UPDATE_AVAILABLE)
            } else {
                updateStatus(VersionStatus.UP_TO_DATE)
            }
        }
    }

    private fun getTimeAgo(timeMillis: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timeMillis
        
        return when {
            diff < 60000 -> getString(R.string.just_now)
            diff < 3600000 -> quantityString(R.plurals.minutes_ago, diff / 60000)
            diff < 86400000 -> quantityString(R.plurals.hours_ago, diff / 3600000)
            diff < 604800000 -> quantityString(R.plurals.days_ago, diff / 86400000)
            else -> quantityString(R.plurals.weeks_ago, diff / 604800000)
        }
    }
    
    private fun quantityString(pluralRes: Int, count: Long): String =
        resources.getQuantityString(pluralRes, count.toInt(), count)
    
    private fun displayNextCheckTime() {
        if (!isAutoCheckEnabled) {
            tvNextCheck.visibility = View.GONE
            return
        }
        
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val lastCheckTime = prefs.getLong(KEY_LAST_CHECK_TIME, 0)
        
        if (lastCheckTime == 0L) {
            tvNextCheck.visibility = View.GONE
            return
        }
        
        val nextCheckTime = lastCheckTime + (CHECK_INTERVAL_HOURS * 3600000)
        val now = System.currentTimeMillis()
        
        if (nextCheckTime > now) {
            val timeUntil = getTimeUntil(nextCheckTime)
            tvNextCheck.text = getString(R.string.next_check, timeUntil)
            tvNextCheck.visibility = View.VISIBLE
        } else {
            tvNextCheck.text = getString(R.string.next_check, getString(R.string.soon))
            tvNextCheck.visibility = View.VISIBLE
        }
    }
    
    private fun getTimeUntil(timeMillis: Long): String {
        val now = System.currentTimeMillis()
        val diff = timeMillis - now
        
        return when {
            diff < 60000 -> getString(R.string.in_less_than_a_minute)
            diff < 3600000 -> quantityString(R.plurals.in_minutes, diff / 60000)
            diff < 86400000 -> quantityString(R.plurals.in_hours, diff / 3600000)
            else -> quantityString(R.plurals.in_days, diff / 86400000)
        }
    }
    
    private fun openPGSharpWebsite() {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.data = "https://api.pgsharp.com/download".toUri()
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
                    data = "package:$packageName".toUri()
                }
                startActivity(intent)
            }
        }
    }
    
    private fun updateStatus(status: VersionStatus) {
        when (status) {
            VersionStatus.UP_TO_DATE -> {
                tvStatus.text = getString(R.string.your_pokemon_go_version_matches_pgsharp)
                tvStatus.setTextColor(getColor(R.color.status_success))
                btnDownloadUpdate.visibility = View.GONE
                btnCheckNow.visibility = View.VISIBLE
            }
            VersionStatus.UPDATE_AVAILABLE -> {
                tvStatus.text = getString(R.string.update_available_new_version_found)
                tvStatus.setTextColor(getColor(R.color.status_warning))
                btnDownloadUpdate.visibility = View.VISIBLE
                btnCheckNow.visibility = View.GONE
            }
            VersionStatus.ERROR -> {
                tvStatus.text = getString(R.string.unable_to_check_for_updates_please_try_again)
                tvStatus.setTextColor(getColor(R.color.status_error))
                btnDownloadUpdate.visibility = View.GONE
                btnCheckNow.visibility = View.VISIBLE
            }
            VersionStatus.CHECKING -> {
                tvStatus.text = getString(R.string.checking_for_updates)
                tvStatus.setTextColor(getColor(R.color.status_info))
                btnDownloadUpdate.visibility = View.GONE
                btnCheckNow.visibility = View.VISIBLE
            }
        }
    }

    private fun loadAndApplyTheme() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val selectedTheme = prefs.getInt(KEY_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(selectedTheme)
    }

    private fun showThemeChooserDialog() {
        val themes = resources.getStringArray(R.array.themes)
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val currentThemeMode = prefs.getInt(KEY_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

        val checkedItem = when (currentThemeMode) {
            AppCompatDelegate.MODE_NIGHT_NO -> 0 // Light
            AppCompatDelegate.MODE_NIGHT_YES -> 1 // Dark
            else -> 2 // System Default
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.theme))
            .setSingleChoiceItems(themes, checkedItem) { dialog, which ->
                val selectedTheme = when (which) {
                    0 -> AppCompatDelegate.MODE_NIGHT_NO
                    1 -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }

                prefs.edit { putInt(KEY_THEME, selectedTheme) }
                AppCompatDelegate.setDefaultNightMode(selectedTheme)
                dialog.dismiss()
            }
            .show()
    }

    private fun toggleSettingsExpansion() {
        if (isSettingsExpanded) {
            // Collapse
            collapseView(llExpandableContent)
            rotateIcon(ivExpandIcon, 180f, 0f)
        } else {
            // Expand
            expandView(llExpandableContent)
            rotateIcon(ivExpandIcon, 0f, 180f)
        }
        isSettingsExpanded = !isSettingsExpanded
    }

    private fun expandView(view: View) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec((view.parent as View).width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val targetHeight = view.measuredHeight

        view.layoutParams.height = 0
        view.visibility = View.VISIBLE

        val a = object : android.view.animation.Animation() {
            override fun applyTransformation(interpolatedTime: Float, t: android.view.animation.Transformation?) {
                view.layoutParams.height = if (interpolatedTime == 1f) {
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                } else {
                    (targetHeight * interpolatedTime).toInt()
                }
                view.requestLayout()
            }

            override fun willChangeBounds(): Boolean = true
        }

        a.duration = 300
        view.startAnimation(a)
    }

    private fun collapseView(view: View) {
        val initialHeight = view.measuredHeight

        val a = object : android.view.animation.Animation() {
            override fun applyTransformation(interpolatedTime: Float, t: android.view.animation.Transformation?) {
                if (interpolatedTime == 1f) {
                    view.visibility = View.GONE
                } else {
                    view.layoutParams.height = initialHeight - (initialHeight * interpolatedTime).toInt()
                    view.requestLayout()
                }
            }

            override fun willChangeBounds(): Boolean = true
        }

        a.duration = 300
        view.startAnimation(a)
    }

    private fun rotateIcon(view: android.widget.ImageView, fromDegrees: Float, toDegrees: Float) {
        val rotate = android.view.animation.RotateAnimation(
            fromDegrees, toDegrees,
            android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
            android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f
        )
        rotate.duration = 300
        rotate.fillAfter = true
        view.startAnimation(rotate)
    }
}
