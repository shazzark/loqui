package com.loqui.android.models

import android.app.ActivityManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * What the device can afford to run.
 *
 * The only number that matters for model choice on today's phones is physical RAM, and
 * [AndroidDeviceCapabilities] reads it from `ActivityManager.MemoryInfo.totalMem` — the
 * total the kernel sees, not the heap limit, which on the same phone varies with the
 * `largeHeap` flag and says nothing about how much compute sits behind the microphone.
 *
 * Behind an interface so the routing rule can be tested on the JVM, where no
 * `ActivityManager` exists.
 */
interface DeviceCapabilities {

    /** Physical RAM in bytes; `0` when the platform will not say. */
    fun totalRamBytes(): Long
}

@Singleton
class AndroidDeviceCapabilities @Inject constructor(
    @ApplicationContext private val context: Context,
) : DeviceCapabilities {

    override fun totalRamBytes(): Long {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return UNKNOWN_RAM_BYTES

        val info = ActivityManager.MemoryInfo()
        return try {
            manager.getMemoryInfo(info)
            info.totalMem
        } catch (exception: RuntimeException) {
            // Some vendor ROMs throw here; an unreadable total is treated as a small
            // device, which picks the model that runs everywhere.
            Timber.w(exception, "Could not read total memory; assuming a small device")
            UNKNOWN_RAM_BYTES
        }
    }

    private companion object {
        const val UNKNOWN_RAM_BYTES = 0L
    }
}
