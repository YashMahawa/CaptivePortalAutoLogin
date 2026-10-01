package de.binarynoise.captiveportalautologin

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import java.util.concurrent.TimeUnit

/** Linux credentials stay in a private owner-only file; never put a password on the command line. */
internal object IitjLogin {
    data class Profile(val url: String, val username: String, val password: String, val certificate: Boolean, val connection: String?)
    val defaultPath: Path get() = Path.of(System.getProperty("user.home"), ".config", "captiveportalautologin", "iitj.properties")
    private val ownerOnly = PosixFilePermissions.fromString("rw-------")

    fun save(path: Path, profile: Profile) {
        require(PortalNavigation.isIitj(profile.url.toHttpUrl()) && PortalNavigation.allowed(profile.url.toHttpUrl(), profile.url.toHttpUrl())) { "Only the IITJ gateway and netaccess HTTPS endpoints are allowed" }
        require(profile.username.isNotBlank() && profile.password.isNotEmpty()) { "Username and password are required" }
        profile.connection?.let { require(java.util.UUID.fromString(it).toString() == it) { "Invalid NetworkManager connection UUID" } }
        val target = path.toAbsolutePath()
        Files.createDirectories(target.parent)
        require(!Files.isSymbolicLink(target)) { "Refusing a symbolic-link credential file" }
        val temp = Files.createTempFile(target.parent, ".iitj-", ".tmp", PosixFilePermissions.asFileAttribute(ownerOnly))
        try {
            val props = Properties().apply {
                setProperty("url", profile.url); setProperty("username", profile.username); setProperty("password", profile.password)
                setProperty("certificateException", profile.certificate.toString())
                profile.connection?.let { setProperty("connectionUuid", it) }
            }
            Files.newOutputStream(temp).use { props.store(it, "IITJ profile - keep private") }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { Files.deleteIfExists(temp) }
    }

    fun load(path: Path): Profile {
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "Configure an IITJ profile first with --configure-iitj" }
        require(Files.getPosixFilePermissions(path) == ownerOnly) { "Credential file must have permissions 600: chmod 600 '${path.fileName}'" }
        val props = Properties().apply { Files.newInputStream(path).use { load(it) } }
        val profile = Profile(props.getProperty("url", ""), props.getProperty("username", ""), props.getProperty("password", ""),
            props.getProperty("certificateException", "false") == "true", props.getProperty("connectionUuid")?.takeIf { it.isNotBlank() })
        require(PortalNavigation.isIitj(profile.url.toHttpUrl()) && PortalNavigation.allowed(profile.url.toHttpUrl(), profile.url.toHttpUrl())) { "Profile URL is not an approved IITJ HTTPS endpoint" }
        require(profile.username.isNotBlank() && profile.password.isNotEmpty()) { "Profile is missing credentials" }
        return profile
    }

    fun activeConnections(): Set<String> {
        val process = ProcessBuilder("nmcli", "-t", "-f", "UUID", "connection", "show", "--active").start()
        check(process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); "NetworkManager query timed out" }
        check(process.exitValue() == 0) { "NetworkManager query failed" }
        return process.inputStream.bufferedReader().readLines().filter { it.isNotBlank() }.toSet()
    }

    fun configure(path: Path, connection: String?) {
        val console = System.console() ?: error("Run --configure-iitj in a terminal so the password can be entered without echo")
        val url = console.readLine("Portal URL [https://netaccess.iitj.ac.in/24online/servlet/E24onlineHTTPClient]: ")
            .takeUnless { it.isBlank() } ?: "https://netaccess.iitj.ac.in/24online/servlet/E24onlineHTTPClient"
        val username = console.readLine("Internet username: ").trim()
        val chars = console.readPassword("Internet password (hidden): ") ?: error("No password entered")
        try {
            val certificate = console.readLine("Allow an unverified IITJ portal certificate? [y/N]: ").equals("y", true)
            val detected = connection ?: runCatching { activeConnections().singleOrNull() }.getOrNull()
            save(path, Profile(url, username, String(chars), certificate, detected))
            println("Saved private profile to $path")
            println(if (detected == null) "Manual login is available. For automatic mode, configure again with --connection-uuid <campus connection UUID>."
                else "Automatic mode is restricted to connection $detected.")
        } finally { chars.fill('\u0000') }
    }

    private fun online(): Boolean {
        val client = OkHttpClient.Builder().followRedirects(false).callTimeout(5, TimeUnit.SECONDS).build()
        return try {
            client.newCall(Request.Builder().url("https://www.google.com/generate_204").build()).execute().use { it.code == 204 }
        } catch (_: Exception) { false }
        finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }

    fun login(profile: Profile): Boolean {
        if (online()) { println("Internet already active."); return true }
        val cookies = mutableListOf<Cookie>()
        val builder = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS).cookieJar(object : CookieJar {
                override fun loadForRequest(url: HttpUrl) = cookies.filter { it.matches(url) }
                override fun saveFromResponse(url: HttpUrl, received: List<Cookie>) {
                    for (cookie in received) {
                        cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                        cookies.add(cookie)
                    }
                }
            })
        if (profile.certificate) IitjCertificateException.apply(builder)
        val client = builder.build()
        try {
            ManualPortalFlow.submit(client, profile.url.toHttpUrl(), profile.username, profile.password)
            val verified = online()
            println(if (verified) "Login completed. Internet access verified." else "Login submitted, but internet access is not verified. The portal may require an additional step.")
            return verified
        } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }
}
