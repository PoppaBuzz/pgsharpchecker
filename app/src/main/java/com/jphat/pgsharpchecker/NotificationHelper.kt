package com.jphat.pgsharpchecker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object NotificationHelper {
    
    private const val CHANNEL_ID = "version_check_channel"
    private const val CHANNEL_NAME = "Pokémon GO Version Checker"
    private const val NOTIFICATION_ID = 1001
    
    /**
     * Create notification channel (required for Android 8.0+)
     */
    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance).apply {
                description = "Notifications when your Pokémon GO version differs from PGSharp's supported version"
            }
            
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) 
                as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * Send notification when a new version is available
     */
    fun sendUpdateNotification(context: Context, installedVersion: String, latestVersion: String) {
        createNotificationChannel(context)
        
        // Intent to open PGSharp website for download
        val websiteIntent = Intent(Intent.ACTION_VIEW, "https://api.pgsharp.com/download".toUri())
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            websiteIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        // Build notification
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Pokémon GO Version Mismatch")
            .setContentText("PGSharp supports v$latestVersion (You have: $installedVersion)")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("PGSharp supports a different Pokémon GO version!\n\nYour Pokémon GO: $installedVersion\nPGSharp supports: $latestVersion\n\nTap to download the update from PGSharp")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        
        // Show notification
        try {
            with(NotificationManagerCompat.from(context)) {
                notify(NOTIFICATION_ID, notification)
            }
        } catch (e: SecurityException) {
            // Permission not granted, silently fail
        }
    }
}
