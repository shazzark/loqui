package com.loqui.android.models

import java.io.File

/**
 * The installed recogniser, in the layout sherpa-onnx expects. Constructing this class
 * does not touch the disk; [File.isFile] checks happen in `SpeechModelRepository.paths`.
 *
 * [modelType] travels with the files because the two catalogue entries are different
 * graphs: the 2023-06-26 export is a `zipformer2` and the 20M export is the legacy
 * `zipformer`. sherpa-onnx has to be told which one it is opening, and the encoder's own
 * metadata is the only authority, so the catalogue records it per model.
 */
data class ModelPaths(
    val directory: File,
    val modelType: String,
) {
    val tokens: File = File(directory, SpeechModels.TOKENS_FILE)
    val encoder: File = File(directory, SpeechModels.ENCODER_FILE)
    val decoder: File = File(directory, SpeechModels.DECODER_FILE)
    val joiner: File = File(directory, SpeechModels.JOINER_FILE)

    val all: List<File> = listOf(tokens, encoder, decoder, joiner)
}
