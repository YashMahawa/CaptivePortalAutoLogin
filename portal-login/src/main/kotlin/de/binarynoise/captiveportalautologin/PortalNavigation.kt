package de.binarynoise.captiveportalautologin

import okhttp3.HttpUrl
import org.jsoup.Jsoup

/** Only literal redirects are read; portal JavaScript is never executed by the HTTP client. */
object PortalNavigation {
    const val PROBE = "http://gstatic.com/generate_204"

    fun isIitj(url: HttpUrl): Boolean = url.scheme == "https" && when (url.host) {
        "gateway.iitj.ac.in" -> url.port == 1003
        "netaccess.iitj.ac.in" -> url.port == 443
        else -> false
    }

    fun allowed(url: HttpUrl, configured: HttpUrl): Boolean {
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return false
        if (isIitj(configured) && isIitj(url)) return true
        return url.host == configured.host &&
            ((url.scheme == configured.scheme && url.port == configured.port) ||
                (configured.scheme == "http" && url.scheme == "https"))
    }

    fun redirect(html: String, page: HttpUrl): HttpUrl? {
        val doc = Jsoup.parse(html, page.toString())
        doc.select("meta[http-equiv]").firstOrNull { it.attr("http-equiv").equals("refresh", true) }?.let {
            Regex("(?i)^\\s*\\d+(?:\\.\\d+)?\\s*;\\s*url\\s*=\\s*['\"]?([^'\"]+)['\"]?\\s*$")
                .find(it.attr("content"))?.groupValues?.get(1)?.trim()?.let { target -> return page.resolve(target) }
        }
        val assignment = Regex("(?:window\\.|document\\.)?location(?:\\.href)?\\s*=\\s*(['\"])([^'\"\\r\\n]+)\\1(?=\\s*(?:;|$|\\}))")
        for (script in doc.select("script:not([src])")) {
            assignment.find(script.data())?.groupValues?.get(2)?.let { return page.resolve(it) }
        }
        return null
    }

    fun loginFailure(body: String): String? {
        val text = Jsoup.parse(body).text()
        return when {
            text.contains("Authentication Failed", true) -> "Portal rejected the username or password"
            text.contains("Maximum number of concurrent logins", true) -> "Portal account has reached its concurrent-login limit"
            else -> null
        }
    }
}
