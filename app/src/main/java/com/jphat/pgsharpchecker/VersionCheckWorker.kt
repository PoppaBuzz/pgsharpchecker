package com.jphat.pgsharpchecker

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.HttpURLConnection
import java.net.URL

class VersionCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    companion object {
        private const val TAG = "VersionCheckWorker"
        private val POKEMON_GO_PACKAGES = listOf(
            "com.nianticlabs.pokemongo",   // Official Pokémon GO
            "com.pgsharp.pokemongo",        // PGSharp
            "com.nianticproject.holoholo"   // Legacy Pokémon GO
        )
        private const val PGSHARP_URL = "https://www.pgsharp.com"
        private const val PGSHARP_DOWNLOAD_URL = "https://api.pgsharp.com/download"
        private const val MAX_REDIRECTS = 10

        /**
         * Compare two dot-separated version strings (e.g. "0.385.2").
         * Returns true if [latest] is greater than [installed].
         */
        fun isUpdateAvailable(installed: String, latest: String): Boolean {
            try {
                val installedParts = installed.split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
                val latestParts = latest.split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

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
    
    private val webViewScraper = WebViewScraper(context)
    
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            // Get installed version
            val installedVersion = getInstalledVersion()
            
            if (installedVersion == null) {
                Log.e(TAG, "Pokémon GO not installed")
                return@withContext Result.failure(
                    workDataOf("error" to "Pokémon GO app not found")
                )
            }
            
            // Get latest version from download URL redirect chain (source of truth)
            var latestVersion = getLatestVersionFromDownloadLink()

            if (latestVersion == null) {
                Log.w(TAG, "Download link parsing failed, trying page scrape...")
                latestVersion = getLatestVersionFromWebsite()
            }

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
            prefs.edit {
                putString("installed_version", installedVersion)
                putString("latest_version", latestVersion)
                putBoolean("update_available", updateAvailable)
            }
            
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
     * Get the installed version of Pokémon GO from PackageManager
     * Tries multiple package names to find the installed app
     */
    private fun getInstalledVersion(): String? {
        for (packageName in POKEMON_GO_PACKAGES) {
            try {
                val packageInfo = applicationContext.packageManager.getPackageInfo(packageName, 0)
                val version = packageInfo.versionName
                Log.d(TAG, "Found Pokémon GO package: $packageName with version: $version")
                return version
            } catch (e: PackageManager.NameNotFoundException) {
                Log.d(TAG, "Package $packageName not found, trying next...")
            }
        }
        Log.e(TAG, "No Pokémon GO package found on device")
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

    private suspend fun getLatestVersionFromDownloadLink(): String? = withContext(Dispatchers.IO) {
        try {
            var connection = URL(PGSHARP_DOWNLOAD_URL).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")

            var redirectCount = 0
            while (redirectCount < MAX_REDIRECTS) {
                val code = connection.responseCode
                if (code in 301..308) {
                    val location = connection.getHeaderField("Location") ?: break
                    connection.disconnect()
                    connection = URL(location).openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000
                    connection.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                    redirectCount++
                    Log.d(TAG, "Redirect $redirectCount -> $location")
                } else {
                    break
                }
            }

            val finalUrl = connection.url.toString()
            connection.disconnect()
            Log.d(TAG, "Download URL resolved to: $finalUrl")

            val match = Regex("""pgs[\d.]+_(\d+\.\d+\.\d+)""").find(finalUrl)
            if (match != null) {
                val version = match.groupValues[1]
                Log.d(TAG, "Parsed Pokemon GO version from download URL: $version")
                return@withContext version
            }

            Log.w(TAG, "No version pattern found in final URL: $finalUrl")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Download link parsing failed: ${e.message}", e)
            null
        }
    }

    private suspend fun getLatestVersionFromWebsite(): String? = withContext(Dispatchers.IO) {
        try {
            // Connect to the website and parse HTML using WebView
            val document = fetchPage(PGSHARP_URL)
            
            Log.d(TAG, "Successfully fetched pgsharp.com")
            
            // Get all text from the page
            val pageText = document.text()
            Log.d(TAG, "Page text length: ${pageText.length}")
            
            // Try multiple patterns to find Pokémon GO version (not PGSharp version)
            // Looking for patterns like "0.385.2" or "(0.385.2-G)" in the page
            val patterns = listOf(
                """\((\d+\.\d+\.\d+)[-\w]*\)""".toRegex(),  // Matches "(0.385.2-G)" or "(0.385.2)"
                """Pokemon\s*Go[:\s]+(\d+\.\d+\.\d+)""".toRegex(RegexOption.IGNORE_CASE),
                """PoGo[:\s]+(\d+\.\d+\.\d+)""".toRegex(RegexOption.IGNORE_CASE),
                """0\.(\d+\.\d+)""".toRegex()  // Matches Pokémon GO version pattern starting with 0.
            )
            
            for (pattern in patterns) {
                val matchResult = pattern.find(pageText)
                if (matchResult != null) {
                    var version = matchResult.groupValues[1]
                    // If we matched the 0.xxx pattern, prepend the 0.
                    if (pattern.pattern.startsWith("0\\.")) {
                        version = "0.$version"
                    }
                    // Only accept versions that look like Pokémon GO (start with 0.)
                    if (version.startsWith("0.")) {
                        Log.d(TAG, "Found Pokémon GO version: $version")
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
            // Look for Pokémon GO version in parentheses like (0.385.2-G)
            val pattern = """\((\d+\.\d+\.\d+)[-\w]*\)""".toRegex()
            val matchResult = pattern.find(pageText)
            
            matchResult?.groupValues?.get(1)?.also {
                if (it.startsWith("0.")) {
                    Log.d(TAG, "Found Pokémon GO version from alternative source: $it")
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
    
}
