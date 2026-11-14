package com.loqui.android.insertion

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes the transcript to the clipboard. This is the guaranteed insertion path:
 * it works with no accessibility permission and no editable field in sight.
 */
@Singleton
class ClipboardTextInserter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun copy(text: String) {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return
        manager.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
    }

    private companion object {
        const val CLIP_LABEL = "Loqui transcript"
    }
}
