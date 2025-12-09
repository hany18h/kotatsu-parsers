package org.koitharu.kotatsu.parsers.site.madara.ar

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

	// PERMANENT caching system
	private val tagCache = ConcurrentHashMap<String, Set<MangaTag>>()
	private val filterOptionsCache = ConcurrentHashMap<String, MangaListFilterOptions>()
	private val singlePageCache = ConcurrentHashMap<String, List<Manga>>()
	
	// Rate limiting
	private val requestMutex = Mutex()
	private var lastRequestTime = 0L
	private val minRequestInterval = 5000L // 5 ثواني بين كل طلب (زيادة من 1.2 ثانية)

	// Rate limiting helper
	private suspend fun rateLimit() {
		requestMutex.withLock {
			val currentTime = System.currentTimeMillis()
			val timeSinceLastRequest = currentTime - lastRequestTime
			if (timeSinceLastRequest < minRequestInterval) {
				val waitTime = minRequestInterval - timeSinceLastRequest
				println("AzoraMoon: الانتظار ${waitTime}ms قبل الطلب التالي")
				delay(waitTime)
			}
			lastRequestTime = System.currentTimeMillis()
			println("AzoraMoon: تنفيذ الطلب")
		}
	}

	// Helper function for PERMANENT caching
	private suspend inline fun <T> withPermanentCache(
		cache: ConcurrentHashMap<String, T>,
		key: String,
		crossinline fetcher: suspend () -> T
	): T {
		// تحقق من الـ cache أولاً
		val cached = cache[key]
		if (cached != null) {
			println("AzoraMoon: استخدام البيانات المحفوظة لـ $key")
			return cached
		}

		println("AzoraMoon: طلب جديد لـ $key")

		// تطبيق rate limiting
		rateLimit()

		return try {
			val data = fetcher()
			cache[key] = data // حفظ دائم
			println("AzoraMoon: تم حفظ $key بنجاح")
			data
		} catch (e: Exception) {
			println("AzoraMoon: فشل الطلب لـ $key: ${e.message}")
			throw e
		}
	}

	// Override tag fetching with caching
	override suspend fun fetchAvailableTags(): Set<MangaTag> = withPermanentCache(
		cache = tagCache,
		key = "tags"
	) {
		super.fetchAvailableTags()
	}

	// Override filter options with caching
	override suspend fun getFilterOptions(): MangaListFilterOptions = withPermanentCache(
		cache = filterOptionsCache,
		key = "filter_options"
	) {
		MangaListFilterOptions(
			availableTags = fetchAvailableTags(),
			availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.ABANDONED),
			availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.ADULT),
		)
	}

	// Override list page with LIMITED caching
	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		// السماح بالصفحات الأولى فقط (1-3)
		if (page > 3) {
			println("AzoraMoon: منع صفحة $page - السماح فقط بالصفحات 1-3")
			return emptyList()
		}

		// مفتاح cache مبسط
		val simplifiedKey = when {
			filter.query.isNullOrEmpty() && filter.tags.isEmpty() && filter.states.isEmpty() ->
				"basic_list_${order}_p${page}"
			!filter.query.isNullOrEmpty() -> {
				// تجميع عمليات البحث المتشابهة
				val words = filter.query!!.trim().lowercase().split(" ").take(2).joinToString(" ")
				"search_${words}_${order}_p${page}"
			}
			else ->
				"filtered_${order}_p${page}"
		}

		return withPermanentCache(
			cache = singlePageCache,
			key = simplifiedKey
		) {
			super.getListPage(page, order, filter)
		}
	}
}
