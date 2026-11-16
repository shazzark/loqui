package com.loqui.android.models

import java.io.File

/**
 * The installed recogniser, in the layout sherpa-onnx expects. Constructing this class
 * does not touch the disk; [File.isFile] checks happen in `SpeechModelRepository.paths`.
 */
data class ModelPaths(val directory: File) {
    val tokens: File = File(directory, SpeechModels.TOKENS_FILE)
    val encoder: File = File(directory, SpeechModels.ENCODER_FILE)
    val decoder: File = File(directory, SpeechModels.DECODER_FILE)
    val joiner: File = File(directory, SpeechModels.JOINER_FILE)

    val all: List<File> = listOf(tokens, encoder, decoder, joiner)
}
