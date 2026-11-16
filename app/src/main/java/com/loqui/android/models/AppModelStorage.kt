package com.loqui.android.models

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where installed models live. An interface so the download repository's filesystem
 * behaviour is testable on the JVM with a temporary directory.
 */
interface ModelStorage {
    val modelsDirectory: File
}

/** App-private storage: no permission, removed when the user clears app data. */
@Singleton
class AppModelStorage @Inject constructor(
    @ApplicationContext private val context: Context,
) : ModelStorage {

    override val modelsDirectory: File
        get() = File(context.filesDir, "models")
}
