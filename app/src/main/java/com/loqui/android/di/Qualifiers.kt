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
