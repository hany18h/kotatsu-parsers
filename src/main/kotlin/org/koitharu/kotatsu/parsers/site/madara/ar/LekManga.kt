package org.koitharu.kotatsu.parsers.site.madara.ar

import okhttp3.Headers
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.exception.ContentUnavailableException
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.textOrNull

@MangaSourceParser("MANGALEK", "LekManga", "ar")
internal class LekManga(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MANGALEK, "lekmanga.site", pageSize = 20) {
	override val userAgentKey = ConfigKey.UserAgent(context.getDefaultUserAgent().ifBlank {
		"Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
			"(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
	})

	// The first h1 is an invisible <noscript> "Access Denied" notice, even
	// in successful responses. Only the work's visible title is metadata.
	override fun parseMangaTitle(manga: Manga, document: Document): String =
		document.selectFirst(".post-title h1")?.textOrNull()
			?: throw ContentUnavailableException("لم يُرجع مانجا ليك بيانات العمل. أعد المحاولة أو افتح المصدر في المتصفح للتحقق من الوصول.")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(ConfigKey.InterceptCloudflare(defaultValue = true))
	}

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("User-Agent", config[userAgentKey])
		.add("Referer", "https://$domain/")
		.add("Accept-Language", "ar,en-US;q=0.8,en;q=0.7")
		.build()

	// The site's admin-ajax endpoint returns a stale ten-item window. Its public
	// paged search is current and includes newly published works.
	override val withoutAjax = true
}
