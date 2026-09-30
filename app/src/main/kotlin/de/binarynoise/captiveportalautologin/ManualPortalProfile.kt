package de.binarynoise.captiveportalautologin

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import de.binarynoise.captiveportalautologin.util.applicationContext
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ManualPortalProfile(val url: String, val username: String, val password: String, val automaticSsid: String?)

object ManualPortalProfiles {
    private const val ALIAS = "captive.portal.manual.profile"
    private val prefs get() = applicationContext.getSharedPreferences("manual_portal", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
                generateKey()
            }
        }
        return store.getKey(ALIAS, null) as SecretKey
    }
    fun save(profile: ManualPortalProfile) {
        val json = JSONObject().put("url", profile.url).put("username", profile.username)
            .put("password", profile.password).put("ssid", profile.automaticSsid ?: JSONObject.NULL)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(prefs.edit().putString("profile", payload).commit()) { "Could not save login details" }
    }
    fun load(): ManualPortalProfile? {
        val payload = prefs.getString("profile", null) ?: return null
        return runCatching {
            val parts = payload.split(":", limit = 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            }
            val json = JSONObject(String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8))
            ManualPortalProfile(json.getString("url"), json.getString("username"), json.getString("password"),
                if (json.isNull("ssid")) null else json.getString("ssid"))
        }.getOrNull()
    }
    fun clear() { prefs.edit().remove("profile").apply() }
}
