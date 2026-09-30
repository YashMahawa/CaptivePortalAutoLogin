package de.binarynoise.captiveportalautologin

import androidx.preference.PreferenceManager
import de.binarynoise.captiveportalautologin.util.applicationContext

object LoginStatus {
    const val KEY = "last_login_result"
    fun record(message: String) {
        PreferenceManager.getDefaultSharedPreferences(applicationContext).edit()
            .putString(KEY, message).apply()
    }
    fun retryScheduled(delayMillis: Long) {
        val previous = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            .getString(KEY, "Portal detected.").orEmpty().substringBefore("\nNext automatic retry:")
        record("$previous\nNext automatic retry: ${delayMillis / 1000} seconds while the phone is awake.")
    }
}
