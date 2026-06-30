package com.jphat.pgsharpchecker

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class VersionCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    companion object {
        private const val TAG = "VersionCheckWorker"
        private val POKEMON_GO_PACKAGES = listOf(
            "com.nianticlabs.pokemongo",   // Official Pokemon Go
            "com.pgsharp.pokemongo",        // PGSharp
            "com.nianticproject.holoholo"   // Legacy Pokemon Go
        )
        private const val PGSHARP_URL = "https://www.pgsharp.com"
    }
    
    private val webViewScraper = WebViewScraper(context)
    
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            // Get installed version
            val installedVersion = getInstalledVersion()
            
            if (installedVersion == null) {
                Log.e(TAG, "Pokemon Go not installed")
                return@withContext Result.failure(
                    workDataOf("error" to "Pokemon Go app not found")
                )
            }
            
            // Get latest version from website
            var latestVersion = getLatestVersionFromWebsite()
            
            // If web scraping fails, try alternative method
            if (latestVersion == null) {
                Log.w(TAG, "Primary scraping failed, trying alternative...")
                latestVersion = getVersionFromAlternativeSource()
            }
            
            if (latestVersion == null) {
                Log.e(TAG, "Failed to fetch latest version from all sources")
                return@withContext Result.failure(
                    workDataOf("error" to "Failed to fetch version from website. Check internet connection.")
                )
            }
            
            // Compare versions
            val updateAvailable = isUpdateAvailable(installedVersion, latestVersion)
            
            Log.d(TAG, "Installed: $installedVersion, Latest: $latestVersion, Update Available: $updateAvailable")
            
            // Send notification if update is available
            if (updateAvailable) {
                NotificationHelper.sendUpdateNotification(
                    applicationContext,
                    installedVersion,
                    latestVersion
                )
            }
            
            // Save version info to SharedPreferences for persistence across app restarts
            val prefs = applicationContext.getSharedPreferences("PGSharpCheckerPrefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("installed_version", installedVersion)
                .putString("latest_version", latestVersion)
                .apply()
            
            // Return result with version information
            val outputData = workDataOf(
                "installed_version" to installedVersion,
                "latest_version" to latestVersion,
                "update_available" to updateAvailable
            )
            
            Result.success(outputData)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error checking version", e)
            Result.failure(workDataOf("error" to e.message))
        }
    }
    
    /**
     * Get the installed version of Pokemon Go from PackageManager
     * Tries multiple package names to find the installed app
     */
    private fun getInstalledVersion(): String? {
        for (packageName in POKEMON_GO_PACKAGES) {
            try {
                val packageInfo = applicationContext.packageManager.getPackageInfo(packageName, 0)
                val version = packageInfo.versionName
                Log.d(TAG, "Found Pokemon Go package: $packageName with version: $version")
                return version
            } catch (e: PackageManager.NameNotFoundException) {
                Log.d(TAG, "Package $packageName not found, trying next...")
            }
        }
        Log.e(TAG, "No Pokemon Go package found on device")
        return null
    }
    
    /**
     * Fetches a page using WebView (bypasses Cloudflare protection)
     * and returns a Jsoup Document for parsing.
     */
    private suspend fun fetchPage(url: String): Document {
        val html = webViewScraper.fetchPageContent(url)
        return Jsoup.parse(html, url)
    }

    /**
     * Scrape the latest version from pgsharp.com using WebView and Jsoup
     */
    private suspend fun getLatestVersionFromWebsite(): String? = withContext(Dispatchers.IO) {
        try {
            // Connect to the website and parse HTML using WebView
            val document = fetchPage(PGSHARP_URL)
            
            Log.d(TAG, "Successfully fetched pgsharp.com")
            
            // Get all text from the page
            val pageText = document.text()
            Log.d(TAG, "Page text length: ${pageText.length}")
            
            // Try multiple patterns to find Pokemon Go version (not PGSharp version)
            // Looking for patterns like "0.385.2" or "(0.385.2-G)" in the page
            val patterns = listOf(
                """\((\d+\.\d+\.\d+)[-\w]*\)""".toRegex(),  // Matches "(0.385.2-G)" or "(0.385.2)"
                """Pokemon\s*Go[:\s]+(\d+\.\d+\.\d+)""".toRegex(RegexOption.IGNORE_CASE),
                """PoGo[:\s]+(\d+\.\d+\.\d+)""".toRegex(RegexOption.IGNORE_CASE),
                """0\.(\d+\.\d+)""".toRegex()  // Matches Pokemon Go version pattern starting with 0.
            )
            
            for (pattern in patterns) {
                val matchResult = pattern.find(pageText)
                if (matchResult != null) {
                    var version = matchResult.groupValues[1]
                    // If we matched the 0.xxx pattern, prepend the 0.
                    if (pattern.pattern.startsWith("0\\.")) {
                        version = "0.$version"
                    }
                    // Only accept versions that look like Pokemon Go (start with 0.)
                    if (version.startsWith("0.")) {
                        Log.d(TAG, "Found Pokemon Go version: $version")
                        return@withContext version
                    }
                }
            }
            
            // Log a sample of the page for debugging
            Log.e(TAG, "Could not find version. Page sample: ${pageText.take(500)}")
            null
            
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching website: ${e.message}", e)
            null
        }
    }
    
    /**
     * Alternative method to get version - tries to fetch from pgsharp.com/download
     */
    private suspend fun getVersionFromAlternativeSource(): String? = withContext(Dispatchers.IO) {
        try {
            val document = fetchPage("$PGSHARP_URL/download")
            
            val pageText = document.text()
            // Look for Pokemon Go version in parentheses like (0.385.2-G)
            val pattern = """\((\d+\.\d+\.\d+)[-\w]*\)""".toRegex()
            val matchResult = pattern.find(pageText)
            
            matchResult?.groupValues?.get(1)?.also {
                if (it.startsWith("0.")) {
                    Log.d(TAG, "Found Pokemon Go version from alternative source: $it")
                    return@withContext it
                }
            }
            
            // Fallback: find any version starting with 0.
            val fallbackPattern = """0\.(\d+\.\d+)""".toRegex()
            fallbackPattern.find(pageText)?.let {
                val version = "0.${it.groupValues[1]}"
                Log.d(TAG, "Found version using fallback: $version")
                return@withContext version
            }
            
            null
        } catch (e: Exception) {
            Log.e(TAG, "Alternative source also failed: ${e.message}")
            null
        }
    }
    
    /**
     * Compare two version strings to determine if update is available
     * Returns true if latestVersion is greater than installedVersion
     */
    private fun isUpdateAvailable(installed: String, latest: String): Boolean {
        try {
            val installedParts = installed.split(".").map { it.toIntOrNull() ?: 0 }
            val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
            
            // Compare major, minor, patch versions
            for (i in 0 until maxOf(installedParts.size, latestParts.size)) {
                val installedPart = installedParts.getOrNull(i) ?: 0
                val latestPart = latestParts.getOrNull(i) ?: 0
                
                if (latestPart > installedPart) {
                    return true
                } else if (latestPart < installedPart) {
                    return false
                }
            }
            
            return false // Versions are equal
            
        } catch (e: Exception) {
            Log.e(TAG, "Error comparing versions", e)
            return false
        }
    }
}
