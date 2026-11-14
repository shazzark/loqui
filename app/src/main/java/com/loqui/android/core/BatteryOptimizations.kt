package com.loqui.android.core

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import timber.log.Timber

/**
 * Doze and OEM battery managers kill background services that the user expects to keep
 * working, so Loqui asks for the standard exemption instead of waiting for the bug
 * report. The exemption is optional: everything still works with the app in front.
 */
object BatteryOptimizations {

    /** True when Android will not throttle or kill Loqui's background work. */
    fun isExempt(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Opens the system dialog that asks for the exemption.
     *
     * Not every OEM exposes `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, so a refused
     * intent falls back to the battery-optimisation list rather than doing nothing.
     *
     * @return true when the direct request dialog was opened.
     */
    fun requestExemption(context: Context): Boolean {
        val request = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            context.startActivity(request)
            true
        } catch (missing: ActivityNotFoundException) {
            Timber.w(missing, "No exemption dialog on this device; opening the battery list")
            openSettings(context)
            false
        }
    }

    /** The list of optimised apps, for the "open settings" escape hatch. */
    fun openSettings(context: Context) {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { Timber.w(it, "Could not open the battery optimisation settings") }
    }
}
