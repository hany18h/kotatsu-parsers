package org.koitharu.kotatsu.parsers.site.ar

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.util.*
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@MangaSourceParser("MANGATEK", "MangaTek", "ar", ContentType.MANGA)
internal class MangaTek(private val loaderContext: MangaLoaderContext) :
    PagedMangaParser(loaderContext, MangaParserSource.MANGATEK, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("mangatek.com")
    override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_MOBILE)

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
        keys.add(ConfigKey.InterceptCloudflare(defaultValue = true))
    }

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = false,
            isMultipleTagsSupported = false,
            isTagsExclusionSupported = false,
        )

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.ALPHABETICAL,
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query?.trim().orEmpty()
        val url = buildString {
            append("https://")
            append(domain)
            append("/manga-list")
            
            when {
                query.isNotEmpty() -> {
                    append("?search=")
                    append(query.urlEncoded())
                }
                else -> {
                    append("?sort=")
                    append(
                        when (order) {
                            SortOrder.POPULARITY -> "views"
                            SortOrder.ALPHABETICAL -> "title&sortOrder=ASC"
                            else -> "latest"
                        }
                    )
                }
            }
            
            if (page > 1) {
                append("&page=")
                append(page)
            }
        }

        val doc = loadDocument(url)
        
        // إزالة العناصر المزعجة
        cleanDocument(doc)
        
        return doc.select("div.grid a.manga-card").mapNotNull { card ->
            val link = card.attr("href")
            if (link.isEmpty()) return@mapNotNull null
            
            val slug = link.removePrefix("/manga/")
            
            val title = card.selectFirst("h3")?.text()?.trim()
            if (title.isNullOrEmpty()) return@mapNotNull null
            
            val ratingElement = card.selectFirst("span:has(i.fa-star) > span:not(:has(i))")
            val rating = ratingElement?.text()?.toFloatOrNull()?.div(10) ?: RATING_UNKNOWN
            
            Manga(
                id = generateUid(slug),
                url = slug,
                publicUrl = "https://$domain$link",
                title = title,
                coverUrl = card.selectFirst("img")?.src(),
                altTitles = emptySet(),
                rating = rating,
                tags = emptySet(),
                authors = emptySet(),
                state = null,
                source = source,
                contentRating = ContentRating.SAFE,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val url = "https://$domain/manga/${manga.url}"
        val doc = loadDocument(url)
        
        // إزالة العناصر المزعجة
        cleanDocument(doc)
        
        val title = doc.selectFirst("h1")?.text() ?: manga.title
        
        // تحسين استخراج الوصف مع تنظيفه من الرسائل غير المرغوب فيها
        val description = extractCleanDescription(doc)
        
        val statusText = doc.selectFirst("span.border")?.text()
        val state = when {
            statusText?.contains("مستمر") == true -> MangaState.ONGOING
            statusText?.contains("مكتمل") == true -> MangaState.FINISHED
            statusText?.contains("متوقف") == true -> MangaState.PAUSED
            else -> null
        }
        
        val tags = doc.select("div.flex.gap-2 span.text-gray-300").mapNotNullToSet { tag ->
            val tagName = tag.text().trim()
            if (tagName.isEmpty()) return@mapNotNullToSet null
            MangaTag(
                key = tagName,
                title = tagName,
                source = source
            )
        }
        
        val ratingText = doc.selectFirst("span:has(i.fa-star)")?.text()
        val rating = ratingText?.replace(Regex("[^0-9.]"), "")?.toFloatOrNull()?.div(10) ?: manga.rating
        
        val chapters = parseChapters(manga.url, doc)
        
        return manga.copy(
            title = title,
            description = description,
            state = state,
            tags = tags,
            rating = rating,
            chapters = chapters,
        )
    }

    /**
     * تنظيف الوثيقة من العناصر المزعجة
     */
    private fun cleanDocument(doc: org.jsoup.nodes.Document) {
        // إزالة إشعارات مانع الإعلانات
        doc.select("[class*='adblock'], [id*='adblock'], [class*='ad-block'], [id*='ad-block']").remove()
        
        // إزالة الإشعارات والتنبيهات
        doc.select(".alert, .notice, .warning, .notification").remove()
        
        // إزالة overlays و modals
        doc.select(".overlay, .modal, .popup, [class*='overlay'], [id*='overlay']").remove()
        
        // إزالة رسائل التحذير الشائعة
        doc.select("div:contains(مانع الإعلانات), div:contains(ad blocker), div:contains(AdBlock)").remove()
        doc.select("div:contains(قم بتعطيل), div:contains(Please disable), div:contains(turn off)").remove()
        
        // إزالة الإعلانات
        doc.select(".ad, .ads, .advertisement, [class*='ad-'], [id*='ad-']").remove()
        
        // إزالة scripts غير ضرورية
        doc.select("script:not([src])").remove()
    }

    /**
     * استخراج وصف نظيف بدون رسائل مزعجة
     */
    private fun extractCleanDescription(doc: org.jsoup.nodes.Document): String? {
        val descriptionElement = doc.selectFirst("div.grid p, p.text-gray-300, div.description, div.synopsis")
        
        if (descriptionElement != null) {
            var description = descriptionElement.text().trim()
            
            // إزالة الجمل المتعلقة بمانع الإعلانات والرسائل المزعجة
            val unwantedPhrases = listOf(
                "يرجى تعطيل مانع الإعلانات",
                "قم بإيقاف مانع الإعلانات",
                "Please disable",
                "AdBlock",
                "ad blocker",
                "turn off your ad blocker",
                "disable your adblocker",
                "يبدو أنك تستخدم",
                "نرجو منك",
                "للمتابعة",
                "to continue"
            )
            
            for (phrase in unwantedPhrases) {
                // إزالة الجملة الكاملة التي تحتوي على العبارة
                val regex = Regex("[^.!?]*$phrase[^.!?]*[.!?]?", RegexOption.IGNORE_CASE)
                description = description.replace(regex, "")
            }
            
            // تنظيف المسافات الزائدة
            description = description.replace(Regex("\\s+"), " ").trim()
            
            return if (description.isNotEmpty()) description else null
        }
        
        return null
    }

    private fun parseChapters(mangaSlug: String, doc: Document): List<MangaChapter> {
        val scriptContent = doc.select("astro-island[component-url*='MangaChaptersLoader']")
            .attr("props")
        
        if (scriptContent.isEmpty()) {
            return parseChaptersFromHtml(doc)
        }
        
        return try {
            val chapters = mutableListOf<MangaChapter>()
            
            // استخراج الفصول من JSON
            val chapterPattern = """"chapter_number"\s*:\s*\[0,\s*"([^"]+)"\]""".toRegex()
            val chapterMatches = chapterPattern.findAll(scriptContent)
            
            chapterMatches.forEach { match ->
                val chapterNum = match.groupValues[1]
                
                chapters.add(
                    MangaChapter(
                        id = generateUid("$mangaSlug-$chapterNum"),
                        title = "الفصل $chapterNum",
                        number = chapterNum.toFloatOrNull() ?: 0f,
                        volume = 0,
                        url = "/reader/$mangaSlug/$chapterNum",
                        uploadDate = 0L,
                        source = source,
                        scanlator = null,
                        branch = null,
                    )
                )
            }
            
            chapters.reversed()
        } catch (e: Exception) {
            parseChaptersFromHtml(doc)
        }
    }

    /**
     * Fallback: تحليل الفصول من HTML مباشرة
     */
    private fun parseChaptersFromHtml(doc: org.jsoup.nodes.Document): List<MangaChapter> {
        return doc.select("div.manga-chapter a, div.grid a[href^='/reader/']").mapNotNull { element ->
            val chapterUrl = element.attr("href")
            if (chapterUrl.isEmpty()) return@mapNotNull null
            
            val chapterTitle = element.selectFirst("h3")?.text() ?: "Chapter"
            val chapterNumber = chapterTitle
                .replace(Regex("[^0-9.]"), "")
                .toFloatOrNull() ?: 0f
            
            val dateText = element.selectFirst("span:has(i.fa-calendar-alt)")?.text()
                ?: element.selectFirst("p.text-sm")?.text()
            
            MangaChapter(
                id = generateUid(chapterUrl),
                title = chapterTitle,
                number = chapterNumber,
                volume = 0,
                url = chapterUrl,
                uploadDate = parseDate(dateText),
                source = source,
                scanlator = null,
                branch = null,
            )
        }.reversed()
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = loadDocument(fullUrl)

        val imageUrls = extractReaderImageUrls(doc, domain)
        val overlayPages = runCatchingCancellable {
            loadOverlayPages(doc)
        }.getOrElse { emptyMap() }

        return imageUrls.mapIndexed { index, imageUrl ->
            MangaPage(
                id = generateUid("${chapter.id}-$index"),
                url = overlayPages[index]?.let { overlay ->
                    buildOverlayPageUrl(imageUrl, overlay)
                } ?: imageUrl,
                preview = null,
                source = source,
            )
        }
    }

    /**
     * MangaTek keeps speech text outside the page image. The browser unlocks an
     * encrypted JSON overlay and paints it on canvas. Decode the same public
     * payload here and hand each page to the app renderer instead of returning
     * the intentionally text-free WebP file.
     */
    private suspend fun loadOverlayPages(document: Document): Map<Int, JSONObject> {
        val props = document.selectFirst("astro-island[component-url*='ChapterImageViewer']")
            ?.attr("props")
            .orEmpty()
        if (!OVERLAY_MODE.containsMatchIn(props)) return emptyMap()

        val token = UNLOCK_TOKEN.find(props)?.groupValues?.get(1) ?: return emptyMap()
        val chapterId = CHAPTER_ID.find(props)?.groupValues?.get(1)?.toLongOrNull() ?: return emptyMap()
        val payload = JSONObject()
            .put("chapterId", chapterId)
            .put("token", token)
            .put("proof", computeUnlockProof(token, chapterId))
        val headers = Headers.Builder()
            .add("Accept", "application/json")
            .add("Referer", "https://$domain/")
            .build()
        val response = webClient.httpPost(OVERLAY_UNLOCK_URL.toHttpUrl(), payload, headers).parseJson()
        if (!response.optBoolean("success")) return emptyMap()

        val overlay = response.optString("overlay")
        val key = response.optString("key")
        if (overlay.isEmpty() || key.isEmpty()) return emptyMap()
        val pages = decryptOverlayPages(overlay, key)
        val pageOffset = response.optInt("overlay_page_offset", 0)
        return buildMap(pages.length()) {
            repeat(pages.length()) { position ->
                val page = pages.optJSONObject(position) ?: return@repeat
                val imageIndex = page.optInt("page_number", position + 1) + pageOffset - 1
                if (imageIndex >= 0 && (page.optJSONArray("overlays")?.length() ?: 0) > 0) {
                    put(imageIndex, page)
                }
            }
        }
    }

    /**
     * MangaTek currently ships the catalog, chapter list and reader images in
     * the initial HTML. Prefer the lightweight HTTP path so opening the source
     * does not wait for a WebView; keep WebView only for challenged networks.
     */
    private suspend fun loadDocument(url: String): Document {
        val directResult = runCatchingCancellable {
            webClient.httpGet(url, siteHeaders("https://$domain/")).parseHtml()
        }
        directResult.getOrNull()?.takeUnless(::isCaptchaPage)?.let { return it }

        val webViewResult = runCatchingCancellable {
            val rawResult = loaderContext.evaluateJs(
                url,
                """
                (function() {
                  if (document.readyState === 'loading') return null;
                  return document.documentElement ? document.documentElement.outerHTML : null;
                })()
                """.trimIndent(),
            ) ?: return@runCatchingCancellable null
            decodeWebViewString(rawResult)?.let { Jsoup.parse(it, url) }
        }
        webViewResult.getOrNull()?.takeUnless(::isCaptchaPage)?.let { return it }

        directResult.exceptionOrNull()?.let { throw it }
        webViewResult.exceptionOrNull()?.let { throw it }
        error("MangaTek returned a CAPTCHA page instead of public content")
    }

    internal fun decodeWebViewString(rawResult: String): String? = runCatching {
        JSONObject("{\"value\":$rawResult}").optString("value").trim().takeIf(String::isNotEmpty)
    }.getOrNull()

    internal fun isCaptchaPage(document: Document): Boolean {
        val text = (document.title() + " " + document.text()).lowercase(Locale.ROOT)
        return CAPTCHA_MARKERS.any(text::contains)
    }

    private fun siteHeaders(referer: String): Headers = Headers.Builder()
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .add("Accept-Language", "ar,en-US;q=0.7,en;q=0.3")
        .add("Referer", referer)
        .add("Upgrade-Insecure-Requests", "1")
        .add("User-Agent", config[userAgentKey])
        .build()

    private fun parseDate(dateText: String?): Long {
        if (dateText.isNullOrEmpty()) return 0L
        
        return try {
            val formats = listOf(
                SimpleDateFormat("dd/MM/yyyy", Locale.US),
                SimpleDateFormat("yyyy-MM-dd", Locale.US),
            )
            
            for (format in formats) {
                try {
                    return format.parse(dateText)?.time ?: 0L
                } catch (_: Exception) {
                    continue
                }
            }
            0L
        } catch (e: Exception) {
            0L
        }
    }

    internal companion object {
        private const val OVERLAY_UNLOCK_URL = "https://api.mangatek.com/api/reader/unlock"
        private const val OVERLAY_PAGE_SCHEME = "mangatek-overlay://render"
        private const val READER_PROOF_SALT = "322c4e08571941fa05abf1a6a2b45c9a9bf7bcc94af61b66"
        private val OVERLAY_MODE = Regex(""""textMode"\s*:\s*\[0,\s*"overlay"\]""")
        private val UNLOCK_TOKEN = Regex(""""unlockToken"\s*:\s*\[0,\s*"([^"]+)"\]""")
        private val CHAPTER_ID = Regex(""""chapterId"\s*:\s*\[0,\s*(\d+)\]""")

        internal fun extractReaderImageUrls(document: Document, domain: String): List<String> = document
            .select("div.manga-page img[data-url], div.manga-page img[data-src], div.manga-page img[src]")
            .mapNotNull { image ->
                sequenceOf("data-url", "data-src", "src")
                    .map { image.attr(it).trim() }
                    .firstOrNull { it.isNotEmpty() && !it.startsWith("data:", ignoreCase = true) }
                    ?.toAbsoluteUrl(domain)
            }
            .distinct()

        internal fun computeUnlockProof(token: String, chapterId: Long): String {
            val value = "$READER_PROOF_SALT|$token|$chapterId"
            return MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }

        internal fun decryptOverlayPages(encrypted: String, keyHex: String) = encrypted
            .split(':')
            .also { require(it.size == 3) { "Invalid MangaTek overlay payload" } }
            .let { parts ->
                val iv = parts[0].decodeHex()
                val ciphertext = parts[1].decodeHex()
                val authTag = parts[2].decodeHex()
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(keyHex.decodeHex(), "AES"),
                    GCMParameterSpec(authTag.size * 8, iv),
                )
                val decrypted = cipher.doFinal(ciphertext + authTag)
                JSONObject(String(decrypted, Charsets.UTF_8)).getJSONArray("pages")
            }

        internal fun buildOverlayPageUrl(imageUrl: String, overlay: JSONObject): String {
            val encoder = Base64.getUrlEncoder().withoutPadding()
            val encodedImage = encoder.encodeToString(imageUrl.toByteArray(Charsets.UTF_8))
            val encodedOverlay = encoder.encodeToString(overlay.toString().toByteArray(Charsets.UTF_8))
            return "$OVERLAY_PAGE_SCHEME?image=$encodedImage&overlay=$encodedOverlay"
        }

        private fun String.decodeHex(): ByteArray {
            require(length % 2 == 0) { "Hex value must have an even length" }
            return ByteArray(length / 2) { index ->
                substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }

        private val CAPTCHA_MARKERS = listOf(
            "captcha",
            "cf-turnstile",
            "verify you are human",
            "التحقق من أنك إنسان",
            "مطلوب التحقق",
        )
    }
}
