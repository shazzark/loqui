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
}

/**
 * sherpa-onnx streaming Zipformer over the int8 transducer files.
 *
 * The recogniser is expensive to build (it loads ~43 MiB of ONNX graphs), so it is kept
 * for the app's lifetime and rebuilt only if the model paths or sample rate change. Each
 * dictation still gets its own [OnlineStream]; recogniser state never leaks between
 * utterances.
 */
@Singleton
class SherpaOnnxStreamingEngine @Inject constructor() : StreamingAsrEngine {

    private val lock = Any()
    private var recognizer: OnlineRecognizer? = null
    private var recognizerKey: String? = null

    override fun open(paths: ModelPaths, sampleRateHz: Int): StreamingAsrSession {
        val key = "${paths.directory.absolutePath}|$sampleRateHz"

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
                numThreads = 2,
                debug = false,
                provider = "cpu",
                modelType = "zipformer",
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
    }
}
