package de.binarynoise.captiveportalautologin

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PortalNavigationTest {
    private val gateway = "https://gateway.iitj.ac.in:1003/fgtauth?fresh".toHttpUrl()
    private val netaccess = "https://netaccess.iitj.ac.in/24online/servlet/E24onlineHTTPClient".toHttpUrl()
    @Test fun `script from uploaded helper discovers fresh Forti token`() {
        assertEquals(gateway, PortalNavigation.redirect("<script>window.location=\"$gateway\";</script>", PortalNavigation.PROBE.toHttpUrl()))
    }
    @Test fun `both quote styles and location href are accepted`() {
        assertEquals(gateway, PortalNavigation.redirect("<script>location.href='$gateway'</script>", netaccess))
    }
    @Test fun `meta refresh supports relative paths`() {
        assertEquals("https://netaccess.iitj.ac.in/login".toHttpUrl(), PortalNavigation.redirect("<meta http-equiv='Refresh' content='0; URL=/login'>", netaccess))
    }
    @Test fun `untrusted script source is not a redirect`() {
        assertNull(PortalNavigation.redirect("<script src='https://evil.test/a.js'></script><p>window.location='https://evil.test'</p>", netaccess))
    }
    @Test fun `campus discovery allows only exact HTTPS endpoints`() {
        assertTrue(PortalNavigation.allowed(gateway, netaccess))
        for (url in listOf("http://gateway.iitj.ac.in:1003/", "https://gateway.iitj.ac.in/", "https://gateway.iitj.ac.in.evil.test:1003/", "https://other.iitj.ac.in/", "https://netaccess.iitj.ac.in:444/", "https://user:pass@netaccess.iitj.ac.in/")) {
            assertFalse(PortalNavigation.allowed(url.toHttpUrl(), netaccess), url)
        }
    }
    @Test fun `generic profiles do not acquire campus trust`() {
        assertFalse(PortalNavigation.allowed(gateway, "https://portal.test/login".toHttpUrl()))
    }
    @Test fun `account failures reported by old script are recognized`() {
        assertNotNull(PortalNavigation.loginFailure("<h1>Authentication Failed</h1>"))
        assertNotNull(PortalNavigation.loginFailure("Maximum number of concurrent logins"))
        assertNull(PortalNavigation.loginFailure("Successful login"))
    }
}
