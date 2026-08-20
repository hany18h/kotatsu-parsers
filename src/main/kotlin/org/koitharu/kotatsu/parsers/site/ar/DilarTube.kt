package org.koitharu.kotatsu.parsers.site.ar

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ContentUnavailableException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@MangaSourceParser("DILARTUBE", "Dilar Tube", "ar", ContentType.MANGA)
internal class DilarTube(private val loaderContext: MangaLoaderContext) :
    PagedMangaParser(loaderContext, MangaParserSource.DILARTUBE, 24) {

    override val configKeyDomain = ConfigKey.Domain("dilar.tube")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
        keys.add(ConfigKey.InterceptCloudflare(true))
    }

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
        )

    override val availableSortOrders: Set<SortOrder> = setOf(SortOrder.RELEVANCE)

    override suspend fun getFilterOptions() = MangaListFilterOptions()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val hasSearch = !filter.query.isNullOrBlank()

        if (!hasSearch) {
            val url = "https://$domain/api/series/?page=$page"
            val response = webClient.httpGet(url, apiHeaders()).parseJson()
            val series = response.getJSONArray("series")
            return (0 until series.length()).map { i ->
                parseMangaFromJson(series.getJSONObject(i))
            }
        }

        // Search: use quick_search — response is a JSONArray of categories
        val url = "https://$domain/api/search/quick_search"
        val jsonBody = JSONObject().apply {
            put("query", filter.query)
            put("includes", JSONArray().apply {
                put("Manga")
                put("Team")
                put("Member")
            })
        }

        val response = webClient.httpPost(url.toHttpUrl(), jsonBody, apiHeaders()).parseJsonArray()
        for (i in 0 until response.length()) {
            val category = response.getJSONObject(i)
            if (category.optString("class") == "Manga") {
                val data = category.getJSONArray("data")
                return (0 until data.length()).map { j ->
                    parseMangaFromJson(data.getJSONObject(j))
                }
            }
        }
        return emptyList()
    }

    private fun parseMangaFromJson(json: JSONObject): Manga {
        val id = json.getString("id")
        val title = json.getString("title")
        val cover = json.optString("cover", "")
        val summary = json.optString("summary", "")

        // Build cover URL - rollback to original working structure
        val coverUrl = if (cover.isNotEmpty()) {
            if (cover.startsWith("http")) {
                cover
            } else {
                val coverName = cover.substringBeforeLast('.') + ".webp"
                "https://$domain/uploads/manga/cover/$id/large_$coverName"
            }
        } else ""

        val rating = json.optString("rating", "0.00").toFloatOrNull()?.div(5f) ?: RATING_UNKNOWN

        // Get alternative titles from synonyms
        val synonyms = json.optJSONObject("synonyms")
        val altTitles = mutableSetOf<String>()
        synonyms?.let { syn ->
            arrayOf("arabic", "english", "japanese", "alternative").forEach { key ->
                val values = syn.optJSONArray(key)
                if (values != null) {
                    for (i in 0 until values.length()) {
                        values.optString(i).trim().takeIf(String::isNotEmpty)?.let(altTitles::add)
                    }
                } else {
                    syn.optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }?.let(altTitles::add)
                }
            }
        }

        val status = json.optString("story_status", "")
        val state = when (status.lowercase()) {
            "completed" -> MangaState.FINISHED
            "ongoing" -> MangaState.ONGOING
            "hiatus" -> MangaState.PAUSED
            else -> null
        }

        return Manga(
            id = generateUid(id),
            title = title,
            url = "/series/$id",
            publicUrl = "https://$domain/series/$id",
            coverUrl = coverUrl,
            source = source,
            rating = rating,
            altTitles = altTitles,
            contentRating = ContentRating.SAFE,
            tags = emptySet(),
            state = state,
            authors = emptySet(),
            largeCoverUrl = null,
            description = summary.takeIf { it.isNotEmpty() },
            chapters = null,
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val id = manga.url.substringAfterLast("/")
        val url = "https://$domain/api/series/$id"
        val json = webClient.httpGet(url, apiHeaders()).parseJson()

        val title = json.getString("title")
        val summary = json.optString("summary").nullIfEmpty()
        
        val cover = json.optString("cover").nullIfEmpty()
        val coverUrl = if (cover != null) {
            val coverName = cover.substringBeforeLast('.') + ".webp"
            "https://$domain/uploads/manga/cover/$id/large_$coverName"
        } else manga.coverUrl

        val statusStr = json.optString("story_status")
        val state = when (statusStr?.lowercase()) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            "hiatus" -> MangaState.ONGOING
            else -> null
        }

        val authors = mutableSetOf<String>()
        json.optJSONObject("creator")?.let {
            authors.add(it.getString("nick"))
        }

        val tags = mutableSetOf<MangaTag>()
        val categories = json.optJSONArray("categories")
        if (categories != null) {
            for (i in 0 until categories.length()) {
                val cat = categories.getJSONObject(i)
                tags.add(MangaTag(
                    key = cat.getInt("id").toString(),
                    title = cat.getString("name"),
                    source = source
                ))
            }
        }

        return manga.copy(
            title = title,
            description = summary,
            coverUrl = coverUrl,
            state = state,
            authors = authors,
            tags = tags,
            chapters = getChapters(id),
        )
    }

    private suspend fun getChapters(seriesId: String): List<MangaChapter> {
        val url = "https://$domain/api/series/$seriesId/chapters"
        val response = webClient.httpGet(url, apiHeaders()).parseJson()
        val chaptersJson = response.getJSONArray("chapters")
        val chapters = mutableListOf<MangaChapter>()

        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

        for (i in 0 until chaptersJson.length()) {
            val item = chaptersJson.getJSONObject(i)
            val releases = item.optJSONArray("releases") ?: continue
            if (releases.length() == 0) continue

            val release = releases.getJSONObject(0)
            val releaseId = release.getString("id")
            
            val chapterNum = item.optString("chapter").toFloatOrNull() ?: 0f
            val volNum = item.optString("volume").toIntOrNull() ?: 0
            val title = item.optString("title").nullIfEmpty() ?: ""
            
            val dateStr = item.optString("created_at")
            val date = try {
                dateFormat.parse(dateStr)?.time ?: 0L
            } catch (e: Exception) {
                0L
            }

            chapters.add(
                MangaChapter(
                    id = generateUid(releaseId),
                    title = title,
                    number = chapterNum,
                    volume = volNum,
                    url = "/api/chapters/$releaseId",
                    uploadDate = date,
                    source = source,
                    scanlator = null,
                    branch = null
                )
            )
        }
        return chapters.reversed()
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val id = chapter.url.substringAfterLast("/")
        val url = "https://$domain/api/chapters/$id"
        var json = loadEncryptedJson(url)
        if (json.optBoolean("free_pass_required") && json.optJSONArray("pages")?.length() == 0) {
            val clientToken = readClientToken()
                ?: loaderContext.requestBrowserAction(this, "https://$domain/r/$id")
            val freePass = requestFreePass(id, clientToken)
            json = loadEncryptedJson(url, clientToken, freePass)
            json.optJSONObject("assets_enc")?.let { encryptedAssets ->
                val mediaToken = json.optString("media_token").nullIfEmpty()
                    ?: throw ContentUnavailableException("Dilar did not provide a media token")
                mergeJson(json, decryptMediaPayload(encryptedAssets, freePass, mediaToken))
                json.remove("assets_enc")
            }
        }
        val pagesJson = json.getJSONArray("pages")
        if (pagesJson.length() == 0) {
            throw ContentUnavailableException(
                "Dilar requires its browser verification before this chapter can be opened",
            )
        }
        val storageKey = json.optString("storage_key").nullIfEmpty()
        val mediaToken = json.optString("media_token").nullIfEmpty()
        val teamId = json.optString("init_team_id").nullIfEmpty()
            ?: json.optJSONArray("teams")?.optJSONObject(0)?.optString("id")?.nullIfEmpty()

        return (0 until pagesJson.length()).map { i ->
            val page = pagesJson.getJSONObject(i)
            val imageUrl = page.getString("url")
            
            val fullUrl = if (imageUrl.startsWith("http")) {
                imageUrl
            } else {
                if (storageKey != null) {
                    val resolvedStorageKey = if ('/' in storageKey || teamId == null) {
                        storageKey
                    } else {
                        "$teamId/$storageKey"
                    }
                    val qualityDirectory = page.optString("dir", "hq").ifBlank { "hq" }
                    "https://$domain/uploads/releases/$resolvedStorageKey/$qualityDirectory/$imageUrl"
                } else {
                    "https://$domain/uploads/$imageUrl"
                }
            }

            val authorizedUrl = if (mediaToken != null && fullUrl.startsWith("https://$domain/uploads/releases/")) {
                fullUrl.toHttpUrl().newBuilder().addQueryParameter("t", mediaToken).build().toString()
            } else {
                fullUrl
            }

            MangaPage(
                id = generateUid("$id-$i"),
                url = authorizedUrl,
                preview = null,
                source = source
            )
        }
    }

    /**
     * Dilar encrypts chapter responses with an ephemeral P-256 ECDH key. The
     * browser sends its raw public key in X-DH-Pub, then opens the returned
     * AES-GCM envelope using HKDF-SHA256. Keep this local to the parser so no
     * account, shared secret, or device identifier is involved.
     */
    private suspend fun loadEncryptedJson(
        url: String,
        clientToken: String? = null,
        freePass: String? = null,
    ): JSONObject {
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val publicKey = encodeRawPublicKey(keyPair.public as ECPublicKey)
        val publicKeyHeader = publicKey.toByteString().base64Url().trimEnd('=')
        val envelope = webClient.httpGet(
            url,
            apiHeaders()
                .newBuilder()
                .add("X-DH-Pub", publicKeyHeader)
                .add("X-Crypto-Caps", "1,10")
                .apply {
                    clientToken?.let { add("X-Client-Token", it) }
                    freePass?.let { add("X-Unlock-Free-Chapter", it) }
                }
                .build(),
        ).parseJson()
        if (!envelope.has("epk") || !envelope.has("ct")) return envelope
        return decryptResponseEnvelope(envelope, keyPair, publicKey)
    }

    private fun decryptResponseEnvelope(
        envelope: JSONObject,
        keyPair: KeyPair,
        clientPublicKey: ByteArray,
    ): JSONObject {
        val version = envelope.getInt("v")
        require(version == 1 || version == 10) { "Unsupported Dilar encryption version: $version" }
        val serverPublicKeyRaw = decodeBase64Url(envelope.getString("epk"))
        val clientPublic = keyPair.public as ECPublicKey
        val coordinateSize = (clientPublic.params.curve.field.fieldSize + 7) / 8
        require(serverPublicKeyRaw.size == 1 + coordinateSize * 2 && serverPublicKeyRaw[0] == 4.toByte()) {
            "Invalid Dilar ECDH public key"
        }
        val serverPoint = ECPoint(
            BigInteger(1, serverPublicKeyRaw.copyOfRange(1, 1 + coordinateSize)),
            BigInteger(1, serverPublicKeyRaw.copyOfRange(1 + coordinateSize, serverPublicKeyRaw.size)),
        )
        val serverPublicKey = KeyFactory.getInstance("EC").generatePublic(
            ECPublicKeySpec(serverPoint, clientPublic.params),
        )
        val sharedSecret = KeyAgreement.getInstance("ECDH").run {
            init(keyPair.private)
            doPhase(serverPublicKey, true)
            generateSecret()
        }
        val iv = decodeBase64Url(envelope.getString("iv"))
        val epoch = envelope.getLong("e")
        val (hash, salt, info) = when (version) {
            1 -> Triple(
                "SHA-256",
                clientPublicKey + serverPublicKeyRaw,
                "dilar.response.ecies.v1|$epoch".toByteArray(StandardCharsets.UTF_8),
            )

            10 -> Triple(
                "SHA-512",
                digest(
                    "SHA-512",
                    lengthPrefixed(clientPublicKey, serverPublicKeyRaw, iv),
                ),
                "dilar.response.ecies.v10|$epoch|${digest("SHA-512", iv).toHex().take(24)}"
                    .toByteArray(StandardCharsets.UTF_8),
            )

            else -> error("Unsupported Dilar encryption version: $version")
        }
        val aesKey = hkdf(sharedSecret, salt, info, 32, hash)
        val cipherText = decodeBase64Url(envelope.getString("ct")) + decodeBase64Url(envelope.getString("tag"))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(aesKey, "AES"),
            GCMParameterSpec(128, iv),
        )
        return JSONObject(String(cipher.doFinal(cipherText), StandardCharsets.UTF_8))
    }

    private suspend fun readClientToken(): String? {
        val result = loaderContext.evaluateJs(
            "https://$domain/",
            """
            (function() {
              try {
                var raw = localStorage.getItem('dilar.client.credential');
                if (!raw) return null;
                var value = JSON.parse(raw);
                return value && typeof value.token === 'string' ? value.token : null;
              } catch (e) {
                return null;
              }
            })()
            """.trimIndent(),
        )?.trim()?.takeIf { it != "null" } ?: return null
        return runCatching { JSONObject("{\"value\":$result}").optString("value").nullIfEmpty() }.getOrNull()
    }

    private suspend fun requestFreePass(releaseId: String, clientToken: String): String {
        val headers = apiHeaders().newBuilder()
            .add("X-Client-Token", clientToken)
            .build()
        val response = webClient.httpPost(
            "https://$domain/api/chapters/$releaseId/unlock/free".toHttpUrl(),
            JSONObject(),
            headers,
        ).parseJson()
        return response.optString("token").nullIfEmpty()
            ?: throw ContentUnavailableException("Dilar free-pass verification did not return a token")
    }

    private fun decryptMediaPayload(
        envelope: JSONObject,
        freePass: String,
        mediaToken: String,
    ): JSONObject {
        require(envelope.getInt("v") == 1) { "Unsupported Dilar media encryption version" }
        val salt = decodeBase64Url(envelope.getString("s"))
        val epoch = envelope.getLong("e")
        val key = hkdf(
            "$freePass|$mediaToken".toByteArray(StandardCharsets.UTF_8),
            salt,
            "dilar.media.payload.v1|$epoch".toByteArray(StandardCharsets.UTF_8),
            32,
            "SHA-256",
        )
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, decodeBase64Url(envelope.getString("iv"))),
        )
        val encrypted = decodeBase64Url(envelope.getString("ct")) +
            decodeBase64Url(envelope.getString("tag"))
        return JSONObject(String(cipher.doFinal(encrypted), StandardCharsets.UTF_8))
    }

    private fun mergeJson(destination: JSONObject, source: JSONObject) {
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            destination.put(key, source.get(key))
        }
    }

    private fun apiHeaders(): Headers = Headers.Builder()
        .add("Accept", "application/json")
        .add("Origin", "https://$domain")
        .add("Referer", "https://$domain/")
        .add("User-Agent", config[userAgentKey])
        .build()

    private fun encodeRawPublicKey(publicKey: ECPublicKey): ByteArray {
        val coordinateSize = (publicKey.params.curve.field.fieldSize + 7) / 8
        return byteArrayOf(4) +
            publicKey.w.affineX.toUnsignedFixedLength(coordinateSize) +
            publicKey.w.affineY.toUnsignedFixedLength(coordinateSize)
    }

    private fun BigInteger.toUnsignedFixedLength(size: Int): ByteArray {
        val encoded = toByteArray()
        val unsigned = if (encoded.size > 1 && encoded[0] == 0.toByte()) encoded.copyOfRange(1, encoded.size) else encoded
        require(unsigned.size <= size) { "EC coordinate is too large" }
        return ByteArray(size - unsigned.size) + unsigned
    }

    private fun decodeBase64Url(value: String): ByteArray =
        value.decodeBase64()?.toByteArray() ?: error("Invalid base64url value")

    private fun lengthPrefixed(vararg values: ByteArray): ByteArray {
        val output = ByteArray(values.sumOf { it.size + 2 })
        var offset = 0
        values.forEach { value ->
            require(value.size <= 0xffff) { "Dilar crypto value is too large" }
            output[offset++] = (value.size ushr 8).toByte()
            output[offset++] = value.size.toByte()
            value.copyInto(output, offset)
            offset += value.size
        }
        return output
    }

    private fun digest(algorithm: String, value: ByteArray): ByteArray =
        MessageDigest.getInstance(algorithm).digest(value)

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
    }

    private fun hkdf(
        inputKey: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        outputLength: Int,
        hash: String,
    ): ByteArray {
        val hmacAlgorithm = "Hmac${hash.replace("-", "")}"
        val hmac = Mac.getInstance(hmacAlgorithm)
        hmac.init(SecretKeySpec(salt, hmacAlgorithm))
        val pseudoRandomKey = hmac.doFinal(inputKey)
        val result = ByteArray(outputLength)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < outputLength) {
            hmac.init(SecretKeySpec(pseudoRandomKey, hmacAlgorithm))
            hmac.update(previous)
            hmac.update(info)
            hmac.update(counter.toByte())
            previous = hmac.doFinal()
            val count = minOf(previous.size, outputLength - offset)
            previous.copyInto(result, offset, 0, count)
            offset += count
            counter++
        }
        return result
    }
}
