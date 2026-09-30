package de.binarynoise.logger

import android.annotation.SuppressLint
import android.app.Application

private var initializedApplication: Application? = null

fun initializeAndroidLogger(application: Application) {
    initializedApplication = application
}

/** 
 * The current application context or `null` if the application context is not available
 * (in XposedModules during process startup when the Application hasn't been initialized yet).
 */
@get:SuppressLint("PrivateApi")
internal val applicationContext: Application?
    get() = initializedApplication ?: runCatching {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Application?
    }.getOrNull()
