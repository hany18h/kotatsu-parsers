package org.koitharu.kotatsu.parsers.site.madara.ar

import kotlinx.coroutines.delay
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@MangaSourceParser("AZORAMOON", "AzoraMoon", "ar")
internal class AzoraMoon(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.AZORAMOON, "azoramoon.com", pageSize = 10) {
	
	override val tagPrefix = "series-genre/"
	override val listUrl = "series/"
	override val withoutAjax = true

	// ============================================
	// PERMANENT CACHING SYSTEM
	// ============================================
	private val tagCache = ConcurrentHashMap<String, Set<MangaTag>>()
	private val filterOptionsCache = ConcurrentHashMap<String, MangaListFilterOptions>()
	private val listPageCache = ConcurrentHashMap<String, List<Manga>>()
	
	private var lastRequestTime = 0L
	private val minRequestInterval = 1500L // 1.5 seconds for heavy rate limiting

	// ============================================
	// RATE LIMITING
	// ============================================
	private suspend fun rateLimit() {
		val currentTime = System.currentTimeMillis()
		val timeSinceLastRequest = currentTime - lastRequestTime
		if (timeSinceLastRequest < minRequestInterval) {
			delay(minRequestInterval - timeSinceLastRequest)
		}
		lastRequestTime = System.currentTimeMillis()
	}

	// ============================================
	// PERMANENT CACHE HELPER
	// ============================================
	private suspend inline fun <T> withPermanentCache(
		cache: ConcurrentHashMap<String, T>,
		key: String,
		useRateLimit: Boolean = true,
		crossinline fetcher: suspend () -> T
	): T {
		// Return cached data immediately if exists
		cache[key]?.let {
			println("AzoraMoon: ✓ Cache hit for $key")
			return it
		}

		println("AzoraMoon: ↓ Making ONE-TIME request for $key")

		if (useRateLimit) {
			rateLimit()
		}

		return try {
			val data = fetcher()
			cache[key] = data
			println("AzoraMoon: ✓ Cached $key (${if (data is Collection<*>) data.size else "data"})")
			data
		} catch (e: Exception) {
			println("AzoraMoon: ✗ Request failed for $key: ${e.message}")
			// Return empty result instead of throwing
			when {
				e.message?.contains("404") == true -> {
					println("AzoraMoon: → Returning empty list for 404")
					@Suppress("UNCHECKED_CAST")
					emptyList<Manga>() as T
				}
				else -> throw e
			}
		}
	}

	// ============================================
	// TAGS & FILTERS
	// ============================================
	override suspend fun fetchAvailableTags(): Set<MangaTag> = withPermanentCache(
		cache = tagCache,
		key = "tags",
		useRateLimit = true
	) {
		super.fetchAvailableTags()
	}

	override suspend fun getFilterOptions(): MangaListFilterOptions = withPermanentCache(
		cache = filterOptionsCache,
		key = "filter_options",
		useRateLimit = true
	) {
		MangaListFilterOptions(
			availableTags = fetchAvailableTags(),
			availableStates = EnumSet.of(
				MangaState.ONGOING,
				MangaState.FINISHED,
				MangaState.ABANDONED
			),
			availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.ADULT),
		)
	}

	// ============================================
	// CACHE KEY GENERATION
	// ============================================
	private fun generateCacheKey(page: Int, order: SortOrder, filter: MangaListFilter): String {
		val query = filter.query?.trim()?.replace(Regex("\\s+"), "_") ?: ""
		val tags = filter.tags.sortedBy { it.key }.joinToString(",") { it.key }
		val states = filter.states.sorted().joinToString(",")
		
		return buildString {
			append("list")
			append("_p${page}")
			append("_${order.name.lowercase()}")
			if (query.isNotEmpty()) append("_q${query}")
			if (tags.isNotEmpty()) append("_t${tags.hashCode()}")
			if (states.isNotEmpty()) append("_s${states.hashCode()}")
		}
	}

	// ============================================
	// MAIN LIST PAGE OVERRIDE
	// ============================================
	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		// ✅ CRITICAL FIX: Force page to 1 for withoutAjax mode
		// The site uses /page/X/ URLs, but page 2+ often don't exist for search
		val effectivePage = if (withoutAjax && !filter.query.isNullOrEmpty()) {
			// For search queries, ONLY use page 1
			if (page > 1) {
				println("AzoraMoon: ⚠ Search pagination blocked - page $page → returning empty")
				return emptyList()
			}
			1
		} else {
			// For normal browsing, allow the requested page but still block > 2
			if (page > 2) {
				println("AzoraMoon: ⚠ Pagination blocked at page $page")
				return emptyList()
			}
			page
		}

		val cacheKey = generateCacheKey(effectivePage, order, filter)

		return withPermanentCache(
			cache = listPageCache,
			key = cacheKey,
			useRateLimit = true
		) {
			try {
				println("AzoraMoon: → Fetching page $effectivePage (${filter.query ?: "browse"})")
				super.getListPage(effectivePage, order, filter)
			} catch (e: Exception) {
				when {
					e.message?.contains("404") == true -> {
						println("AzoraMoon: → 404 error, returning empty list")
						emptyList()
					}
					else -> throw e
				}
			}
		}
	}

	// ============================================
	// PAGINATION CONTROL
	// ============================================
	override suspend fun getList(offset: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val requestedPage = (offset / pageSize) + 1
		
		// For search, only allow page 1
		if (!filter.query.isNullOrEmpty() && requestedPage > 1) {
			println("AzoraMoon: ⚠ Search pagination disabled at offset $offset")
			return emptyList()
		}
		
		// For browsing, limit to 2 pages max
		if (requestedPage > 2) {
			println("AzoraMoon: ⚠ Browse pagination limited at offset $offset")
			return emptyList()
		}

		return getListPage(requestedPage, order, filter)
	}

	// ============================================
	// LOGGING HELPER
	// ============================================
	init {
		println("""
			|============================================
			|AzoraMoon Parser Initialized
			|============================================
			|Mode: withoutAjax = $withoutAjax
			|Page Size: $pageSize
			|Rate Limit: ${minRequestInterval}ms
			|Domain: $domain
			|============================================
		""".trimMargin())
	}
}
