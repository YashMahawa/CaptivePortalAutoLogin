package de.binarynoise.captiveportalautologin

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class IitjProfileTest {
    @TempDir lateinit var dir: Path
    private val profile = IitjLogin.Profile("https://netaccess.iitj.ac.in/24online/servlet/E24onlineHTTPClient", "fake-user", "fake&password+", true, null)
    @Test fun `profile round trip preserves credentials with owner-only permissions`() {
        val path = dir.resolve("profile")
        IitjLogin.save(path, profile)
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(path))
        assertEquals(profile, IitjLogin.load(path))
        IitjLogin.save(path, profile.copy(password = "new"))
        assertEquals("new", IitjLogin.load(path).password)
    }
    @Test fun `world-readable credentials are rejected`() {
        val path = dir.resolve("profile")
        IitjLogin.save(path, profile)
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"))
        assertThrows(IllegalArgumentException::class.java) { IitjLogin.load(path) }
    }
    @Test fun `symlinks are neither followed nor overwritten`() {
        val real = dir.resolve("real")
        IitjLogin.save(real, profile)
        val link = Files.createSymbolicLink(dir.resolve("link"), real)
        assertThrows(IllegalArgumentException::class.java) { IitjLogin.load(link) }
        assertThrows(IllegalArgumentException::class.java) { IitjLogin.save(link, profile.copy(password = "other")) }
        assertEquals(profile, IitjLogin.load(real))
    }
    @Test fun `unapproved endpoints and URL credentials are rejected`() {
        for (url in listOf("https://evil.test/login", "https://u:p@netaccess.iitj.ac.in/", "http://netaccess.iitj.ac.in/"))
            assertThrows(IllegalArgumentException::class.java) { IitjLogin.save(dir.resolve("bad"), profile.copy(url = url)) }
    }
}
