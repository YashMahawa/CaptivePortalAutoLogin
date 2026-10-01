package de.binarynoise.captiveportalautologin

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup

data class ManualLoginForm(val action: HttpUrl, val fields: List<Pair<String, String>>, val page: HttpUrl) {
    companion object {
        fun parse(html: String, page: HttpUrl, configured: HttpUrl, username: String, password: String): ManualLoginForm {
            fun sameOrigin(url: HttpUrl) = url.host == configured.host &&
                ((url.scheme == configured.scheme && url.port == configured.port) ||
                    (configured.scheme == "http" && url.scheme == "https"))
            require(sameOrigin(page)) { "Portal redirected to another address. Save the final login page URL." }
            val forms = Jsoup.parse(html, page.toString()).select("form")
                .filter { it.select("input[type=password][name]:not([disabled])").isNotEmpty() }
            require(forms.size == 1) { "No unique HTML login form found. Use Capture Captive Portal Login for this portal." }
            val form = forms.single()
            require(form.attr("method").equals("post", ignoreCase = true)) { "Only POST login forms are supported." }
            val action = if (form.attr("action").isBlank()) page else form.absUrl("action").toHttpUrlOrNull()
                ?: error("Invalid login form URL")
            require(sameOrigin(action)) { "Login form sends credentials to another address. Use the portal browser." }
            val passwordInput = form.selectFirst("input[type=password][name]:not([disabled])")!!
            val userInput = form.selectFirst("input[autocomplete=username][name]:not([disabled]), input[type=email][name]:not([disabled])")
                ?: form.selectFirst("input[type=text][name]:not([disabled]), input[name]:not([type]):not([disabled])")
                ?: error("Could not identify the username field. Use the portal browser.")
            require(userInput.attr("name").isNotBlank() && passwordInput.attr("name").isNotBlank())
            val fields = form.select("input[type=hidden][name]:not([disabled]), input[type=checkbox][checked][name]:not([disabled]), input[type=radio][checked][name]:not([disabled])")
                .map { it.attr("name") to it.attr("value") }.toMutableList()
            fields.add(userInput.attr("name") to username)
            fields.add(passwordInput.attr("name") to password)
            form.selectFirst("input[type=submit][name], button[type=submit][name]")?.let { fields.add(it.attr("name") to it.attr("value")) }
            return ManualLoginForm(action, fields, page)
        }
    }
}
