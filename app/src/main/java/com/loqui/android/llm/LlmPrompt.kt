package com.loqui.android.llm

/**
 * Prompt configuration for cloud clean-up.
 *
 * Kept in one reviewable place so swapping the transport (OpenAI, DeepSeek, a future
 * OpenAI-compatible endpoint) never changes what the model is asked to do. The wording
 * is deliberately narrow: the model is a formatting step, not an assistant — it must
 * not answer the transcript, summarise it, or invent words.
 */
object LlmPrompt {

    const val INSTRUCTION: String =
        "You are a dictation clean-up step. Add correct punctuation and capitalisation " +
            "to the transcript. Do not add, remove or reorder words, do not translate " +
            "it, and do not answer it. Reply with the corrected text only."

    /** The full prompt sent to the provider; the transcript is appended verbatim. */
    fun build(transcript: String): String = "$INSTRUCTION\n\n$transcript"
}
