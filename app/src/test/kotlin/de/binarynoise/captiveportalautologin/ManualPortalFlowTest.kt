package de.binarynoise.captiveportalautologin

import com.sun.net.httpserver.HttpServer
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class ManualPortalFlowTest {
    @Test fun `IITJ discovery refreshes magic and 4Tredir and retains session cookies`() {
        val attempt = AtomicInteger()
        val posts = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val response: String
            var status = 200
            when (path) {
                "/generate_204" -> {
                    val n = attempt.incrementAndGet()
                    response = "<script>window.location=\"https://gateway.iitj.ac.in:1003/fgtauth?token$n\"</script>"
                }
                "/fgtauth" -> {
                    val n = attempt.get()
                    assertEquals("token$n", exchange.requestURI.rawQuery)
                    exchange.responseHeaders.add("Set-Cookie", "portal=session$n; Path=/; HttpOnly")
                    // Deliberately different action: the supplied script uses 4Tredir.
                    response = """<form method='post' action='/wrong'><input type='hidden' name='magic' value='fresh$n'><input type='hidden' name='4Tredir' value='https://gateway.iitj.ac.in:1003/submit'><input name='username'><input type='password' name='password'></form>"""
                }
                "/submit" -> {
                    val n = attempt.get()
                    val body = exchange.requestBody.bufferedReader().readText()
                    val valid = exchange.requestMethod == "POST" && body.contains("magic=fresh$n") &&
                        body.contains("4Tredir=https%3A%2F%2Fgateway.iitj.ac.in%3A1003%2Fsubmit") &&
                        body.contains("username=student%26%2B") && body.contains("password=p%26%2B") &&
                        exchange.requestHeaders.getFirst("Cookie")?.contains("portal=session$n") == true &&
                        exchange.requestHeaders.getFirst("Origin") == "https://gateway.iitj.ac.in:1003" &&
                        exchange.requestHeaders.getFirst("Referer") == "https://gateway.iitj.ac.in:1003/fgtauth?token$n"
                    status = if (valid) 200 else 403
                    if (valid) posts.incrementAndGet()
                    response = if (valid) "Logged in" else "Invalid fixture request"
                }
                else -> { status = 404; response = "No handler" }
            }
            val bytes = response.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val cookies = mutableListOf<Cookie>()
        val client = OkHttpClient.Builder().cookieJar(object : CookieJar {
            override fun loadForRequest(url: HttpUrl) = cookies.filter { it.matches(url) }
            override fun saveFromResponse(url: HttpUrl, received: List<Cookie>) { cookies.clear(); cookies.addAll(received) }
        }).addInterceptor { chain ->
            // Synthetic fixture only: route the campus/probe requests to our local HTTP server.
            val original = chain.request()
            val mapped = "http://127.0.0.1:${server.address.port}/".toHttpUrl().newBuilder()
                .encodedPath(original.url.encodedPath).encodedQuery(original.url.encodedQuery).build()
            chain.proceed(original.newBuilder().url(mapped).build()).newBuilder().request(original).build()
        }.build()
        try {
            repeat(2) { ManualPortalFlow.submit(client, "https://netaccess.iitj.ac.in/24online/servlet/E24onlineHTTPClient".toHttpUrl(), "student&+", "p&+") }
            assertEquals(2, attempt.get())
            assertEquals(2, posts.get())
        } finally { server.stop(0); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
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
