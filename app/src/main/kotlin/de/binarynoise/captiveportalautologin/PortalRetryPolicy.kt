package de.binarynoise.captiveportalautologin

internal class PortalRetryPolicy {
    private var failures = 0
    fun reset() { failures = 0 }
    fun nextDelayMillis(): Long {
        val delay = (60_000L shl failures.coerceAtMost(4)).coerceAtMost(900_000L)
        failures++
        return delay
    }
}
