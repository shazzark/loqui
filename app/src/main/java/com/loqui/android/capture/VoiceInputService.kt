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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.loqui.android.BuildConfig
import com.loqui.android.MainActivity
import com.loqui.android.R
import com.loqui.android.asr.AsrCoordinator
import com.loqui.android.asr.AsrFailure
import com.loqui.android.asr.AsrFailureMessage
import com.loqui.android.asr.AsrSessionEvent
import com.loqui.android.asr.messageFor
import com.loqui.android.audio.AudioCaptureStream
import com.loqui.android.audio.vad.SpeechEndpointer
import com.loqui.android.di.ApplicationScope
import com.loqui.android.hardening.CaptureStartFailure
import com.loqui.android.hardening.captureStartFailureMessage
import com.loqui.android.hardening.classifyCaptureStartFailure
import com.loqui.android.insertion.InsertResult
import com.loqui.android.insertion.InsertRoute
import com.loqui.android.insertion.TextInserter
import com.loqui.android.llm.CorrectionOutcome
import com.loqui.android.llm.LlmCoordinator
import com.loqui.android.settings.AsrMode
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The only component that records audio. It is started by the bubble (or the app
 * UI) and runs as a `microphone` foreground service so capture survives the user
 * switching apps.
 *
 * Phase 2 wires the real capture path: `AudioRecord` at 16 kHz mono PCM-16 into a
 * ring buffer, VAD endpointing, then the ASR provider selected by settings.
 */
@AndroidEntryPoint
class VoiceInputService : Service() {

    @Inject
    lateinit var asrCoordinator: AsrCoordinator

    @Inject
    lateinit var audioCaptureStream: AudioCaptureStream

    @Inject
    lateinit var speechEndpointer: SpeechEndpointer

    @Inject
    lateinit var textInserter: TextInserter

    @Inject
    lateinit var llmCoordinator: LlmCoordinator

    @Inject
    lateinit var captureStateHolder: CaptureStateHolder

