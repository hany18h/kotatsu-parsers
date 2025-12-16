// ✅ أضف هذه الدالة إلى LavatoonsRepositoryv2

override suspend fun getChapterPages(chapterUrl: String): Flow<State<List<ChapterPage>>> {
    return fetchDataWithHeaders({ api.get(chapterUrl, defaultHeaders) }) { html ->
        extractChapterPages(html, chapterUrl)
    }
}

private fun extractChapterPages(html: String, chapterUrl: String): List<ChapterPage> {
    val doc = Jsoup.parse(html)

    // 1️⃣ محاولة استخراج من ts_reader.run() - JSON format
    val scriptWithJson = doc.select("script")
        .map { it.html() }
        .firstOrNull { it.contains("ts_reader.run") || it.contains("ts_reader") }

    if (scriptWithJson != null) {
        try {
            // استخراج JSON من السكربت
            val jsonText = when {
                scriptWithJson.contains("ts_reader.run(") -> 
                    scriptWithJson
                        .substringAfter("ts_reader.run(")
                        .substringBeforeLast(");")
                        .trim()
                
                scriptWithJson.trim().startsWith("{") && scriptWithJson.trim().endsWith("}") ->
                    scriptWithJson.trim()
                
                else -> null
            }

            if (jsonText != null && jsonText.startsWith("{") && jsonText.endsWith("}")) {
                val root = JSONObject(jsonText)
                val sources = root.optJSONArray("sources")
                
                if (sources != null && sources.length() > 0) {
                    val imagesArray = sources.getJSONObject(0).optJSONArray("images")
                    
                    if (imagesArray != null) {
                        return (0 until imagesArray.length()).mapNotNull { idx ->
                            val imageUrl = imagesArray.optString(idx).takeIf { it.isNotBlank() }
                                ?: return@mapNotNull null
                            
                            ChapterPage(
                                url = imageUrl,
                                imageUrl = imageUrl,
                                number = idx + 1
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Log error if needed
            e.printStackTrace()
        }
    }

    // 2️⃣ Fallback: استخراج الصور العادية من div#readerarea
    val normalImages = doc.select("div#readerarea img, div.reading-content img")
        .mapIndexedNotNull { index, img ->
            val src = img.attr("src").takeIf { it.isNotBlank() } 
                ?: img.attr("data-src").takeIf { it.isNotBlank() }
                ?: return@mapIndexedNotNull null
            
            ChapterPage(
                url = src,
                imageUrl = src,
                number = index + 1
            )
        }

    if (normalImages.isNotEmpty()) {
        return normalImages
    }

    // 3️⃣ إذا فشل كل شيء، ارجع قائمة فارغة
    return emptyList()
}

// ✅ تأكد من أن getChapterImages ترجع الصور بشكل صحيح
override fun getChapterImages(html: String): List<String> {
    val doc = Jsoup.parse(html)

    // محاولة 1: ts_reader.run
    val scriptWithJson = doc.select("script")
        .map { it.html() }
        .firstOrNull { it.contains("ts_reader.run") || it.contains("ts_reader") }

    if (scriptWithJson != null) {
        try {
            val jsonText = when {
                scriptWithJson.contains("ts_reader.run(") -> 
                    scriptWithJson
                        .substringAfter("ts_reader.run(")
                        .substringBeforeLast(");")
                        .trim()
                
                scriptWithJson.trim().startsWith("{") && scriptWithJson.trim().endsWith("}") ->
                    scriptWithJson.trim()
                
                else -> null
            }?.takeIf { it.startsWith("{") && it.endsWith("}") }

            if (jsonText != null) {
                val root = JSONObject(jsonText)
                val sources = root.optJSONArray("sources")
                if (sources != null && sources.length() > 0) {
                    val imagesArray = sources.getJSONObject(0).optJSONArray("images")
                    if (imagesArray != null) {
                        return (0 until imagesArray.length())
                            .mapNotNull { idx -> 
                                imagesArray.optString(idx).takeIf { it.isNotBlank() } 
                            }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // محاولة 2: صور عادية
    return doc.select("div#readerarea img, div.reading-content img")
        .mapNotNull { 
            it.attr("src").takeIf { url -> url.isNotBlank() }
                ?: it.attr("data-src").takeIf { url -> url.isNotBlank() }
        }
}
