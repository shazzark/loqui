package com.loqui.android

import android.app.Application
import android.content.pm.ApplicationInfo
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

/**
 * Loqui runs entirely on-device by default. No backend, no proxy, no bundled
 * provider credentials — see the loqui-android skill, 01-project-brief.md.
 */
@HiltAndroidApp
class LoquiApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        if (isDebuggable()) {
            Timber.plant(Timber.DebugTree())
        }
    }

    private fun isDebuggable(): Boolean =
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}
