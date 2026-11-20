package com.soundscape.app.presentation.home

import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.presentation.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HomeMessagesTest {
    @Test
    fun networkAndTimeoutHaveDifferentMessages() {
        assertEquals(R.string.home_offline, errorText(DomainError.Network))
        assertEquals(R.string.home_timeout, errorText(DomainError.Timeout))
        assertNotEquals(errorText(DomainError.Network), errorText(DomainError.Timeout))
    }

    @Test
    fun cachedContentKeepsReasonOfRefreshFailure() {
        assertEquals(R.string.home_cached, contentNoticeText(null))
        assertEquals(R.string.home_cache_offline, contentNoticeText(DomainError.Network))
        assertEquals(R.string.home_cache_timeout, contentNoticeText(DomainError.Timeout))
        assertEquals(R.string.home_cache_rate_limited, contentNoticeText(DomainError.RateLimited()))
        assertEquals(R.string.home_cache_error, contentNoticeText(DomainError.Server))
    }

    @Test
    fun rateLimitIsNotReportedAsOfflineOrTimeout() {
        assertEquals(R.string.home_rate_limited, errorText(DomainError.RateLimited()))
        assertEquals(R.string.home_refresh_error, errorText(DomainError.Server))
        assertEquals(R.string.home_refresh_error, errorText(DomainError.Unexpected))
    }
}
