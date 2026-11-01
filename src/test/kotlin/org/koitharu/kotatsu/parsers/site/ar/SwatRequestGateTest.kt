package org.koitharu.kotatsu.parsers.site.ar

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.koitharu.kotatsu.parsers.exception.TooManyRequestExceptions
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class SwatRequestGateTest {
    @Test fun simultaneousScreensShareTheResponseAndDifferentCallsArePaced() = runTest {
        val gate = SwatRequestGate({ testScheduler.currentTime }, { delay(it) })
        val starts = mutableListOf<Long>()
        suspend fun load() = "response".also { starts += testScheduler.currentTime }
        val data = listOf("genres", "catalogue", "catalogue").map { url -> async { gate.get(url, ::load) } }.awaitAll()
        assertEquals(listOf("response", "response", "response"), data)
        assertEquals(listOf(0L, 1_000L), starts)
        delay(21_000)
        gate.get("catalogue", ::load)
        assertEquals(3, starts.size)
    }
    @Test fun aTemporary429RetriesOnlyOnceAndThenKeepsTheCooldown() = runTest {
        val gate = SwatRequestGate({ testScheduler.currentTime }, { delay(it) })
        var calls = 0
        try {
            gate.get("api") { calls++; throw TooManyRequestExceptions("api", 0) }
            fail<Unit>("Expected 429")
        } catch (_: TooManyRequestExceptions) { }
        assertEquals(2, calls)
        assertTrue(testScheduler.currentTime >= 1_500)
    }
    @Test fun longServerCooldownDoesNotCauseAnUnboundedWaitOrAnotherRequest() = runTest {
        val gate = SwatRequestGate({ testScheduler.currentTime }, { delay(it) })
        var calls = 0
        repeat(2) {
            try { gate.get("api") { calls++; throw TooManyRequestExceptions("api", 60_000) } }
            catch (_: TooManyRequestExceptions) { }
        }
        assertEquals(1, calls)
        assertEquals(0L, testScheduler.currentTime)
    }
    @Test fun otherNetworkErrorsAreNotRetried() = runTest {
        val gate = SwatRequestGate({ testScheduler.currentTime }, { delay(it) })
        var calls = 0
        try { gate.get("api") { calls++; throw IOException("offline") } } catch (_: IOException) { }
        assertEquals(1, calls)
    }
}
