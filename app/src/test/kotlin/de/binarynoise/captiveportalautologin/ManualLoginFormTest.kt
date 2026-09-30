package de.binarynoise.captiveportalautologin

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ManualLoginFormTest {
    private val url = "https://portal.test/login".toHttpUrl()
    private val html = """<form method="post" action="/auth"><input type="hidden" name="csrf" value="fresh"><input name="student"><input type="password" name="secret"><button type="submit" name="login" value="yes">Sign in</button></form>"""

    @Test fun `manual form includes fresh hidden fields and chosen credentials`() {
        val form = ManualLoginForm.parse(html, url, url, "yash&+", "p&+")
        assertEquals("https://portal.test/auth", form.action.toString())
        assertTrue(form.fields.containsAll(listOf("csrf" to "fresh", "student" to "yash&+", "secret" to "p&+", "login" to "yes")))
    }
    @Test fun `credentials are never sent to a different form origin`() {
        assertThrows(IllegalArgumentException::class.java) {
            ManualLoginForm.parse(html.replace("/auth", "https://other.test/auth"), url, url, "user", "password")
        }
    }
    @Test fun `redirect to another origin requires the user to save its exact URL`() {
        assertThrows(IllegalArgumentException::class.java) {
            ManualLoginForm.parse(html, "https://other.test/login".toHttpUrl(), url, "user", "password")
        }
    }
    @Test fun `GET form does not expose the password in a URL`() {
        assertThrows(IllegalArgumentException::class.java) { ManualLoginForm.parse(html.replace("post", "get"), url, url, "user", "password") }
    }
    @Test fun `ambiguous forms are not submitted automatically`() {
        assertThrows(IllegalArgumentException::class.java) { ManualLoginForm.parse(html + html, url, url, "user", "password") }
    }
}
