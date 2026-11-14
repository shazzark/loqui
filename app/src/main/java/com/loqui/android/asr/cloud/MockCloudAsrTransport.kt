package com.loqui.android.asr.cloud

import kotlinx.coroutines.delay

/**
 * Stand-in for the Deepgram / AssemblyAI / Google STT client.
 *
 * It performs **no network I/O** — no `INTERNET` permission is requested yet — and
 * returns a canned transcript, so the full capture → endpoint → cloud provider →
 * insertion path can be exercised end to end before a real client exists.
 *
 * It records only shape metadata about the request (never the key itself), which is
 * what the unit tests assert on.
 */
class MockCloudAsrTransport(
    private val cannedTranscript: String = CANNED_TRANSCRIPT,
    private val simulatedLatencyMs: Long = SIMULATED_LATENCY_MS,
) : CloudAsrTransport {

    /** Number of simulated requests; proves the network path was or was not entered. */
    var calls: Int = 0
        private set

    var lastSampleRateHz: Int = 0
        private set

    var lastAudioBytes: Long = 0
        private set

    var lastKeyLength: Int = 0
        private set

    override suspend fun transcribe(request: CloudAsrRequest): CloudAsrResult {
        calls++
        lastSampleRateHz = request.sampleRateHz
        lastAudioBytes = request.audioBytes
        lastKeyLength = request.apiKey.length

        delay(simulatedLatencyMs)
        return CloudAsrResult.Success(cannedTranscript)
    }

    companion object {
        const val CANNED_TRANSCRIPT = "This is a Loqui cloud mock transcript."

        private const val SIMULATED_LATENCY_MS = 120L
    }
}