    @Inject
    lateinit var captureFeedback: CaptureFeedback

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main.immediate)
    private var captureJob: Job? = null
    private var isForeground = false

    /** A stop the user asked for: the recogniser is flushing, not failing. */
    private var gracefulStopInProgress = false
    private var flushWatchdog: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CAPTURE -> startCapture()
            ACTION_CANCEL -> cancelCapture()
            else -> Timber.w("VoiceInputService started without a known action")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        captureJob?.cancel()
        // The session died with the service (explicit cancel or a system kill): never
        // leave the bubble stuck on "Listening".
        if (isSessionActive(captureStateHolder.state.value)) {
            captureStateHolder.reset()
        }
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

        // A stop from the previous session must not close this one on its first frame.
        gracefulStopInProgress = false
        flushWatchdog?.cancel()
        flushWatchdog = null

        // Android 14 refuses a `microphone` promotion without the grant, and a grant that
        // was revoked must produce a message rather than a crash on the audio thread.
        if (!hasMicrophonePermission()) {
            Timber.w("Capture requested without RECORD_AUDIO")
            publish(CaptureState.Error(getString(R.string.capture_error_mic_permission)))
            stopSelf()
            return
        }

        val promotionFailure = goForeground()
        if (promotionFailure != null) {
            // Never record without the foreground promotion: a blocked background start
            // means the platform will cut the microphone anyway.
            publish(CaptureState.Error(getString(captureStartFailureMessage(promotionFailure))))
            stopSelf()
            return
        }

        captureJob = serviceScope.launch {
            captureStateHolder.update(CaptureState.Listening)
            val permissionWatch = launch { watchMicrophonePermission() }
            val stallWatch = launch { watchForStalledAudio() }
            try {
                // Mic -> ring buffer -> VAD. The endpointer completes the flow at the
                // utterance endpoint (silence, 10 s ceiling, or 8 s without speech),
                // which stops capture and releases the mic.
                val utterance = speechEndpointer.segment(audioCaptureStream.frames())
                Timber.d("Utterance flow ready; handing it to the ASR provider")
                asrCoordinator.transcribe(utterance).collect { event ->
                    handleSessionEvent(event)
                }
                Timber.d("ASR session ended without an outcome")
            } catch (cancellation: CancellationException) {
                // Session cancelled (bubble stop, service destroy): not a failure.
                Timber.d("Capture session cancelled")
                throw cancellation
            } catch (revoked: SecurityException) {
                // Android 14+ takes the microphone off a running service by denying the
                // next audio read; report it and let the session end cleanly.
                Timber.w(revoked, "Microphone access lost while capturing")
                publish(CaptureState.Error(getString(R.string.capture_error_mic_revoked)))
            } catch (throwable: Throwable) {
                Timber.e(throwable, "Capture session failed")
                publish(CaptureState.Error(getString(R.string.capture_error_generic)))
            } finally {
                permissionWatch.cancel()
                stallWatch.cancel()
                endSession()
            }
        }
    }

    /**
     * Last-resort guard for a session that never reaches an endpoint.
     *
     * A healthy session leaves [CaptureState.Listening] within the endpointer's own
     * ceilings (10 s of speech, 8 s of silence), so still listening after
     * [SESSION_WATCHDOG_MS] means the microphone stopped delivering frames. Cancelling
     * the job releases the microphone and takes the bubble out of "Listening"; the
     * frame ceilings in [SpeechEndpointer] are what guarantee the captured audio still
     * reaches the recogniser.
     */
    private suspend fun watchForStalledAudio() {
        delay(SESSION_WATCHDOG_MS)
        if (captureStateHolder.state.value != CaptureState.Listening) return

        Timber.w(
            "No endpoint after %d ms; the microphone stopped delivering audio",
            SESSION_WATCHDOG_MS,
        )
        publish(CaptureState.Error(getString(R.string.capture_error_audio_stalled)))
        captureJob?.cancel()
    }

    /**
     * Ends the session as soon as the grant disappears.
     *
     * The system usually kills the app on revocation, but OEM builds that keep it alive
     * would otherwise leave a dead microphone and a bubble stuck on "Listening". Polling a
     * permission is cheap and only runs while a session is active.
     */
    private suspend fun watchMicrophonePermission() {
        while (currentCoroutineContext().isActive) {
            delay(PERMISSION_POLL_MS)
            if (hasMicrophonePermission()) continue

            Timber.w("RECORD_AUDIO revoked while capturing; ending the session")
            publish(CaptureState.Error(getString(R.string.capture_error_mic_revoked)))
            captureJob?.cancel()
            return
        }
    }

    private suspend fun handleSessionEvent(event: AsrSessionEvent) {
        when (event) {
            AsrSessionEvent.NeedsKey -> {
                Timber.d("ASR session needs a cloud key")
                publish(CaptureState.NeedsKey)
            }

            is AsrSessionEvent.Failed -> when {
                // Stopped by the user before anything was recognised: the session is over,
                // not failed. No toast, no red bubble.
                event.reason == AsrFailure.NO_SPEECH && gracefulStopInProgress -> {
                    Timber.d("Flush after the stop produced no text; closing silently")
                    captureStateHolder.reset()
                }

                // A cancellation is never reported as an error.
                event.reason == AsrFailure.CANCELLED -> {
                    Timber.d("ASR session cancelled; closing the bubble silently")
                    captureStateHolder.reset()
                }

                else -> {
                    Timber.w("ASR session failed: %s (%s)", event.reason, event.mode)
                    publish(CaptureState.Error(describe(event.reason, event.mode)))
                }
            }

            is AsrSessionEvent.Transcript -> {
                // Length only: the transcript is never logged.
                Timber.d("Transcript ready (%d chars)", event.text.length)
                insert(event.text)
            }
        }
    }

    /**
     * Optional clean-up, then insertion.
     *
     * The state is already [CaptureState.Processing] before the corrector runs, so the
     * bubble keeps its spinner. [LlmCoordinator] bounds the pass at one second and hands
     * back the raw transcript whenever correction is off, unconfigured, slow or broken:
     * insertion never waits on a model.
     */
    private suspend fun insert(transcript: String) {
        publish(CaptureState.Processing)

        val correction = llmCoordinator.correctIfEnabled(transcript)
        logCorrection(transcript, correction)

        logInsertionText(correction.text)
        val result = textInserter.insert(correction.text)
        publish(
            when (result) {
                InsertResult.Pasted -> CaptureState.Inserted(InsertRoute.ACCESSIBILITY_PASTE)
                InsertResult.CopiedOnly -> CaptureState.Copied(correction.text)
                InsertResult.Unavailable -> CaptureState.Error(getString(R.string.capture_error_generic))
            },
        )
        Timber.i("Transcript inserted (%s, %d chars)", result, correction.text.length)
    }

    /** Lengths only: the transcript is never logged. */
    private fun logCorrection(transcript: String, correction: CorrectionOutcome) {
        if (correction.corrected) {
            Timber.d("Clean-up applied (%d -> %d chars)", transcript.length, correction.text.length)
        } else {
            Timber.d("Clean-up skipped (%s); inserting the raw transcript", correction.skipped)
        }
    }

    /** Transcript contents are emitted only in an opted-in debug build. */
    private fun logInsertionText(text: String) {
        if (BuildConfig.DEBUG && Log.isLoggable(TRANSCRIPT_LOG_TAG, Log.DEBUG)) {
            Timber.tag(TRANSCRIPT_LOG_TAG).d("Insertion: %s", text)
        }
    }

    /** Publishes the state for the bubble and shows the matching toast at most once. */
    private fun publish(state: CaptureState) {
        captureStateHolder.update(state)
        captureFeedback.report(state)
    }

    /**
     * A tap that stops a dictation, without throwing away what was said.
     *
     * The first tap closes the microphone but leaves the session running: the capture
     * flow *completes* (see [AudioCaptureStream.stop]), the endpointer closes the
     * utterance and the recogniser flushes, so the words already spoken are still
     * inserted. A second tap — or a flush that outlives [FLUSH_TIMEOUT_MS] — drops the
     * session outright.
     *
     * A cancel during insertion is ignored: the transcript is already on its way into the
     * field and killing the job mid-paste would lose it.
     */
    private fun cancelCapture() {
        val state = captureStateHolder.state.value
        when {
            // Second tap, or a session that never reached the recogniser.
            gracefulStopInProgress -> hardCancelCapture()

            state == CaptureState.Listening && captureJob?.isActive == true ->
                stopCaptureGracefully()

            state == CaptureState.Processing ->
                Timber.d("Cancel ignored: the transcript is being inserted")

            else -> hardCancelCapture()
        }
    }

    /**
     * Closes the microphone and lets the recogniser finish the sentence.
     *
     * The bubble shows [CaptureState.Processing] because the session is finishing, not
     * failing. If the flush yields nothing, [handleSessionEvent] closes the bubble
     * silently instead of claiming a transcription failure.
     */
    private fun stopCaptureGracefully() {
        gracefulStopInProgress = true
        Timber.d("Stop requested: closing capture so the recogniser can flush")
        publish(CaptureState.Processing)
        audioCaptureStream.stop()

        flushWatchdog = serviceScope.launch {
            delay(FLUSH_TIMEOUT_MS)
            if (captureJob?.isActive == true) {
                Timber.w(
                    "Recogniser still flushing %d ms after the stop; cancelling the session",
                    FLUSH_TIMEOUT_MS,
                )
                captureJob?.cancel()
            }
        }
    }

    /** Drops the session immediately: the user asked twice, or nothing was recording. */
    private fun hardCancelCapture() {
        flushWatchdog?.cancel()
        flushWatchdog = null
        captureJob?.cancel()
        captureStateHolder.reset()
        stopSelf()
    }

    private fun isSessionActive(state: CaptureState): Boolean =
        state == CaptureState.Listening || state == CaptureState.Processing

    /** Drops the foreground state and lets the bubble show the outcome for a moment. */
    private fun endSession() {
        flushWatchdog?.cancel()
        flushWatchdog = null
        if (captureStateHolder.state.value == CaptureState.Listening) {
            // No event ever reached the UI: without this the bubble would stay on
            // "Listening" while the service is already gone.
            Timber.w("Session ended while the bubble was still listening")
            captureStateHolder.reset()
        }
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
     * Promotes the service to the `microphone` foreground type.
     *
     * @return null when the promotion succeeded, otherwise why Android refused it; the
     * caller must not record without it.
     */
    private fun goForeground(): CaptureStartFailure? {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }

        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
            isForeground = true
            null
        } catch (throwable: Throwable) {
            Timber.e(throwable, "Foreground promotion for capture failed")
            // Android 12+ reports a blocked background start as IllegalStateException;
            // Android 14 reports a refused service type as SecurityException.
            classifyCaptureStartFailure(throwable, hasMicrophonePermission())
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

    private fun describe(reason: AsrFailure, mode: AsrMode): String = getString(
        when (messageFor(reason, mode)) {
            AsrFailureMessage.ON_DEVICE_FAILED -> R.string.asr_error_on_device_failed
            AsrFailureMessage.ON_DEVICE_TIMEOUT -> R.string.asr_error_on_device_timeout
            AsrFailureMessage.NETWORK_TIMEOUT -> R.string.asr_error_network_timeout
            AsrFailureMessage.CLOUD_REJECTED -> R.string.asr_error_provider
            AsrFailureMessage.CLOUD_KEY_MISSING -> R.string.asr_error_missing_key
            AsrFailureMessage.MODEL_MISSING -> R.string.asr_error_model_missing
            AsrFailureMessage.NO_SPEECH -> R.string.asr_error_no_speech
            AsrFailureMessage.CANCELLED -> R.string.asr_error_cancelled
        },
    )

    companion object {
        const val ACTION_START_CAPTURE = "com.loqui.android.action.START_CAPTURE"
        const val ACTION_CANCEL = "com.loqui.android.action.CANCEL"

        private const val CAPTURE_CHANNEL_ID = "loqui_capture"
        private const val NOTIFICATION_ID = 1001
        private const val REQUEST_OPEN_APP = 0
        private const val OUTCOME_VISIBLE_MS = 4_000L

        /** How often a running session re-checks the microphone grant. */
        private const val PERMISSION_POLL_MS = 500L

        /**
         * Wall-clock backstop for a session that never endpoints. Three times the
         * endpointer's 10 s ceiling, so a slow device that is still processing is not cut
         * off.
         */
        private const val SESSION_WATCHDOG_MS = 30_000L

        /**
         * How long a user-initiated stop may wait for the recogniser to flush.
         *
         * sherpa-onnx decodes the tail in a few hundred milliseconds on a phone; 3 s
         * covers a slow `armeabi-v7a` device without leaving the bubble spinning.
         */
        private const val FLUSH_TIMEOUT_MS = 3_000L

        private const val TRANSCRIPT_LOG_TAG = "LoquiTranscript"

        /**
         * Starts a dictation session; the service owns the microphone itself.
         *
         * `startForegroundService` throws when Android refuses the start — most often a
         * background start on 12+, or a missing `FOREGROUND_SERVICE_MICROPHONE` grant on
         * 14+. The failure is returned instead of swallowed so the bubble and the in-app
         * button can explain the silent no-op.
         *
         * @return null when the start was accepted, otherwise the classified reason.
         */
        fun start(context: Context): CaptureStartFailure? = try {
            val intent = Intent(context, VoiceInputService::class.java)
                .setAction(ACTION_START_CAPTURE)
            ContextCompat.startForegroundService(context, intent)
            null
        } catch (throwable: Throwable) {
            val hasMicrophone = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            val failure = classifyCaptureStartFailure(throwable, hasMicrophone)
            Timber.w(throwable, "Capture start refused: %s", failure)
            failure
        }

        fun cancel(context: Context) {
            val intent = Intent(context, VoiceInputService::class.java)
                .setAction(ACTION_CANCEL)
            context.startService(intent)
        }
    }
}

