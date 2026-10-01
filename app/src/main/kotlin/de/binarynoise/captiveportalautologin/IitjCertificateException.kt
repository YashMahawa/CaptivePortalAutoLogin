package de.binarynoise.captiveportalautologin

import okhttp3.OkHttpClient
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** Explicit profile opt-in matching the supplied campus script, restricted to two portal endpoints. */
internal object IitjCertificateException {
    fun apply(builder: OkHttpClient.Builder) {
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        builder.sslSocketFactory(ssl.socketFactory, trust)
            .hostnameVerifier { host, _ -> host == "gateway.iitj.ac.in" || host == "netaccess.iitj.ac.in" }
            .addInterceptor { chain ->
                val url = chain.request().url
                require(PortalNavigation.isIitj(url) || url.toString() == PortalNavigation.PROBE) {
                    "Certificate exception is restricted to the IITJ portal"
                }
                chain.proceed(chain.request())
            }
    }
}
