package com.framepick.app.domain.parser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDlpUpdateThrottleTest {
    @Test
    fun neverAttemptedAlwaysProceeds() {
        val now = 1_000_000_000L
        assertFalse(YtDlpUpdateThrottle.shouldSkip(now, 0L, 0L))
    }

    @Test
    fun successfulUpdateKeepsTwentyFourHourWindow() {
        val lastSuccess = 1_000_000L
        assertTrue(
            YtDlpUpdateThrottle.shouldSkip(
                lastSuccess + YtDlpUpdateThrottle.SUCCESS_INTERVAL_MS - 1_000L,
                lastSuccess,
                0L,
            ),
        )
        assertFalse(
            YtDlpUpdateThrottle.shouldSkip(
                lastSuccess + YtDlpUpdateThrottle.SUCCESS_INTERVAL_MS + 1_000L,
                lastSuccess,
                0L,
            ),
        )
    }

    @Test
    fun failedUpdateReopensWindowWithinAnHour() {
        val lastFailure = 1_000_000L
        val hour = YtDlpUpdateThrottle.FAILURE_RETRY_INTERVAL_MS
        assertTrue(YtDlpUpdateThrottle.shouldSkip(lastFailure + hour - 1_000L, 0L, lastFailure))
        // The one-hour failure backoff unblocks long before the 24-hour
        // success window, so one flaky network attempt cannot pin the
        // bundled extractor for a whole day.
        assertFalse(YtDlpUpdateThrottle.shouldSkip(lastFailure + hour + 1_000L, 0L, lastFailure))
    }

    @Test
    fun failureAfterSuccessUsesShortBackoff() {
        val lastSuccess = 5_000_000L
        val lastFailure = 7_000_000L
        val hour = YtDlpUpdateThrottle.FAILURE_RETRY_INTERVAL_MS
        assertTrue(
            YtDlpUpdateThrottle.shouldSkip(lastFailure + hour - 1_000L, lastSuccess, lastFailure),
        )
        assertFalse(
            YtDlpUpdateThrottle.shouldSkip(lastFailure + hour + 1_000L, lastSuccess, lastFailure),
        )
    }
}
