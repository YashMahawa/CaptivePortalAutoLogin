package de.binarynoise.captiveportalautologin

internal class NetworkAttemptLimiter(private val cooldownMillis: Long = 60_000L) {
    private var lastNetwork: Any? = null
    private var lastAttemptAt: Long? = null

    fun acquire(network: Any, now: Long, manual: Boolean): Boolean {
        if (!manual && lastNetwork == network && lastAttemptAt?.let { now - it < cooldownMillis } == true) {
            return false
        }
        lastNetwork = network
        lastAttemptAt = now
        return true
    }
}
