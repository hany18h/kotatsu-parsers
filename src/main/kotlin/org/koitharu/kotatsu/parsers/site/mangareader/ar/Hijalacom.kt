package org.koitharu.kotatsu.parsers.site.mangareader.ar

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat

/**
 * Parser محدث لموقع hijala.com
 * آخر تحديث: أكتوبر 2025
 */
@MangaSourceParser("HIJALACOM", "Hijalacom", "ar")
internal class Hijalacom(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.HIJALACOM, "hijala.com", pageSize = 30, searchPageSize = 10) {
	
	// الإعدادات الأساسية
	override val listUrl = "/manga"
	override val datePattern = "MMMM d, yyyy"
	
	// تفعيل الحماية إذا كان الموقع يستخدم NetShield
	override val isNetShieldProtected = true
	
	// تفعيل الترميز إذا كانت الصور مشفرة
	override val encodedSrc = true
	
	// === Selectors محدثة للقوائم ===
	// جرب عدة selectors للتوافق مع تغييرات الموقع
	override val selectMangaList = """
		.postbody .listupd .bs .bsx,
		.bixbox .listupd article,
		.listupd article.bs,
		.utao .uta .imgu,
		article.bs
	""".trimIndent()
	
	override val selectMangaListImg = """
		img.ts-post-image,
		img.attachment-medium,
		img[loading='lazy'],
		.limit img,
		img
	""".trimIndent()
	
	override val selectMangaListTitle = """
		div.tt,
		.bigor .tt,
		.entry-title,
		h2,
		a[title]
	""".trimIndent()
	
	// === Selectors للفصول ===
	override val selectChapter = """
		#chapterlist ul li,
		.eplister ul li,
		.bxcl ul li,
		.eplisterfull ul li
	""".trimIndent()
	
	// === Selectors للصفحات ===
	override val selectTestScript = """
		script:containsData(ts_reader.run),
		script:containsData(ts_reader),
		script:containsData(prevnext)
	""".trimIndent()
	
	override val selectPage = """
		div#readerarea img,
		.rdminimal img,
		#readerarea img,
		.reader-area img,
		.chimg img
	""".trimIndent()
	
	override val selectScript = """
		div.wrapper script,
		script[src^='data:text/javascript'],
		script[src*='base64']
	""".trimIndent()
	
	// === Selector للوصف ===
	override val detailsDescriptionSelector = """
		div.entry-content,
		.entry-content-single,
		[itemprop='description'],
		.summary .summ,
		.desc,
		.synopsis
	""".trimIndent()
	
	/**
	 * دالة محسّنة لاستخراج قائمة المانجا
	 * تحاول عدة selectors للتوافق مع التغييرات
	 */
	override fun parseMangaList(docs: Document): List<Manga> {
		// محاولة 1: استخدام الـ selectors القياسية
		var elements = docs.select(".postbody .listupd .bs .bsx")
		
		// محاولة 2: selectors بديلة
		if (elements.isEmpty()) {
			elements = docs.select(".bixbox .listupd article, .listupd article.bs")
		}
		
		// محاولة 3: selector عام
		if (elements.isEmpty()) {
			elements = docs.select("article.bs, .utao .uta")
		}
		
		// إذا لم نجد أي عناصر، نستخدم الدالة الأصلية
		if (elements.isEmpty()) {
			return super.parseMangaList(docs)
		}
		
		return elements.mapNotNull { el ->
			try {
				val a = el.selectFirst("a") ?: return@mapNotNull null
				val url = a.attrAsRelativeUrl("href")
				
				// البحث عن العنوان بعدة طرق
				val title = el.selectFirst("div.tt, .bigor .tt")?.text()
					?: el.selectFirst(".entry-title")?.text()
					?: a.attr("title")
					?: el.selectFirst("h2, h3, h4")?.text()
					?: return@mapNotNull null
				
				// البحث عن التقييم
				val rating = el.selectFirst(".numscore, .rt .rating, .rating")?.text()
					?.replace(",", ".")
					?.toFloatOrNull()
					?.div(10) 
					?: RATING_UNKNOWN
				
				// البحث عن الصورة
				val coverUrl = el.selectFirst("img.ts-post-image, img[loading='lazy'], .limit img, img")
					?.src()
				
				Manga(
					id = generateUid(url),
					url = url,
					title = title.trim(),
					altTitles = emptySet(),
					publicUrl = a.attrAsAbsoluteUrl("href"),
					rating = rating,
					contentRating = ContentRating.SAFE,
					coverUrl = coverUrl,
					tags = emptySet(),
					state = null,
					authors = emptySet(),
					source = source,
				)
			} catch (e: Exception) {
				// تجاهل العناصر التي تسبب أخطاء
				null
			}
		}
	}
	
	/**
	 * دالة محسّنة لاستخراج تفاصيل المانجا
	 */
	override suspend fun parseInfo(docs: Document, manga: Manga, chapters: List<MangaChapter>): Manga {
		return try {
			super.parseInfo(docs, manga, chapters)
		} catch (e: Exception) {
			// في حالة فشل الدالة الأصلية، نستخدم parsing بسيط
			manga.copy(
				title = docs.selectFirst("h1.entry-title, h1, .entry-title")?.text() ?: manga.title,
				description = docs.selectFirst(detailsDescriptionSelector)?.text(),
				chapters = chapters,
			)
		}
	}
	
	/**
	 * دالة محسّنة لاستخراج الصفحات
	 */
	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		return try {
			super.getPages(chapter)
		} catch (e: Exception) {
			// محاولة بديلة: استخراج الصور مباشرة من HTML
			val chapterUrl = chapter.url.toAbsoluteUrl(domain)
			val docs = webClient.httpGet(chapterUrl).parseHtml()
			
			docs.select("#readerarea img, .rdminimal img, .reader-area img").mapNotNull { img ->
				val url = img.src()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
				MangaPage(
					id = generateUid(url),
					url = url,
					preview = null,
					source = source,
				)
			}.ifEmpty {
				throw e // إعادة رمي الخطأ الأصلي إذا فشلت المحاولة البديلة
			}
		}
	}
}
