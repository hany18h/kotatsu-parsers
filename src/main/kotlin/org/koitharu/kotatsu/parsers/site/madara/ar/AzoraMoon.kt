package org.koitharu.kotatsu.parsers.site.madara.ar

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.exception.TooManyRequestExceptions
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@MangaSourceParser("AZORAMOON", "AzoraMoon", "ar")
internal class AzoraMoon(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.AZORAMOON, "azoramoon.com", pageSize = 10) {
	override val tagPrefix = "series-genre/"
	override val listUrl = "series/"

	// Wrapper class to cache both success and failure results
	private sealed class CacheResult<out T> {
		data class Success<T>(val data: T) : CacheResult<T>()
		data class Failure(val exception: Exception) : CacheResult<Nothing>()
	}

	// PERMANENT caching system
	private val singlePageCache = ConcurrentHashMap<String, CacheResult<List<Manga>>>()
	private val inProgressRequests = ConcurrentHashMap<String, CompletableDeferred<CacheResult<*>>>()
	private val requestMutex = Mutex() // Global mutex to prevent concurrent requests
	private var lastRequestTime = 0L
	private val minRequestInterval = 3000L // 3 seconds between requests
	private var requestCounter = 0
	private val maxRequestsPerSession = 3 // Reduced to 3 requests per session
	private val rateLimitErrorDelay = 10000L // Wait 10 seconds after rate limit error

	// Rate limiting helper with request counter
	private suspend fun rateLimit() {
		requestMutex.withLock {
			requestCounter++
			if (requestCounter > maxRequestsPerSession) {
				val errorMsg = "تم الوصول للحد الأقصى من الطلبات. الرجاء إعادة تشغيل التطبيق."
				println("AzoraMoon: Maximum request limit reached ($maxRequestsPerSession)")
				throw Exception(errorMsg)
			}

			val currentTime = System.currentTimeMillis()
			val timeSinceLastRequest = currentTime - lastRequestTime
			if (timeSinceLastRequest < minRequestInterval) {
				val waitTime = minRequestInterval - timeSinceLastRequest
				println("AzoraMoon: Waiting ${waitTime}ms before request #$requestCounter")
				delay(waitTime)
			}
			lastRequestTime = System.currentTimeMillis()
			println("AzoraMoon: Executing request #$requestCounter")
		}
	}

	// Check if exception is rate limiting related
	private fun isRateLimitError(e: Exception): Boolean {
		return e is TooManyRequestExceptions ||
		       e.message?.lowercase()?.contains("too many") == true ||
		       e.message?.lowercase()?.contains("rate limit") == true ||
		       e.message?.lowercase()?.contains("429") == true ||
		       e.message?.lowercase()?.contains("too man") == true
	}

