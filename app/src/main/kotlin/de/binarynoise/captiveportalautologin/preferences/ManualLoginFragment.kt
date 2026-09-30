package de.binarynoise.captiveportalautologin.preferences

import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import android.widget.EditText
import android.app.AlertDialog
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreference
import de.binarynoise.captiveportalautologin.ConnectivityChangeListenerService
import de.binarynoise.captiveportalautologin.ManualPortalProfile
import de.binarynoise.captiveportalautologin.ManualPortalProfiles
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.concurrent.read

class ManualLoginFragment : AutoCleanupPreferenceFragment() {
    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (preference !is EditTextPreference) {
            super.onDisplayPreferenceDialog(preference)
            return
        }
        // The original app uses the platform settings theme, not AppCompat.
        val input = EditText(requireContext()).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or when (preference.key) {
                "manual_password" -> InputType.TYPE_TEXT_VARIATION_PASSWORD
                "manual_portal_url" -> InputType.TYPE_TEXT_VARIATION_URI
                else -> InputType.TYPE_TEXT_VARIATION_NORMAL
            }
            setText(preference.text)
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(preference.title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val value = input.text.toString()
                if (preference.callChangeListener(value)) preference.text = value
            }.show()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val ctx = preferenceManager.context
        val profile = ManualPortalProfiles.load()
        preferenceScreen = preferenceManager.createPreferenceScreen(ctx)
        fun field(label: String, value: String?, password: Boolean = false): EditTextPreference {
            val p = EditTextPreference(ctx).apply {
                key = "manual_${label.lowercase().replace(' ', '_')}"
                title = label; isPersistent = false; text = value ?: ""
                summary = if (password && !value.isNullOrEmpty()) "Saved securely" else value ?: "Not set"
                setOnBindEditTextListener { it.inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT }
                setOnPreferenceChangeListener { pref, new ->
                    pref.summary = if (password) "Entered · tap Save details" else new.toString()
                    true
                }
            }
            preferenceScreen.addPreference(p)
            return p
        }
        val url = field("Portal URL", profile?.url)
        val username = field("Username", profile?.username)
        val password = field("Password", profile?.password, true)
        val automatic = SwitchPreference(ctx).apply {
            title = "Use saved details automatically on this Wi-Fi"
            summary = "Only the Wi-Fi you are connected to when saving. Manual login works without Android detecting a portal."
            isPersistent = false
            isChecked = profile?.automaticSsid != null
        }
        preferenceScreen.addPreference(automatic)
        fun save(): Boolean = try {
            val parsed = url.text?.trim()?.toHttpUrlOrNull() ?: error("Enter a valid http(s) portal URL")
            require(!username.text.isNullOrEmpty() && !password.text.isNullOrEmpty()) { "Enter username and password" }
            val ssid = if (automatic.isChecked) ConnectivityChangeListenerService.networkStateLock.read {
                ConnectivityChangeListenerService.networkState?.ssid?.takeUnless { it == ConnectivityChangeListenerService.SsidCompat.UNKNOWN_SSID }
            } ?: error("Connect to your Wi-Fi with location permission before enabling automatic login") else null
            ManualPortalProfiles.save(ManualPortalProfile(parsed.toString(), username.text!!, password.text!!, ssid))
            password.summary = "Saved securely"
            true
        } catch (e: Exception) {
            Toast.makeText(ctx, e.message ?: "Could not save details", Toast.LENGTH_LONG).show()
            false
        }
        preferenceScreen.addPreference(Preference(ctx).apply {
            title = "Save details"
            summary = "Stored privately, encrypted with Android Keystore. Uses fresh form fields and cookies for each attempt."
            setOnPreferenceClickListener { if (save()) Toast.makeText(ctx, "Login details saved", Toast.LENGTH_SHORT).show(); true }
        })
        preferenceScreen.addPreference(Preference(ctx).apply {
            title = "Log in now"
            summary = "Save and submit the standard username/password form, then ask Android to verify internet access."
            setOnPreferenceClickListener { if (save()) ConnectivityChangeListenerService.retry(); true }
        })
        preferenceScreen.addPreference(Preference(ctx).apply {
            title = "Forget saved details"
            setOnPreferenceClickListener { ManualPortalProfiles.clear(); password.text = ""; password.summary = "Not set"; automatic.isChecked = false; true }
        })
    }
}
