package de.binarynoise.captiveportalautologin

import android.net.Network
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

internal object WifiInternetCheck {
    fun isOnline(network: Network, userAgent: String): Boolean {
        val client = OkHttpClient.Builder().socketFactory(network.socketFactory)
            .dns { network.getAllByName(it).toList() }
            .followRedirects(false).followSslRedirects(false)
            .callTimeout(5, TimeUnit.SECONDS).build()
        return try {
            client.newCall(Request.Builder().url("https://www.google.com/generate_204")
                .header("User-Agent", userAgent).build()).execute().use { it.code == 204 }
        } catch (_: Exception) { false }
        finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }
}
