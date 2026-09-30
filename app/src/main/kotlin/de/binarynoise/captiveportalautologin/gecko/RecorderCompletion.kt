package de.binarynoise.captiveportalautologin.gecko

internal class RecorderCompletion {
    private var sawPortal = false
    private var loadedPage = false
    private var validated = false
    private var completed = false
    fun capabilities(hasPortal: Boolean, isValidated: Boolean) {
        sawPortal = sawPortal || hasPortal
        validated = isValidated && !hasPortal
    }
    fun pageLoaded() { loadedPage = true }
    fun consumeCompletion(): Boolean {
        if (completed || !sawPortal || !loadedPage || !validated) return false
        completed = true
        return true
    }
}
