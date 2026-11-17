package com.loqui.android.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.loqui.android.models.ModelPaths
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One streaming recognition session. The provider feeds captured audio, drains decoded
 * results and finally asks for the tail; nothing here knows about sherpa-onnx, which is
 * what lets the orchestration be tested on the JVM without loading a native library.
 */
interface StreamingAsrSession {
    fun accept(samples: FloatArray, sampleRateHz: Int)

    fun isReady(): Boolean

    fun decode()

    fun text(): String

    /** Signals end of audio so the trailing partial becomes a final result. */
    fun finish()

    fun release()
}

/** Creates sessions from an installed model. Loading the recogniser is cached internally. */
interface StreamingAsrEngine {
    fun open(paths: ModelPaths, sampleRateHz: Int): StreamingAsrSession

    /**
     * Frees the cached recogniser. The next [open] loads its graphs again.
     *
     * Worth calling when the recogniser stops being wanted — the user switched to cloud
     * mode, or to a different on-device model — because the graphs are tens of megabytes
     * that nothing else on a phone reclaims while the process lives.
     */
    fun release() {}
}

/**
 * sherpa-onnx streaming Zipformer over the int8 transducer files.
 *
 * The recogniser is expensive to build (it loads ~68 MiB of ONNX graphs and takes seconds
 * on a phone), so it is kept for the app's lifetime and rebuilt only if the model paths or
 * sample rate change. `SherpaOnnxAsrProvider.warmUp()` pays that cost before the first
 * dictation instead of between the tap and the first recorded frame. Each dictation still
 * gets its own [OnlineStream]; recogniser state never leaks between utterances.
 */
@Singleton
class SherpaOnnxStreamingEngine @Inject constructor() : StreamingAsrEngine {

    private val lock = Any()
    private var recognizer: OnlineRecognizer? = null
    private var recognizerKey: String? = null

    override fun open(paths: ModelPaths, sampleRateHz: Int): StreamingAsrSession {
        val key = "${paths.directory.absolutePath}|${paths.modelType}|$sampleRateHz"

        val active = synchronized(lock) {
            val current = recognizer
            if (current != null && recognizerKey == key) {
                current
            } else {
                current?.release()
                createRecognizer(paths, sampleRateHz).also {
                    recognizer = it
                    recognizerKey = key
                }
            }
        }

        return SherpaSession(active, active.createStream())
    }

    override fun release() {
        synchronized(lock) {
            recognizer?.release()
            recognizer = null
            recognizerKey = null
        }
    }

    private fun createRecognizer(paths: ModelPaths, sampleRateHz: Int): OnlineRecognizer {
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = sampleRateHz,
                featureDim = FEATURE_DIM,
                dither = 0.0f,
            ),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = paths.encoder.absolutePath,
                    decoder = paths.decoder.absolutePath,
                    joiner = paths.joiner.absolutePath,
                ),
                tokens = paths.tokens.absolutePath,
                numThreads = NUM_THREADS,
                debug = false,
                provider = "cpu",
                // Must match the encoder's own metadata, which is why the catalogue records
                // it per model: the 2023-06-26 export reports `zipformer2` (chunk-16, 128
                // frames of left context) and the 20M export reports plain `zipformer`.
                // Asking for the wrong one builds the wrong transducer class against those
                // outputs and throws instead of recognising.
                modelType = paths.modelType,
            ),
            // Endpointing is Loqui's job (`SpeechEndpointer`); resetting inside sherpa
            // would split one dictation into several unrelated results.
            enableEndpoint = false,
            decodingMethod = "greedy_search",
        )
        return OnlineRecognizer(config = config)
    }

    private class SherpaSession(
        private val recognizer: OnlineRecognizer,
        private val stream: OnlineStream,
    ) : StreamingAsrSession {

        private var released = false

        override fun accept(samples: FloatArray, sampleRateHz: Int) {
            stream.acceptWaveform(samples = samples, sampleRate = sampleRateHz)
        }

        override fun isReady(): Boolean = recognizer.isReady(stream)

        override fun decode() = recognizer.decode(stream)

        override fun text(): String = recognizer.getResult(stream).text

        override fun finish() = stream.inputFinished()

        override fun release() {
            if (!released) {
                released = true
                stream.release()
            }
        }
    }

    private companion object {
        /** Zipformer streaming models use 80-dimensional fbank features. */
        const val FEATURE_DIM = 80

        /**
         * Intra-op threads for the decoder. The 2023-06-26 encoder is roughly 1.6x the
         * compute of the 20M model it replaced, and a decoder that cannot keep up with the
         * microphone delays every partial by the backlog it builds. Four threads (clamped to
         * the cores a device actually reports) keeps the performance cores busy without
         * asking a two-core `armeabi-v7a` device for pools it cannot run.
         */
        val NUM_THREADS = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    }
}
