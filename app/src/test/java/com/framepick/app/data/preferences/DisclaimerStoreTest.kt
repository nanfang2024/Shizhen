package com.framepick.app.data.preferences

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisclaimerStoreTest {
    @Test
    fun showsDisclaimerWhenNeverAccepted() {
        assertTrue(DisclaimerStore.shouldShowDisclaimer(null))
    }

    @Test
    fun hidesDisclaimerWhenAcceptedCurrentVersion() {
        assertFalse(
            DisclaimerStore.shouldShowDisclaimer(
                DisclaimerStore.DISCLAIMER_VERSION,
            ),
        )
    }

    @Test
    fun showsDisclaimerWhenAcceptedOlderVersion() {
        assertTrue(DisclaimerStore.shouldShowDisclaimer("v0", "v1"))
    }

    @Test
    fun showsDisclaimerWhenAcceptedVersionBlank() {
        assertTrue(DisclaimerStore.shouldShowDisclaimer(""))
    }
}
