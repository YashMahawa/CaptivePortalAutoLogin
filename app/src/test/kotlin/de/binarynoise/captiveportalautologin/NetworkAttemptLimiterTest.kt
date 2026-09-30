package de.binarynoise.captiveportalautologin

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NetworkAttemptLimiterTest {
    @Test
    fun `callback storm does not repeat a failed login immediately`() {
        val limiter = NetworkAttemptLimiter()
        assertTrue(limiter.acquire("hostel", 100, manual = false))
        repeat(100) { assertFalse(limiter.acquire("hostel", 101L + it, manual = false)) }
        assertTrue(limiter.acquire("hostel", 60_100, manual = false))
    }

    @Test
    fun `manual login bypasses the automatic cooldown`() {
        val limiter = NetworkAttemptLimiter()
        assertTrue(limiter.acquire("hostel", 100, manual = false))
        assertTrue(limiter.acquire("hostel", 101, manual = true))
        assertFalse(limiter.acquire("hostel", 102, manual = false))
    }

    @Test
    fun `switching networks does not inherit the previous cooldown`() {
        val limiter = NetworkAttemptLimiter()
        assertTrue(limiter.acquire("first", 100, manual = false))
        assertTrue(limiter.acquire("second", 101, manual = false))
    }
}
