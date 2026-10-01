package de.binarynoise.captiveportalautologin

import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

/** Cookies and Wi-Fi routing are supplied by the caller. Every attempt fetches a fresh token. */
internal object ManualPortalFlow {
    fun submit(client: OkHttpClient, configured: HttpUrl, username: String, password: String) {
        val direct = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        var current = configured
        if (PortalNavigation.isIitj(configured)) {
            try {
                direct.newCall(Request.Builder().url(PortalNavigation.PROBE).build()).execute().use { response ->
                    if (response.code == 204) return
                    val target = response.header("Location")?.let { response.request.url.resolve(it) }
                        ?: PortalNavigation.redirect(response.body.string(), response.request.url)
                    if (target != null && PortalNavigation.allowed(target, configured)) current = target
                }
            } catch (_: java.io.IOException) {
                // Some campus networks block the probe host; the saved endpoint still works.
            }
        }
        var form: ManualLoginForm? = null
        val visited = mutableSetOf<HttpUrl>()
        for (step in 0 until 8) {
            check(visited.add(current)) { "Portal redirect loop. Open Capture to inspect the login page." }
            require(PortalNavigation.allowed(current, configured)) { "Portal redirected to an unapproved host. Save the final login URL." }
            direct.newCall(Request.Builder().url(current).build()).execute().use { response ->
                val html = response.body.string()
                val target = if (response.code in 300..399) response.header("Location")?.let { current.resolve(it) }
                    else PortalNavigation.redirect(html, current)
                if (target != null) {
                    require(PortalNavigation.allowed(target, configured)) { "Portal redirected to an unapproved host. Save the final login URL." }
                    current = target
                } else {
                    check(response.isSuccessful) { "Portal page returned HTTP ${response.code} at ${current.host}${current.encodedPath}" }
                    // Credentials go only to the explicitly allowed origin of the final form.
                    form = ManualLoginForm.parse(html, current, current, username, password)
                    // The supplied IITJ script submits to 4Tredir with magic, rather than a cached action.
                    if (PortalNavigation.isIitj(current)) {
                        val doc = Jsoup.parse(html, current.toString())
                        val magic = doc.selectFirst("input[name=magic]")?.attr("value")
                        val redir = doc.selectFirst("input[name=4Tredir]")?.attr("value")
                        if (!magic.isNullOrBlank() && !redir.isNullOrBlank()) {
                            val action = current.resolve(redir) ?: error("Invalid IITJ 4Tredir URL")
                            // FortiGate also uses 4Tredir as a return URL. Keep the real form action
                            // when it names an external site; never post credentials there.
                            if (PortalNavigation.allowed(action, configured)) form = form!!.copy(action = action)
                        }
                    }
                }
            }
            if (form != null) break
        }
        val login = form ?: error("Too many portal redirects. Open Capture to inspect the login page.")
        val body = FormBody.Builder().apply { login.fields.forEach { (name, value) -> add(name, value) } }.build()
        val origin = login.page.newBuilder().username("").password("").encodedPath("/").query(null).fragment(null)
            .build().toString().removeSuffix("/")
        direct.newCall(Request.Builder().url(login.action).header("Origin", origin)
            .header("Referer", login.page.toString()).post(body).build()).execute().use { response ->
            check(response.isSuccessful || response.code in 300..399) { "Portal login returned HTTP ${response.code}" }
            PortalNavigation.loginFailure(response.body.string())?.let { error(it) }
        }
    }
}
