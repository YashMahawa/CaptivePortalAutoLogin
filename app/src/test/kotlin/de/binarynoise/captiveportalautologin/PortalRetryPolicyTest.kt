package de.binarynoise.captiveportalautologin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PortalRetryPolicyTest {
    @Test fun `failed logins back off and stop increasing at fifteen minutes`() {
        val policy = PortalRetryPolicy()
        assertEquals(listOf(60_000L, 120_000L, 240_000L, 480_000L, 900_000L, 900_000L),
            List(6) { policy.nextDelayMillis() })
    }
    @Test fun `a new connection starts at the shortest delay`() {
        val policy = PortalRetryPolicy()
        repeat(6) { policy.nextDelayMillis() }
        policy.reset()
        assertEquals(60_000L, policy.nextDelayMillis())
    }
}
