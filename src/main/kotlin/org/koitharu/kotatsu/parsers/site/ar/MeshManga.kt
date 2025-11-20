package org.koitharu.kotatsu.parsers.site.ar

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.*

/**
 * MeshManga (Swat Manga) - موقع مانجا عربي
 * Website: https://meshmanga.com
 */
@MangaSourceParser("MESHMANGA", "MeshManga", "ar")
internal class MeshManga(context: MangaLoaderContext) :
    MangaReaderParser(
        context = context,
        source = MangaParserSource.MESHMANGA,
        domain = "meshmanga.com",
        pageSize = 36,
        searchPageSize = 20
    ) {

    override val listUrl = "/type/manga"
    override val datePattern = "MMMM dd, yyyy"

    // Selectors مخصصة للموقع
    override val selectMangaList = ".postbody .listupd .bs .bsx, .postbody .bs .bsx"
    override val selectMangaListImg = "img.ts-post-image, img"
    override val selectMangaListTitle = "div.tt, .bigor .tt"
    
    override val selectChapter = "#chapterlist ul li, .eplister ul li"
    override val selectPage = "div#readerarea img, .reader-area img"
    override val detailsDescriptionSelector = "div.entry-content, div.summary__content, .entry-content-single"
    
    // دعم ts_reader للصور
    override val selectTestScript = "script:contains(ts_reader), script#ts-reader"

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://")
            append(domain)

            when {
                !filter.query.isNullOrEmpty() -> {
                    // البحث
                    append("/?s=")
                    append(filter.query.urlEncoded())
                    append("&page=")
                    append(page)
                }
                else -> {
                    // قائمة عادية
                    append(listUrl)
                    append("?order=")
                    append(
                        when (order) {
                            SortOrder.ALPHABETICAL -> "title"
                            SortOrder.ALPHABETICAL_DESC -> "titlereverse"
                            SortOrder.NEWEST -> "latest"
                            SortOrder.POPULARITY -> "popular"
                            SortOrder.UPDATED -> "update"
                            else -> "update"
                        }
                    )

                    // Tags
                    filter.tags.forEach {
                        append("&genre[]=")
                        append(it.key)
                    }

                    filter.tagsExclude.forEach {
                        append("&genre[]=-")
                        append(it.key)
                    }

                    // Status
                    filter.states.oneOrThrowIfMany()?.let {
                        append("&status=")
                        when (it) {
                            MangaState.ONGOING -> append("ongoing")
                            MangaState.FINISHED -> append("completed")
                            MangaState.PAUSED -> append("hiatus")
                            else -> {}
                        }
                    }

                    // Type
                    filter.types.oneOrThrowIfMany()?.let {
                        append("&type=")
                        append(
                            when (it) {
                                ContentType.MANGA -> "manga"
                                ContentType.MANHWA -> "manhwa"
                                ContentType.MANHUA -> "manhua"
                                ContentType.COMICS -> "comic"
                                ContentType.NOVEL -> "novel"
                                else -> "manga"
                            }
                        )
                    }

                    append("&page=")
                    append(page)
                }
            }
        }

        return parseMangaList(webClient.httpGet(url).parseHtml())
    }

    override suspend fun parseInfo(docs: Document, manga: Manga, chapters: List<MangaChapter>): Manga {
        // استخدام الـ parent implementation مع بعض التعديلات
        val result = super.parseInfo(docs, manga, chapters)
        
        // إضافة دعم للعناوين البديلة إذا وُجدت
        val altTitles = docs.select(".alternative, .wd-full .alter")
            .firstOrNull()
            ?.text()
            ?.split(",", ";")
            ?.mapNotNull { it.trim().takeIf { t -> t.isNotBlank() } }
            ?.toSet()
            ?: emptySet()

        return if (altTitles.isNotEmpty()) {
            result.copy(altTitles = altTitles)
        } else {
            result
        }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        // استخدام الـ parent implementation
        // تدعم تلقائياً ts_reader والصور العادية
        return super.getPages(chapter)
    }
}
