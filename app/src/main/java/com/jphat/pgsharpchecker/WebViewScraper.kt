package com.jphat.pgsharpchecker

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.TimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * WebView-based scraper to bypass Cloudflare and other bot protections.
 * Uses Android's WebView which is recognized as a legitimate browser by most websites.
 */
class WebViewScraper(private val context: Context) {
    
    companion object {
        private const val TAG = "WebViewScraper"
        private const val LOAD_TIMEOUT_MS = 30000L
    }
    
    /**
     * Fetch a URL using WebView and return the page content as HTML string
     * This bypasses Cloudflare and other bot protections that block automated clients
     * Note: WebView must run on the main thread, so we use Handler
     */
    suspend fun fetchPageContent(url: String): String = suspendCancellableCoroutine { continuation ->
        val mainHandler = Handler(Looper.getMainLooper())
        
        mainHandler.post {
            try {
                val webView = WebView(context)
                var pageLoaded = false
                var timeoutReached = false
                
                // Configure WebView settings
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    // Allow mixed content if needed
                    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    // Set user agent to mimic browser
                    userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                }
                
                // Set a custom WebViewClient to handle page loading
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (!pageLoaded && !timeoutReached) {
                            pageLoaded = true
                            Log.d(TAG, "Page finished loading: $url")
                            
                            // Extract the HTML content
                            view?.evaluateJavascript("(function() { return document.documentElement.outerHTML; })()") { html ->
                                try {
                                    // Remove quotes added by evaluateJavascript
                                    val cleanHtml = if (html != null && html.startsWith("\"") && html.endsWith("\"")) {
                                        html.substring(1, html.length - 1)
                                            .replace("\\\"", "\"")
                                            .replace("\\n", "\n")
                                            .replace("\\t", "\t")
                                            .replace("\\\\", "\\")
                                    } else {
                                        html ?: ""
                                    }
                                    
                                    Log.d(TAG, "Successfully extracted HTML, length: ${cleanHtml.length}")
                                    webView.stopLoading()
                                    webView.destroy()
                                    continuation.resume(cleanHtml)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error processing HTML: ${e.message}", e)
                                    webView.destroy()
                                    continuation.resumeWithException(e)
                                }
                            }
                        }
                    }
                    
                    override fun onReceivedError(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest?,
                        error: android.webkit.WebResourceError?
                    ) {
                        super.onReceivedError(view, request, error)
                        Log.e(TAG, "WebView error: ${error?.description}, URL: ${request?.url}")
                        if (!pageLoaded && !timeoutReached && request?.url.toString() == url) {
                            timeoutReached = true
                            webView.destroy()
                            continuation.resumeWithException(
                                Exception("Failed to load page: ${error?.description}")
                            )
                        }
                    }
                }
                
                // Start loading the page
                Log.d(TAG, "Starting to load: $url")
                webView.loadUrl(url)
                
                // Set a timeout to prevent indefinite waiting
                val timeoutThread = Thread {
                    Thread.sleep(LOAD_TIMEOUT_MS)
                    if (!pageLoaded && !timeoutReached) {
                        timeoutReached = true
                        Log.w(TAG, "Page load timeout reached for: $url")
                        mainHandler.post {
                            try {
                                webView.stopLoading()
                                webView.destroy()
                            } catch (e: Exception) {
                                Log.e(TAG, "Error during timeout cleanup: ${e.message}")
                            }
                        }
                        continuation.resumeWithException(TimeoutException("Page load timeout"))
                    }
                }
                timeoutThread.isDaemon = true
                timeoutThread.start()
                
            } catch (e: Exception) {
                Log.e(TAG, "Error in fetchPageContent: ${e.message}", e)
                continuation.resumeWithException(e)
            }
        }
    }
}
