package de.binarynoise.captiveportalautologin

import android.net.Network
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

internal object ManualPortalLogin {
    fun submit(network: Network, profile: ManualPortalProfile, userAgent: String) {
        val pageUrl = profile.url.toHttpUrl()
        val cookies = mutableListOf<Cookie>()
        val client = OkHttpClient.Builder().socketFactory(network.socketFactory)
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
            }).build()
        try {
            val form = client.newCall(Request.Builder().url(pageUrl).build()).execute().use { response ->
                check(response.isSuccessful) { "Portal page returned HTTP ${response.code}" }
                ManualLoginForm.parse(response.body.string(), response.request.url, pageUrl, profile.username, profile.password)
            }
            val body = FormBody.Builder().apply { form.fields.forEach { (name, value) -> add(name, value) } }.build()
            // A 307/308 redirect must never forward the password to another origin.
            client.newBuilder().followRedirects(false).followSslRedirects(false).build()
                .newCall(Request.Builder().url(form.action).header("Origin", form.page.newBuilder()
                    .username("").password("").encodedPath("/").query(null).fragment(null).build().toString().removeSuffix("/"))
                    .header("Referer", form.page.toString()).post(body).build()).execute().use { response ->
                check(response.isSuccessful || response.code in 300..399) { "Portal login returned HTTP ${response.code}" }
            }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
