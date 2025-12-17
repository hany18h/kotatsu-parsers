package org.koitharu.kotatsu.parsers.site.madara.ar

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*
import org.jsoup.nodes.Document
import java.util.*

@MangaParserSource("CROWSCANS", "CrowScans", "ar")
internal class CrowScans(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.CROWSCANS, "crowscans.com") {

	override val datePattern = "dd MMMM، yyyy"
	override val sourceLocale: Locale = Locale("ar")
	override val postReq = true

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		
		// محاولة استخراج الصور المحمية
		val protectedImages = doc.select("div.protected-image-data[data-src]")
		
		if (protectedImages.isNotEmpty()) {
			val images = mutableListOf<MangaPage>()
			
			protectedImages.forEach { div ->
				val encodedUrl = div.attr("data-src")
				
				if (encodedUrl.isNotEmpty()) {
					try {
						// فك تشفير Base64
						val decodedUrl = context.decodeBase64(encodedUrl).decodeToString()
						
						if (decodedUrl.startsWith("http")) {
							images.add(
								MangaPage(
									id = generateUid(decodedUrl),
									url = decodedUrl,
									preview = null,
									source = source,
								)
							)
						}
					} catch (e: Exception) {
						// استمر إذا فشل فك التشفير
					}
				}
			}
			
			if (images.isNotEmpty()) {
				return images
			}
		}
		
		// استخدام الطريقة الافتراضية
		return super.getPages(chapter)
	}
}
