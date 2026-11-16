package com.loqui.android.di

import javax.inject.Qualifier

@Retention(AnnotationRetention.RUNTIME)
@Qualifier
annotation class IoDispatcher

@Retention(AnnotationRetention.RUNTIME)
@Qualifier
annotation class DefaultDispatcher

/** Application-lifetime scope for work that must outlive a screen or a service. */
@Retention(AnnotationRetention.RUNTIME)
@Qualifier
annotation class ApplicationScope

/** The on-device recogniser, as opposed to the cloud provider built per session. */
@Retention(AnnotationRetention.RUNTIME)
@Qualifier
annotation class OnDeviceAsrProvider
