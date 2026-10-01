package de.binarynoise.captiveportalautologin

import android.net.Network
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

internal object ManualPortalLogin {
    fun submit(network: Network, profile: ManualPortalProfile, userAgent: String) {
        val pageUrl = profile.url.toHttpUrl()
        val cookies = mutableListOf<Cookie>()
        val builder = OkHttpClient.Builder().socketFactory(network.socketFactory)
            .dns { host -> network.getAllByName(host).toList() }
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder()
                .header("User-Agent", userAgent).build()) }
            .cookieJar(object : CookieJar {
                override fun loadForRequest(url: HttpUrl) = cookies.filter { it.matches(url) }
                override fun saveFromResponse(url: HttpUrl, received: List<Cookie>) {
                    received.forEach { cookie ->
                        cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                        cookies.add(cookie)
                    }
                }
            })
        if (profile.allowIitjCertificate && PortalNavigation.isIitj(pageUrl)) {
            IitjCertificateException.apply(builder)
        }
        val client = builder.build()
        try {
            ManualPortalFlow.submit(client, pageUrl, profile.username, profile.password)
        } catch (e: javax.net.ssl.SSLException) {
            if (PortalNavigation.isIitj(pageUrl) && !profile.allowIitjCertificate) {
                throw IllegalStateException("IITJ TLS connection failed. If this is the campus certificate, enable the IITJ certificate exception in Manual login and save. ${e.javaClass.simpleName}", e)
            }
            throw e
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
