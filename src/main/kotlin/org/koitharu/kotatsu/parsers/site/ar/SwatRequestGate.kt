package org.koitharu.kotatsu.parsers.site.ar

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.HttpStatusException
import org.koitharu.kotatsu.parsers.exception.TooManyRequestExceptions
import java.io.IOException

/** Serialize API calls across parser instances; repeated UI loads share a short cache. */
internal class SwatRequestGate(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutex = Mutex()
    private var nextRequestAt = 0L
    private val cache = LinkedHashMap<String, Pair<Long, String>>()

    suspend fun get(url: String, load: suspend () -> String): String = mutex.withLock {
        cache[url]?.takeIf { clock() - it.first < 20_000 }?.let { return@withLock it.second }
        repeat(2) { attempt ->
            val wait = (nextRequestAt - clock()).coerceAtLeast(0)
            // Long Retry-After values are surfaced to the UI, never slept through.
            if (wait > 5_000) throw TooManyRequestExceptions(url, wait)
            if (wait > 0) pause(wait)
            nextRequestAt = clock() + 1_000
            try {
                val body = load()
                if (body.length <= 512_000) {
                    cache.remove(url)
                    cache[url] = clock() to body
                    while (cache.size > 12) cache.remove(cache.keys.first())
                }
                return@withLock body
            } catch (error: IOException) {
                val retry = when {
                    error is TooManyRequestExceptions -> error.getRetryDelay().coerceAtLeast(1_500)
                    error is HttpStatusException && error.statusCode == 429 -> 1_500L
                    else -> throw error
                }
                nextRequestAt = maxOf(nextRequestAt, clock() + retry)
                if (attempt == 1 || retry > 5_000) throw error
            }
        }
        error("Unreachable")
    }
}
