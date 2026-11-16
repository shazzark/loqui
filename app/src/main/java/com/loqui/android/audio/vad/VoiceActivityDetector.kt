package com.loqui.android.audio.vad

import com.loqui.android.asr.AudioFrame

/**
 * Decides whether one frame of audio contains speech.
 *
 * The interface exists so the endpointing state machine is independent of the
 * detector: Phase 2 ships [EnergyVad], and a Silero ONNX detector can replace it
 * without touching [SpeechEndpointer] or its tests.
 */
interface VoiceActivityDetector {

    /** Drops any per-stream state (noise floor, model state) before a new utterance. */
    fun reset()

    fun isSpeech(frame: AudioFrame): Boolean
}

/**
 * Energy-based detector: a frame is speech when its normalised RMS reaches
 * [threshold].
 *
 * Deliberately dependency-free and deterministic. It is good enough to prove the
 * capture → endpointer → ASR path end to end; the accuracy-critical Silero model
 * slots in behind [VoiceActivityDetector] when the on-device runtime lands.
 *
 * The default gate is [VadConfig]'s, deliberately low (-80 dBFS): a threshold that is
 * too high does not degrade quietly, it stops the onset from ever confirming and the
 * session runs until a timeout. Background noise above the gate is the cheaper failure:
 * the utterance still closes at the 15 s ceiling.
 */
class EnergyVad(
    private val threshold: Float = VadConfig().speechRmsThreshold,
) : VoiceActivityDetector {

    override fun reset() = Unit

    override fun isSpeech(frame: AudioFrame): Boolean =
        Pcm16Signal.rms(frame.pcm16) >= threshold
}
