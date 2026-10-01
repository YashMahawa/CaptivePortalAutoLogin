package de.binarynoise.captiveportalautologin

import mockwebserver3.MockWebServer
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class ManualPortalFlowTest {
    @Test fun `IITJ discovery refreshes magic and 4Tredir and retains session cookies`() {
        val attempt = AtomicInteger()
        val posts = AtomicInteger()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
          override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.url.encodedPath
            val builder = MockResponse.Builder()
            val response: String
            var status = 200
            when (path) {
                "/generate_204" -> {
                    val n = attempt.incrementAndGet()
                    response = "<script>window.location=\"https://gateway.iitj.ac.in:1003/fgtauth?token$n\"</script>"
                }
                "/fgtauth" -> {
                    val n = attempt.get()
                    assertEquals("token$n", request.url.encodedQuery)
                    builder.addHeader("Set-Cookie", "portal=session$n; Path=/; HttpOnly")
                    // Deliberately different action: the supplied script uses 4Tredir.
                    response = """<form method='post' action='/wrong'><input type='hidden' name='magic' value='fresh$n'><input type='hidden' name='4Tredir' value='https://gateway.iitj.ac.in:1003/submit'><input name='username'><input type='password' name='password'></form>"""
                }
                "/submit" -> {
                    val n = attempt.get()
                    val body = request.body?.utf8() ?: ""
                    val valid = request.method == "POST" && body.contains("magic=fresh$n") &&
                        body.contains("4Tredir=https%3A%2F%2Fgateway.iitj.ac.in%3A1003%2Fsubmit") &&
                        body.contains("username=student%26%2B") && body.contains("password=p%26%2B") &&
                        request.headers["Cookie"]?.contains("portal=session$n") == true &&
                        request.headers["Origin"] == "https://gateway.iitj.ac.in:1003" &&
                        request.headers["Referer"] == "https://gateway.iitj.ac.in:1003/fgtauth?token$n"
                    status = if (valid) 200 else 403
                    if (valid) posts.incrementAndGet()
                    response = if (valid) "Logged in" else "Invalid fixture request"
                }
                else -> { status = 404; response = "No handler" }
            }
            return builder.code(status).body(response).build()
          }
        }
        server.start()
        val cookies = mutableListOf<Cookie>()
        val client = OkHttpClient.Builder().cookieJar(object : CookieJar {
            override fun loadForRequest(url: HttpUrl) = cookies.filter { it.matches(url) }
            override fun saveFromResponse(url: HttpUrl, received: List<Cookie>) { cookies.clear(); cookies.addAll(received) }
        }).addInterceptor { chain ->
            // Synthetic fixture only: route the campus/probe requests to our local HTTP server.
            val original = chain.request()
            val mapped = "http://127.0.0.1:${server.port}/".toHttpUrl().newBuilder()
                .encodedPath(original.url.encodedPath).encodedQuery(original.url.encodedQuery).build()
            chain.proceed(original.newBuilder().url(mapped).build()).newBuilder().request(original).build()
        }.build()
        try {
            repeat(2) { ManualPortalFlow.submit(client, "https://netaccess.iitj.ac.in/24online/servlet/E24onlineHTTPClient".toHttpUrl(), "student&+", "p&+") }
            assertEquals(2, attempt.get())
            assertEquals(2, posts.get())
        } finally { server.close(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }

    @Test fun `certificate exception never applies to an unrelated endpoint`() {
        val builder = OkHttpClient.Builder()
        IitjCertificateException.apply(builder)
        val client = builder.build()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                client.newCall(okhttp3.Request.Builder().url("https://evil.test/").build()).execute()
            }
        } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }
}
