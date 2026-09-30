package de.binarynoise.captiveportalautologin.preferences

import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import de.binarynoise.captiveportalautologin.LoginStatus
import de.binarynoise.captiveportalautologin.util.mainHandler

fun PreferenceGroup.addLoginStatusPreference(owner: LifecycleOwner) {
    val preferences = PreferenceManager.getDefaultSharedPreferences(context)
    val result = Preference(context).apply { title = "Last login attempt"; isSelectable = false }
    addPreference(result)
    fun refresh() { result.summary = preferences.getString(LoginStatus.KEY, "No login attempt yet.") }
    val listener = OnSharedPreferenceChangeListener { _, key ->
        if (key == LoginStatus.KEY) mainHandler.post { refresh() }
    }
    owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            preferences.registerOnSharedPreferenceChangeListener(listener)
            refresh()
        }
        override fun onStop(owner: LifecycleOwner) { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    })
    refresh()
}
