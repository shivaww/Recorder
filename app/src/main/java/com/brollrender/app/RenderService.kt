package com.brollrender.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Foreground anchor for OVERNIGHT BATCH rendering (queue mode). It renders
 * nothing itself: RenderEngine and its WebView stay owned by MainActivity
 * (attached INVISIBLE, driven from the activity's UI thread - that wiring
 * is load-bearing and untouched). This service exists only to:
 *
 *  - keep the PROCESS in the foreground while the screen is off, so Android
 *    never freezes or kills it mid-batch (cached-process freezing, Doze);
 *  - hold a PARTIAL_WAKE_LOCK - CPU up, display free to turn off;
 *  - own the notification channel; the activity mirrors batch progress into
 *    the same NOTE_ID notification while the run lasts.
 *
 * FGS type: specialUse on API 34+ (no time limit - dataSync has a 6h/day cap
 * on Android 15, too short for overnight batches), dataSync on 29-33, plain
 * startForeground on 26-28. Task swiped away -> onTaskRemoved -> stop: the
 * batch loop dies with the activity, no orphaned wake lock.
 */
class RenderService : Service() {

    companion object {
        const val CH_ID = "broll_batch"
        const val NOTE_ID = 42

        /** Idempotent: called by the service AND by the activity before its
         *  first notify() - creating an existing channel is a no-op. */
        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CH_ID,
                    "Overnight batch",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
            )
        }
    }

    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        val n = note("Overnight render", "starting batch")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTE_ID, n)
        }
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BrollRender:batch")
            .also { it.acquire() } // no timeout by design: hours-long batch
        return START_NOT_STICKY
    }

    private fun note(title: String, text: String): Notification =
        Notification.Builder(this, CH_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Task swiped away: the activity (and the engine's WebView) is being
        // destroyed with it. Stop the service, release the wake lock.
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        wake?.let { w ->
            try {
                if (w.isHeld) w.release()
            } catch (_: Exception) {
            }
        }
        wake = null
        super.onDestroy()
    }
}
