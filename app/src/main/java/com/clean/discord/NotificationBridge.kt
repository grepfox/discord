package com.clean.discord

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.webkit.JavascriptInterface
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Exposed to the page as `AndroidNotifier`. Backs the Notification polyfill in assets/notifications.js.
 */
class NotificationBridge(
    private val activity: MainActivity,
    private val onPermissionRequested: (callbackId: Int) -> Unit,
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val manager = NotificationManagerCompat.from(activity)

    companion object {
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_SERVICE = "service"
        const val EXTRA_NOTIFICATION_ID = "com.clean.discord.NOTIFICATION_ID"

        fun ensureChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH)
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_SERVICE, "Background connection", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Keeps Discord connected so messages and calls arrive"
                    setShowBadge(false)
                }
            )
        }

        fun canPost(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) return false
            return NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    fun shutdown() = executor.shutdown()

    @JavascriptInterface
    fun permission(): String = when {
        canPost(activity) -> "granted"
        // Not asked yet (or can still ask): let Discord show its "enable notifications" prompt.
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> "default"
        else -> "denied"
    }

    @JavascriptInterface
    fun requestPermission(callbackId: Int) {
        activity.runOnUiThread { onPermissionRequested(callbackId) }
    }

    @JavascriptInterface
    fun show(id: Int, title: String, body: String, tag: String, icon: String, silent: Boolean) {
        if (!canPost(activity)) return
        executor.execute {
            val largeIcon = loadIcon(icon)
            val tapIntent = Intent(activity, MainActivity::class.java)
                .putExtra(EXTRA_NOTIFICATION_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pending = PendingIntent.getActivity(
                activity, id, tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(activity, CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_stat_discord)
                .setColor(0xFF5865F2.toInt())
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setLargeIcon(largeIcon)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setSilent(silent)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()
            try {
                if (tag.isNotEmpty()) manager.notify(tag, 0, notification) else manager.notify(id, notification)
            } catch (_: SecurityException) {
            }
        }
    }

    @JavascriptInterface
    fun close(id: Int, tag: String) {
        if (tag.isNotEmpty()) manager.cancel(tag, 0) else manager.cancel(id)
    }

    /** Fetches the avatar Discord passes as the icon. Only Discord's own CDN is allowed. */
    private fun loadIcon(url: String): Bitmap? {
        val uri = Uri.parse(url)
        if (uri.scheme != "https" || !MainActivity.isInternalHost(uri.host ?: "")) return null
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        }
    }
}
