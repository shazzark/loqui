package com.loqui.android.models

import android.content.Context
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/** Supplies the free space a download would land in. Injectable so tests never touch StatFs. */
interface ModelStorageProbe {
    fun freeBytes(): Long
}

/** Free space on the volume that holds the app's private files, where models will live. */
@Singleton
class DeviceModelStorageProbe @Inject constructor(
    @ApplicationContext private val context: Context,
) : ModelStorageProbe {

    override fun freeBytes(): Long = try {
        StatFs(context.filesDir.absolutePath).availableBytes
    } catch (throwable: Throwable) {
        // A storage read must never take the settings screen down; report "unknown".
        Timber.w(throwable, "Could not read free space")
        0L
    }
}
