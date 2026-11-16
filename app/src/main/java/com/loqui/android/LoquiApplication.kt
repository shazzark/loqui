package com.loqui.android

import android.app.Application
import android.content.pm.ApplicationInfo
import com.loqui.android.asr.RecogniserWarmUp
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import timber.log.Timber

/**
 * Loqui runs entirely on-device by default. No backend, no proxy, no bundled
 * provider credentials — see the loqui-android skill, 01-project-brief.md.
 */
@HiltAndroidApp
class LoquiApplication : Application() {

    @Inject
    lateinit var recogniserWarmUp: RecogniserWarmUp

    override fun onCreate() {
        super.onCreate()
        if (isDebuggable()) {
            Timber.plant(Timber.DebugTree())
        }
        // The recogniser takes seconds to open, and it used to open between the tap and the
        // first recorded frame. Loading it here means the microphone is open immediately and
        // the first dictation of the process is not missing its first words.
        recogniserWarmUp.start()
    }

    private fun isDebuggable(): Boolean =
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}
