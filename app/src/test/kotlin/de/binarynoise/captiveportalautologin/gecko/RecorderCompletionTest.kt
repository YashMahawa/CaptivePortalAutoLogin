package de.binarynoise.captiveportalautologin.gecko

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecorderCompletionTest {
    @Test fun `already logged in network does not instantly close the recorder`() {
        val state = RecorderCompletion()
        state.capabilities(false, true)
        state.pageLoaded()
        assertFalse(state.consumeCompletion())
    }
    @Test fun `validation before the first page cannot close a blank browser`() {
        val state = RecorderCompletion()
        state.capabilities(true, false)
        state.capabilities(false, true)
        assertFalse(state.consumeCompletion())
        state.pageLoaded()
        assertTrue(state.consumeCompletion())
        assertFalse(state.consumeCompletion())
    }
    @Test fun `portal and validated flags together do not mean login succeeded`() {
        val state = RecorderCompletion()
        state.pageLoaded()
        state.capabilities(true, true)
        assertFalse(state.consumeCompletion())
        state.capabilities(false, true)
        assertTrue(state.consumeCompletion())
    }
}
