// أضف هذه الدوال إلى class Dilar

override suspend fun search(query: String, page: Int, filter: MangaListFilter): List<Manga> {
    val url = "https://$domain/api/mangas/search"
    
    val body = mapOf(
        "oneshot" to mapOf("value" to false),
        "title" to query,
        "page" to page,
        "manga_types" to mapOf(
            "include" to emptyList<Int>(),
            "exclude" to emptyList<Int>()
        ),
        "story_status" to mapOf(
            "include" to emptyList<Int>(),
            "exclude" to emptyList<Int>()
        ),
        "translation_status" to mapOf(
            "include" to emptyList<Int>(),
            "exclude" to emptyList<Int>()
        ),
        "categories" to mapOf(
            "include" to emptyList<Int>(),
            "exclude" to emptyList<Int>()
        ),
        "chapters" to mapOf(
            "min" to "",
            "max" to ""
        ),
        "dates" to mapOf(
            "start" to "",
            "end" to ""
        )
    )
    
    return try {
        val response = webClient.httpPost(
            url,
            body = body.toJsonString().toByteArray(),
            headers = mapOf("Content-Type" to "application/json")
        ).parseJson()
        
        // فك التشفير
        val encryptedData = response.optString("data", "")
        if (encryptedData.isBlank()) {
            return emptyList()
        }
        
        val decryptedJson = decrypt(encryptedData).let { JSONObject(it) }
        
        val mangas = mutableListOf<Manga>()
        
        val mangasArray = decryptedJson.optJSONArray("mangas") ?: JSONArray()
        
        for (i in 0 until mangasArray.length()) {
            val item = mangasArray.optJSONObject(i) ?: continue
            
            // تجاهل الروايات
            if (item.optBoolean("is_novel", false)) continue
            
            val mangaId = item.optInt("id", 0)
            if (mangaId == 0) continue
            
            val relUrl = "/api/mangas/$mangaId"
            mangas.add(
                Manga(
                    id = generateUid(relUrl),
                    url = relUrl,
                    publicUrl = "https://$domain/mangas/$mangaId",
                    title = item.optString("title", "Unknown"),
                    altTitles = emptySet(),
                    coverUrl = item.optString("cover", "")
                        .let { cover ->
                            if (cover.startsWith("http")) cover
                            else "https://$domain/uploads/manga/cover/$mangaId/$cover"
                        },
                    rating = item.optString("rating", "0")
                        .toFloatOrNull()?.div(10) ?: RATING_UNKNOWN,
                    tags = emptySet(),
                    authors = emptySet(),
                    state = null,
                    source = source,
                    contentRating = ContentRating.SAFE,
                )
            )
        }
        
        mangas
    } catch (e: Exception) {
        throw Exception("Search failed: ${e.message}", e)
    }
}

// ============ دوال فك التشفير ============

private fun decrypt(responseData: String): String {
    try {
        val enc = responseData.split("|")
        if (enc.size < 4) {
            throw Exception("Invalid encrypted data format")
        }
        
        val secretKey = enc[3].sha256().hexStringToByteArray()
        return enc[0].aesDecrypt(secretKey, enc[2])
    } catch (e: Exception) {
        throw Exception("Decryption failed: ${e.message}", e)
    }
}

private fun String.hexStringToByteArray(): ByteArray {
    val len = length
    val data = ByteArray(len / 2)
    var i = 0
    while (i < len) {
        data[i / 2] = (
                (Character.digit(this[i], 16) shl 4) +
                        Character.digit(this[i + 1], 16)
                ).toByte()
        i += 2
    }
    return data
}

private fun String.sha256(): String {
    return MessageDigest
        .getInstance("SHA-256")
        .digest(toByteArray())
        .fold("") { str, it -> str + "%02x".format(it) }
}

private fun String.aesDecrypt(secretKey: ByteArray, ivString: String): String {
    try {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val sk = SecretKeySpec(secretKey, 0, secretKey.size, "AES")
        
        // فك تشفير IV من Base64
        val iv = IvParameterSpec(
            Base64.getDecoder().decode(ivString.toByteArray(Charsets.UTF_8))
        )
        
        c.init(Cipher.DECRYPT_MODE, sk, iv)
        val byteStr = Base64.getDecoder().decode(toByteArray(Charsets.UTF_8))
        return String(c.doFinal(byteStr))
    } catch (e: Exception) {
        throw Exception("AES decryption failed: ${e.message}", e)
    }
}
