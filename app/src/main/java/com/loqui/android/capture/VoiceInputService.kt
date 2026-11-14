package com.loqui.android.capture

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.loqui.android.MainActivity
import com.loqui.android.R
import com.loqui.android.asr.AsrCoordinator
import com.loqui.android.asr.AsrFailure
import com.loqui.android.asr.AsrFrameStreams
import com.loqui.android.asr.AsrSessionEvent
import com.loqui.android.di.ApplicationScope
import com.loqui.android.insertion.InsertResult
import com.loqui.android.insertion.InsertRoute
import com.loqui.android.insertion.TextInserter
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The only component that records audio. It is started by the bubble (or the app
 * UI) and runs as a `microphone` foreground service so capture survives the user
 * switching apps.
 *
 * Phase 1 wires the full path — permission gate, foreground notification, ASR
 * hand-off, insertion, state reporting — with a silent fake audio source.
 */
@AndroidEntryPoint
class VoiceInputService : Service() {

    @Inject
    lateinit var asrCoordinator: AsrCoordinator

    @Inject
    lateinit var textInserter: TextInserter

    @Inject
    lateinit var captureStateHolder: CaptureStateHolder

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main.immediate)
    private var captureJob: Job? = null
    private var isForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CAPTURE -> startCapture()
            ACTION_CANCEL -> stopSelf()
            else -> Timber.w("VoiceInputService started without a known action")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        captureJob?.cancel()
        serviceJob.cancel()
        if (isForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        super.onDestroy()
    }

    private fun startCapture() {
        if (captureJob?.isActive == true) {
            Timber.d("Capture already running; ignoring start")
            return
        }

        val canRecord = hasMicrophonePermission()
        if (!goForeground(withMicrophoneType = canRecord)) {
            captureStateHolder.update(
                CaptureState.Error(getString(R.string.capture_error_background_blocked)),
            )
            stopSelf()
            return
        }

        if (!canRecord) {
            Timber.w("Capture requested without RECORD_AUDIO")
            captureStateHolder.update(
                CaptureState.Error(getString(R.string.capture_error_mic_permission)),
            )
            endSession()
            return
        }

        captureJob = serviceScope.launch {
            captureStateHolder.update(CaptureState.Listening)
            try {
                asrCoordinator.transcribe(AsrFrameStreams.phaseOneSilence()).collect { event ->
                    handleSessionEvent(event)
                }
            } catch (throwable: Throwable) {
                Timber.e(throwable, "Capture session failed")
                captureStateHolder.update(
                    CaptureState.Error(getString(R.string.capture_error_generic)),
                )
            } finally {
                endSession()
            }
        }
    }

    private suspend fun handleSessionEvent(event: AsrSessionEvent) {
        when (event) {
            AsrSessionEvent.NeedsKey -> captureStateHolder.update(CaptureState.NeedsKey)
            is AsrSessionEvent.Failed -> captureStateHolder.update(
                CaptureState.Error(describe(event.reason)),
            )

            is AsrSessionEvent.Transcript -> insert(event.text)
        }
    }

    private suspend fun insert(text: String) {
        captureStateHolder.update(CaptureState.Processing)
        val result = textInserter.insert(text)
        captureStateHolder.update(
            when (result) {
                InsertResult.Pasted -> CaptureState.Inserted(InsertRoute.ACCESSIBILITY_PASTE)
                InsertResult.CopiedOnly -> CaptureState.Copied(text)
                InsertResult.Unavailable -> CaptureState.Error(getString(R.string.capture_error_generic))
            },
        )
        Timber.i("Transcript inserted (%s, %d chars)", result, text.length)
    }

    /** Drops the foreground state and lets the bubble show the outcome for a moment. */
    private fun endSession() {
        if (isForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        stopSelf()

        applicationScope.launch {
            delay(OUTCOME_VISIBLE_MS)
            if (captureStateHolder.state.value !is CaptureState.Listening) {
                captureStateHolder.reset()
            }
        }
    }

    /**
     * @return false when the platform refuses the foreground promotion, in which
     * case the caller must not start recording.
     */
    private fun goForeground(withMicrophoneType: Boolean): Boolean {
        val type = if (withMicrophoneType && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }

        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
            isForeground = true
            true
        } catch (throwable: Throwable) {
            Timber.e(throwable, "Foreground promotion for capture failed")
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

        return NotificationCompat.Builder(this, CAPTURE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_loqui_bubble)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CAPTURE_CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CAPTURE_CHANNEL_ID,
            getString(R.string.notification_channel_capture),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_capture_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun hasMicrophonePermission(): Boolean = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    private fun describe(reason: AsrFailure): String = when (reason) {
        AsrFailure.NETWORK_TIMEOUT -> getString(R.string.asr_error_network_timeout)
        AsrFailure.PROVIDER_ERROR -> getString(R.string.asr_error_provider)
        AsrFailure.CANCELLED -> getString(R.string.asr_error_cancelled)
        AsrFailure.MODEL_UNAVAILABLE -> getString(R.string.asr_error_model_missing)
    }

    companion object {
        const val ACTION_START_CAPTURE = "com.loqui.android.action.START_CAPTURE"
        const val ACTION_CANCEL = "com.loqui.android.action.CANCEL"

        private const val CAPTURE_CHANNEL_ID = "loqui_capture"
        private const val NOTIFICATION_ID = 1001
        private const val REQUEST_OPEN_APP = 0
        private const val OUTCOME_VISIBLE_MS = 4_000L

        /** Starts a dictation session; the service owns the microphone itself. */
        fun start(context: Context) {
            val intent = Intent(context, VoiceInputService::class.java)
                .setAction(ACTION_START_CAPTURE)
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, VoiceInputService::class.java)
                .setAction(ACTION_CANCEL)
            context.startService(intent)
        }
    }
}

