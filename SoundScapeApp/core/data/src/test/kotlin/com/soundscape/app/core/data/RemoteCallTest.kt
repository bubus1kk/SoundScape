package com.soundscape.app.core.data

import com.soundscape.app.core.domain.model.DomainError
import com.soundscape.app.core.domain.model.DomainResult
import com.soundscape.app.core.network.ConnectivityChecker
import com.soundscape.app.core.network.NetworkConfig
import com.soundscape.app.core.network.PlacePageDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class RemoteCallTest {
    private fun call(offline: Boolean = false) = RemoteCall(
        NetworkConfig("https://example.invalid/", "sb_publishable_test"),
        ConnectivityChecker { offline }, NetworkErrorMapper(Json))
    @Test fun `offline prevents request`() = runTest {
        val result = call(true).execute<PlacePageDto, Int>({ error("request must not run") }, { it.items.size })
        assertEquals(DomainResult.Failure(DomainError.Network), result)
    }
    @Test fun `empty page succeeds`() = runTest {
        val result = call().execute({ Response.success(PlacePageDto(emptyList(), 20, 0, false)) }, { it.items })
        assertEquals(DomainResult.Success(emptyList<Any>()), result)
    }
    @Test fun `timeouts and DNS remain distinct`() = runTest {
        val timeout = call().execute<PlacePageDto, Int>({ throw SocketTimeoutException() }, { 1 })
        val dns = call().execute<PlacePageDto, Int>({ throw UnknownHostException() }, { 1 })
        assertEquals(DomainResult.Failure(DomainError.Timeout), timeout)
        assertEquals(DomainResult.Failure(DomainError.Network), dns)
    }
    @Test fun `cancellation propagates`() = runTest {
        var cancelled = false
        try {
            call().execute<PlacePageDto, Int>({ throw CancellationException() }, { 1 })
        } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }
}
