package com.linkplay.app

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Keeps a phone link on the network while Link Play is in the background. Android 15+ cuts apps in
 * the background off the network (the moment the user switched to WhatsApp, every send failed with
 * EPERM and the link timed out), and Doze / battery saver do the same with the screen off; a
 * foreground service lifts both. It runs from the moment a phone link starts (Host a game / Join)
 * until it ends, with an ongoing "In <room> · tap to return" notification and a Leave action.
 *
 * MainActivity owns the link; this service only keeps the app in the foreground. It is started with
 * a plain startService, and only while the activity is resumed, which meets Android 12+'s rules for
 * starting a foreground service (and there's no deadline to miss and crash on, as there is after
 * startForegroundService). If Android refuses anyway, it stops itself and the activity tries again on
 * its next onResume. Later start commands only change the notification's words.
 */
class LinkService : Service() {
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "In a link play room · tap to return"
        val hosting = intent?.getBooleanExtra(EXTRA_HOSTING, false) == true
        val n = notification(text, hosting)
        if (foreground) {
            // Already in the foreground: just new words. Not startForeground again, which
            // Android 12+ may refuse while the app is in the background.
            try {
                getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, n)
            } catch (e: RuntimeException) {
                Log.w(TAG, "service: notification update failed: $e")
            }
            return START_NOT_STICKY
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(NOTIFICATION_ID, n)
            foreground = true
            running = true
            Log.i(TAG, "service: in the foreground; the link keeps its network while the app is in the background")
        } catch (e: RuntimeException) {
            // Android 12+: ForegroundServiceStartNotAllowedException (the app was in the background
            // already); 14+: SecurityException or a type exception if the type's needs aren't met.
            Log.w(TAG, "service: Android refused the foreground service (${why(e)}); " +
                "the link may lose its network in the background")
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            Log.i(TAG, "service: stopped")
        }
        foreground = false
        running = false
        super.onDestroy()
    }

    private fun notification(text: String, hosting: Boolean): Notification {
        // Low importance: no sound, no pop-up; it just sits in the shade while the link runs.
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Link play", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while you're in a link play room, so the link keeps working in the background"
                setShowBadge(false)
            }
        )
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        // Like the launcher icon: brings the running app back as it was.
        val back = Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val leave = Intent(this, MainActivity::class.java).setAction(ACTION_LEAVE).putExtra(EXTRA_TOKEN, TOKEN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(PendingIntent.getActivity(this, 0, back, flags))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_notify),
                if (hosting) "Close room" else "Leave", PendingIntent.getActivity(this, 1, leave, flags)).build())
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }

    companion object {
        /** The notification's Leave / Close room action: MainActivity leaves (or closes) the room. */
        const val ACTION_LEAVE = "com.linkplay.app.action.LEAVE"
        const val EXTRA_TOKEN = "token"
        /** Random per process: only our own notification knows it, so other apps can't fire Leave at the exported activity. */
        val TOKEN: String = java.util.UUID.randomUUID().toString()
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_HOSTING = "hosting"
        private const val CHANNEL = "link"
        private const val NOTIFICATION_ID = 1
        private const val TAG = PhoneLink.TAG

        /** The service is in the foreground (set on the main thread). */
        @Volatile var running = false
            private set

        /**
         * Starts the service, or gives the running one new words. Call it while the activity is
         * resumed (Android refuses a new foreground service from the background). False if Android
         * refused to start it at all.
         */
        fun start(context: Context, text: String, hosting: Boolean): Boolean = try {
            val i = Intent(context, LinkService::class.java).putExtra(EXTRA_TEXT, text).putExtra(EXTRA_HOSTING, hosting)
            context.startService(i) != null
        } catch (e: RuntimeException) { // IllegalStateException: the app is in the background; SecurityException
            Log.w(TAG, "service: not started (${why(e)})")
            false
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, LinkService::class.java))
            } catch (e: RuntimeException) {
                Log.w(TAG, "service: stop failed: $e")
            }
        }

        private fun why(e: RuntimeException): String = when {
            Build.VERSION.SDK_INT >= 31 && e is ForegroundServiceStartNotAllowedException -> "not allowed while the app is in the background"
            e is SecurityException -> "missing permission: ${e.message}"
            else -> e.toString()
        }
    }
}