	// Helper function for PERMANENT caching
	@Suppress("UNCHECKED_CAST")
	private suspend inline fun <T> withPermanentCache(
		cache: ConcurrentHashMap<String, CacheResult<T>>,
		key: String,
		crossinline fetcher: suspend () -> T
	): T {
		// First check cache WITHOUT lock for performance
		val cached = cache[key]
		if (cached != null) {
			when (cached) {
				is CacheResult.Success -> {
					println("AzoraMoon: Returning cached data for $key")
					return cached.data
				}
				is CacheResult.Failure -> {
					println("AzoraMoon: Cache FAILURE for $key: ${cached.exception.message}")
					throw cached.exception
				}
			}
		}

		// Use mutex to ensure only ONE request per key at a time
		return requestMutex.withLock {
			// Double-check cache after acquiring lock
			val cachedAfterLock = cache[key]
			if (cachedAfterLock != null) {
				when (cachedAfterLock) {
					is CacheResult.Success -> return@withLock cachedAfterLock.data
					is CacheResult.Failure -> throw cachedAfterLock.exception
				}
			}

			// Check if another coroutine is already making this request
			val existingRequest = inProgressRequests[key] as? CompletableDeferred<CacheResult<T>>
			if (existingRequest != null) {
				println("AzoraMoon: Waiting for in-progress request for $key")
				val result = existingRequest.await()
				when (result) {
					is CacheResult.Success -> return@withLock result.data
					is CacheResult.Failure -> throw result.exception
				}
			}

			// Create new request
			val deferred = CompletableDeferred<CacheResult<T>>()
			inProgressRequests[key] = deferred as CompletableDeferred<CacheResult<*>>

			try {
				println("AzoraMoon: Making ONE-TIME request for $key")

				var retryCount = 0
				val maxRetries = 1 // Only 1 retry to avoid spam
				var lastException: Exception? = null

				while (retryCount <= maxRetries) {
					try {
						// Apply rate limiting before each attempt
						rateLimit()

						val data = fetcher()
						val result = CacheResult.Success(data)
						cache[key] = result
						deferred.complete(result)
						println("AzoraMoon: Successfully cached $key")
						return@withLock data
					} catch (e: Exception) {
						// Don't cache cancellation exceptions - they're not real failures
						if (e is CancellationException) {
							println("AzoraMoon: Request cancelled for $key, not caching")
							deferred.cancel(e)
							throw e
						}

						lastException = e
						println("AzoraMoon: Attempt ${retryCount + 1}/${maxRetries + 1} failed for $key: ${e.message}")

						if (isRateLimitError(e)) {
							retryCount++
							if (retryCount <= maxRetries) {
								val waitTime = rateLimitErrorDelay
								println("AzoraMoon: Waiting ${waitTime}ms before retry...")
								withContext(NonCancellable) {
									delay(waitTime)
								}
								continue
							}
						} else {
							// Non-rate-limit error (including max requests reached) - cache immediately and throw
							val failureResult = CacheResult.Failure(e)
							cache[key] = failureResult
							deferred.complete(failureResult)
							println("AzoraMoon: Failed to fetch page: ${e.message}")
							throw e
						}
					}
				}

				// All retries failed - cache the failure
				val finalException = lastException ?: Exception("Max retries exceeded for $key")
				println("AzoraMoon: All retries failed for $key")
				val finalFailure = CacheResult.Failure(finalException)
				cache[key] = finalFailure
				deferred.complete(finalFailure)
				throw finalException
			} finally {
				inProgressRequests.remove(key)
			}
		}
	}

	// Override tag fetching - DISABLED due to extreme rate limiting
	override suspend fun fetchAvailableTags(): Set<MangaTag> {
		println("AzoraMoon: Tags fetching is disabled due to rate limiting")
		return emptySet()
	}

	// Override filter options - return minimal options without making requests
	override suspend fun getFilterOptions(): MangaListFilterOptions {
		println("AzoraMoon: Returning minimal filter options (no tags)")
		return MangaListFilterOptions(
			availableTags = emptySet(),
			availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.ABANDONED),
			availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.ADULT),
		)
	}

	// Override list page with VERY LIMITED caching
	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		// Only allow page 1 to avoid pagination spam
		if (page > 1) {
			println("AzoraMoon: Blocking page $page - only page 1 allowed")
			return emptyList()
		}

		// Simplified cache key - merge similar searches
		val searchQuery = filter.query?.trim()?.lowercase() ?: ""
		val simplifiedKey = when {
			searchQuery.isEmpty() && filter.tags.isEmpty() && filter.states.isEmpty() ->
				"basic_list_${order}"
			searchQuery.length <= 3 ->
				"search_short_${order}"
			else -> {
				// Group searches by first 2 words
				val words = searchQuery.split(" ").take(2).joinToString(" ")
				"search_${words}_${order}"
			}
		}

		return withPermanentCache(
			cache = singlePageCache,
			key = simplifiedKey
		) {
			super.getListPage(1, order, filter)
		}
	}
}
