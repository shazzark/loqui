package com.loqui.android.models

import com.loqui.android.settings.ModelQuality

/**
 * Turns the user's quality choice plus the device's RAM into the model to install and run.
 *
 * Auto is the shipping default. It keeps the accurate 2023-06-26 export on phones that can
 * decode it in real time and drops to the 20M export below [ACCURATE_MIN_TOTAL_RAM_BYTES],
 * where the larger decoder cannot keep up with the microphone and the transcript lags
 * behind the speaker instead of being wrong — the failure mode users actually notice.
 *
 * Pure and synchronous on purpose: the decision is one comparison, so it is unit-tested
 * on the JVM and can be recomputed whenever the setting changes.
 */
object ModelRouting {

    /**
     * Total RAM at which the accurate model is chosen.
     *
     * 6 GB: a 4 GB phone has roughly 2.2-2.6 GB available to apps after the vendor
     * partition, which is where the 18-layer encoder's int8 graphs and working set stop
     * fitting comfortably alongside Android itself; 6 GB and 8 GB devices have room and
     * the cores to run it. The number matters less than that it is one place.
     */
    const val ACCURATE_MIN_TOTAL_RAM_BYTES = 6_000_000_000L

    /** The model [quality] asks for on a device with [totalRamBytes] of RAM. */
    fun resolve(quality: ModelQuality, totalRamBytes: Long): SpeechModel = when (quality) {
        ModelQuality.FAST -> SpeechModels.onDeviceFast
        ModelQuality.ACCURATE -> SpeechModels.onDeviceAccurate
        ModelQuality.AUTO -> if (isHighMemory(totalRamBytes)) {
            SpeechModels.onDeviceAccurate
        } else {
            SpeechModels.onDeviceFast
        }
    }

    /**
     * True when the device has the RAM the accurate model needs. An unreadable total (`0`)
     * is not high memory, so a device that will not report its RAM gets the safe model.
     */
    fun isHighMemory(totalRamBytes: Long): Boolean = totalRamBytes >= ACCURATE_MIN_TOTAL_RAM_BYTES
}
