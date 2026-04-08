package org.koitharu.kotatsu.parsers

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource

private const val HEADER_REFERER = "Referer"
private const val HEADER_ACCEPT = "Accept"

internal class CommonHeadersInterceptor : Interceptor {
	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val source = request.tag(MangaSource::class.java)
		val parser = if (source is MangaParserSource) {
			MangaLoaderContextMock.newParserInstance(source)
		} else {
			null
		}
		val headersBuilder = request.headers.newBuilder()
		
		// 🆕 إضافة headers خاصة للصور من CDNs
		val url = request.url.toString()
		val isImageRequest = isImageUrl(url)
		
		if (isImageRequest) {
			// إضافة Accept header للصور فقط إذا لم يكن موجود
			if (headersBuilder[HEADER_ACCEPT] == null) {
				headersBuilder[HEADER_ACCEPT] = "image/avif,image/webp,image/apng,image/png,image/jpeg,image/*,*/*;q=0.8"
			}
			
			// إضافة Sec-Fetch headers للصور
			if (headersBuilder["Sec-Fetch-Dest"] == null) {
				headersBuilder["Sec-Fetch-Dest"] = "image"
			}
			if (headersBuilder["Sec-Fetch-Mode"] == null) {
				headersBuilder["Sec-Fetch-Mode"] = "no-cors"
			}
			if (headersBuilder["Sec-Fetch-Site"] == null) {
				headersBuilder["Sec-Fetch-Site"] = "same-site"
			}
		}
		// نهاية الإضافة 🆕
		
		if (headersBuilder[HEADER_REFERER] == null && parser != null) {
			headersBuilder[HEADER_REFERER] = "https://${parser.domain}/"
		}
		val newRequest = request.newBuilder().headers(headersBuilder.build()).build()
		return if (parser is Interceptor) {
			parser.intercept(ProxyChain(chain, newRequest))
		} else {
			chain.proceed(newRequest)
		}
	}
	
	/**
	 * تحقق من أن الـ URL يشير إلى صورة
	 */
	private fun isImageUrl(url: String): Boolean {
		// قائمة بـ CDN domains الشائعة للصور
		val imageCdnPatterns = listOf(
			"app.procomic.net",
			"cdn2.procomic.pro",
			"cdn3.procomic.pro",
			"cdn4.procomic.pro",
			"app.procomic.pro",
			"cdn2.prochan.net",
			"cdn3.prochan.net",
		)
		
		// امتدادات الصور الشائعة
		val imageExtensions = listOf(
			".jpg", ".jpeg", ".png", ".gif", 
			".webp", ".avif", ".bmp", ".svg"
		)
		
		// تحقق من CDN patterns
		val isCdnImage = imageCdnPatterns.any { pattern -> 
			url.contains(pattern, ignoreCase = true) 
		}
		
		// تحقق من امتداد الملف
		val hasImageExtension = imageExtensions.any { ext -> 
			url.contains(ext, ignoreCase = true) 
		}
		
		return isCdnImage || hasImageExtension
	}
	
	private class ProxyChain(
		private val delegate: Interceptor.Chain,
		private val request: Request,
	) : Interceptor.Chain by delegate {
		override fun request(): Request = request
	}
}
