package com.loqui.android.trigger

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.loqui.android.MainActivity
import com.loqui.android.R
import com.loqui.android.capture.CaptureFeedback
import com.loqui.android.capture.CaptureStateHolder
import com.loqui.android.capture.DictationTap
import com.loqui.android.capture.VoiceInputService
import com.loqui.android.capture.dictationTapFor
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Keeps the floating trigger alive over other apps and relays taps to
 * [VoiceInputService]. It holds the overlay window and nothing else: no audio, no
 * provider calls, no screen access.
 */
@AndroidEntryPoint
class BubbleService : Service() {

    @Inject
    lateinit var bubbleController: BubbleController

    @Inject
    lateinit var captureStateHolder: CaptureStateHolder

    @Inject
    lateinit var captureFeedback: CaptureFeedback

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main.immediate)
    private var stateJob: Job? = null
    private var isForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startBubble()
            ACTION_STOP -> stopBubble()
            else -> Timber.w("BubbleService started without a known action")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stateJob?.cancel()
        bubbleController.hide()
        serviceJob.cancel()
        if (isForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        super.onDestroy()
    }

    private fun startBubble() {
        if (!canDrawOverlays()) {
            Timber.w("Bubble requested without SYSTEM_ALERT_WINDOW")
            toast(getString(R.string.permission_overlay_body))
            stopSelf()
            return
        }

        if (!goForeground()) {
            stopSelf()
            return
        }

        val attached = bubbleController.show(onTap = ::onBubbleTapped)
        if (!attached) {
            toast(getString(R.string.capture_start_failed))
            stopSelf()
            return
        }

        observeState()
    }

    private fun onBubbleTapped() {
        when (dictationTapFor(captureStateHolder.state.value)) {
            DictationTap.START -> {
                Timber.d("Bubble tapped: starting capture")
                VoiceInputService.start(this)
            }

            DictationTap.STOP -> {
                Timber.d("Bubble tapped while busy: stopping capture")
                VoiceInputService.cancel(this)
            }
        }
    }

    private fun observeState() {
        if (stateJob?.isActive == true) return
        stateJob = serviceScope.launch {
            captureStateHolder.state.collectLatest { state ->
                bubbleController.render(state)
                captureFeedback.report(state)
            }
        }
    }

    private fun stopBubble() {
        bubbleController.hide()
        stopSelf()
    }

    private fun goForeground(): Boolean {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }

        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
            isForeground = true
            true
        } catch (throwable: Throwable) {
            Timber.e(throwable, "Foreground promotion for the bubble failed")
            false
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, BubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, BUBBLE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_loqui_bubble)
            .setContentTitle(getString(R.string.bubble_notification_title))
            .setContentText(getString(R.string.bubble_notification_text))
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.home_stop_bubble), stop)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(BUBBLE_CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            BUBBLE_CHANNEL_ID,
            getString(R.string.notification_channel_bubble),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_bubble_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(this)

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val ACTION_START = "com.loqui.android.action.START_BUBBLE"
        const val ACTION_STOP = "com.loqui.android.action.STOP_BUBBLE"

        private const val BUBBLE_CHANNEL_ID = "loqui_bubble"
        private const val NOTIFICATION_ID = 1002
        private const val REQUEST_OPEN_APP = 1
        private const val REQUEST_STOP = 2

        /** Requires the overlay permission; callers should gate the UI on it. */
        fun start(context: Context) {
            val intent = Intent(context, BubbleService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, BubbleService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }

        /** True when the platform permission needed to show the bubble is granted. */
        fun hasOverlayPermission(context: Context): Boolean =
            Settings.canDrawOverlays(context)

        /** Convenience for onboarding copy: is RECORD_AUDIO granted? */
        fun hasMicrophonePermission(context: Context): Boolean = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
