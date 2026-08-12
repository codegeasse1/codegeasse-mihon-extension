package eu.kanade.tachiyomi.extension.en.comix

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import okio.Buffer
import java.util.Calendar

/**
 * Comix (comix.to)
 */
class Comix : HttpSource() {

    override val name = "Codegeasse Comix"

    override val baseUrl = "https://comix.to"

    override val lang = "en"

    override val supportsLatest = true

    private val SCRAMBLE_PATH_FALLBACK_REGEX = Regex("/(?:i5|s?i+)/")

    // Attach the Descrambler and CDN Fallback interceptors
    override val client = network.client.newBuilder()
        .addInterceptor(Descrambler.interceptor)
        .addInterceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            if (response.code != 404) return@addInterceptor response

            val url = request.url.toString()
            val fallbacks = listOf("/i5/", "/si/", "/i/", "/sii/", "/ii/")
                .map { url.replaceFirst(SCRAMBLE_PATH_FALLBACK_REGEX, it) }
                .filter { it != url }

            if (fallbacks.isEmpty()) return@addInterceptor response

            var lastResponse = response
            for (fallbackUrl in fallbacks) {
                lastResponse.close()
                lastResponse = chain.proceed(request.newBuilder().url(fallbackUrl).build())
                if (lastResponse.code != 404) break
            }
            lastResponse
        }
        .build()

    // Rely on Mihon's default User-Agent to sync perfectly with the WebView
    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Origin", baseUrl)
        .add("Referer", "$baseUrl/")

    // ---- Shared: Pull embedded React-Query cache out of a page -------

    private fun queries(document: Document): JSONObject {
        val script = document.selectFirst("script#initial-data")
            ?: return JSONObject()
            
        val root = try { JSONObject(script.data()) } catch (e: Exception) { JSONObject() }
        return root.optJSONObject("queries") ?: root
    }

    private fun findQuery(queries: JSONObject, vararg mustContain: String): Any? {
        for (key in queries.keys()) {
            if (mustContain.all { key.contains(it, ignoreCase = true) }) return queries.get(key)
        }
        return null
    }

    private fun extractItems(value: Any?): JSONArray? {
        if (value is JSONArray) return value
        if (value is JSONObject) {
            val target = if (value.has("result")) value.optJSONObject("result") ?: value else value
            if (target.has("pages")) {
                val pagesArr = target.optJSONArray("pages")
                if (pagesArr != null && pagesArr.length() > 0) {
                    val combined = JSONArray()
                    for (i in 0 until pagesArr.length()) {
                        val pObj = pagesArr.get(i)
                        val pItems = extractItems(pObj)
                        if (pItems != null) {
                            for (j in 0 until pItems.length()) combined.put(pItems.get(j))
                        }
                    }
                    if (combined.length() > 0) return combined
                }
            }
            return target.optJSONArray("items") ?: target.optJSONArray("data") ?: target.optJSONArray("chapters") ?: target.optJSONArray("list")
        }
        return null
    }

    private fun asMangaArray(value: Any?): JSONArray = extractItems(value) ?: JSONArray()

    private fun JSONObject.toSManga(): SManga = SManga.create().apply {
        url = this@toSManga.optString("url").ifBlank { "/title/${this@toSManga.optString("hid")}" }
        title = this@toSManga.optString("title")
        thumbnail_url = this@toSManga.optJSONObject("poster")?.optString("medium")
        description = this@toSManga.optString("synopsis").takeIf { it.isNotBlank() }
        status = when (this@toSManga.optString("status")) {
            "releasing" -> SManga.ONGOING
            "completed", "finished" -> SManga.COMPLETED
            "on_hiatus" -> SManga.ON_HIATUS
            "discontinued", "cancelled" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    // ---- Popular ----------------------------------------------------------

    override fun popularMangaRequest(page: Int): Request = 
        GET("$baseUrl/?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val q = queries(response.asJsoup())
        val result = findQuery(q, "\"list\"") ?: findQuery(q, "\"manga\"")
        val list = asMangaArray(result)
        val mangas = (0 until list.length()).map { list.getJSONObject(it).toSManga() }
        
        val meta = (result as? JSONObject)?.optJSONObject("meta")
        val hasNextPage = meta?.optBoolean("hasNext", false) ?: (list.length() >= 30)
        return MangasPage(mangas, hasNextPage)
    }

    // ---- Latest -----------------------------------------------------------

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val q = queries(response.asJsoup())
        val result = findQuery(q, "\"list\"") ?: findQuery(q, "\"manga\"")
        val list = asMangaArray(result)
        val mangas = (0 until list.length()).map { list.getJSONObject(it).toSManga() }
        
        val meta = (result as? JSONObject)?.optJSONObject("meta")
        val hasNextPage = meta?.optBoolean("hasNext", false) ?: (list.length() >= 30)
        return MangasPage(mangas, hasNextPage)
    }

    // ---- Search -----------------------------------------------------------

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/search?q=$query&page=$page", headers)

    override fun searchMangaParse(response: Response): MangasPage {
        if (response.code == 404) return MangasPage(emptyList(), false)
        
        val q = queries(response.asJsoup())
        val result = findQuery(q, "\"search\"") ?: findQuery(q, "\"list\"") ?: findQuery(q, "\"manga\"")
        val list = asMangaArray(result)
        val mangas = (0 until list.length()).map { list.getJSONObject(it).toSManga() }
        
        val meta = (result as? JSONObject)?.optJSONObject("meta")
        val hasNextPage = meta?.optBoolean("hasNext", false) ?: (list.length() >= 30)
        return MangasPage(mangas, hasNextPage)
    }

    override fun getFilterList(): FilterList = FilterList()

    // ---- Manga details ----------------------------------------------------

    private fun parseTerms(obj: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            val arr = obj.optJSONArray(key) ?: continue
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i)
                if (item != null && item.has("title")) {
                    list.add(item.optString("title"))
                }
            }
            if (list.isNotEmpty()) return list.joinToString(", ")
        }
        return null
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty().substringBefore(" - ")
            description = document.selectFirst("meta[property=og:description]")?.attr("content")
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")

            try {
                val q = queries(document)
                for (key in q.keys()) {
                    if (key.contains("manga", ignoreCase = true) || key.contains("detail", ignoreCase = true)) {
                        val obj = q.optJSONObject(key) ?: continue
                        val target = obj.optJSONObject("result") ?: obj
                        
                        if (target.has("title")) {
                            author = parseTerms(target, "authors", "author")
                            artist = parseTerms(target, "artists", "artist")
                            genre = parseTerms(target, "genres", "genre", "tags", "theme")
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    // ---- Chapters ---------------------------------------------------------

    private fun isChapterObj(obj: JSONObject): Boolean {
        // Core identifiers of a chapter vs other data structures
        val hasId = obj.has("id") || obj.has("hid")
        val hasNum = obj.has("number") || obj.has("chap") || obj.has("chapter")
        // Rule out manga/tag objects
        val isNotManga = !obj.has("synopsis") && !obj.has("poster") && !obj.has("status") && !obj.has("slug")
        
        return hasId && hasNum && isNotManga
    }

    private fun extractAllChapters(root: JSONObject): JSONArray {
        val combined = JSONArray()

        fun search(element: Any?) {
            when (element) {
                is JSONObject -> {
                    if (isChapterObj(element)) {
                        combined.put(element)
                    } else {
                        element.keys().forEach { k -> search(element.opt(k)) }
                    }
                }
                is JSONArray -> {
                    for (i in 0 until element.length()) {
                        search(element.opt(i))
                    }
                }
            }
        }
        
        search(root)
        return combined
    }

    private fun parseRelativeDate(dateStr: String): Long {
        if (dateStr.isEmpty() || dateStr == "null") return 0L
        val trimmed = dateStr.trim().lowercase().removeSuffix(" ago")
        val match = Regex("""^(\d+)\s*(s|m|h|d|w|mo|mos|y|yr|yrs|min|mins|sec|secs|hr|hrs|day|days|week|weeks|month|months|year|years)$""").find(trimmed) ?: return 0L

        val amount = match.groupValues[1].toIntOrNull() ?: return 0L
        val unit = match.groupValues[2]

        val calendar = Calendar.getInstance()
        when (unit) {
            "s", "sec", "secs" -> calendar.add(Calendar.SECOND, -amount)
            "m", "min", "mins" -> calendar.add(Calendar.MINUTE, -amount)
            "h", "hr", "hrs" -> calendar.add(Calendar.HOUR_OF_DAY, -amount)
            "d", "day", "days" -> calendar.add(Calendar.DAY_OF_YEAR, -amount)
            "w", "week", "weeks" -> calendar.add(Calendar.WEEK_OF_YEAR, -amount)
            "mo", "mos", "month", "months" -> calendar.add(Calendar.MONTH, -amount)
            "y", "yr", "yrs", "year", "years" -> calendar.add(Calendar.YEAR, -amount)
        }
        return calendar.timeInMillis
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val q = queries(document)
        val mangaUrl = response.request.url.encodedPath 
        
        val chaptersArray = extractAllChapters(q)
        val chapters = mutableListOf<SChapter>()
        val seenIds = mutableSetOf<String>()
        
        for (i in 0 until chaptersArray.length()) {
            val obj = chaptersArray.getJSONObject(i)
            val id = obj.optString("id").ifBlank { obj.optString("hid") }
            
            // Prevent duplicates if infiniteQuery paginated arrays overlap
            if (id.isBlank() || id == "null" || !seenIds.add(id)) continue
            
            val number = obj.optDouble("number", -1.0)
            val chapStr = obj.optString("chap").ifBlank { obj.optString("chapter") }
            val nameStr = obj.optString("name", "").trim()
            val titleStr = obj.optString("title", "").trim()
            val urlStr = obj.optString("url", "")
            val dateStr = obj.optString("createdAtFormatted", "")
            
            chapters.add(SChapter.create().apply {
                val numString = if (number >= 0) {
                    if (number % 1.0 == 0.0) number.toInt().toString() else number.toString()
                } else {
                    chapStr
                }
                
                val finalTitle = nameStr.ifBlank { titleStr }
                
                name = buildString {
                    append("Chapter ")
                    if (numString.isNotBlank() && numString != "null") append(numString) else append("?")
                    if (finalTitle.isNotBlank() && finalTitle != "null") {
                        append(": ")
                        append(finalTitle)
                    }
                }
                
                chapter_number = number.toFloat().takeIf { it >= 0 } ?: chapStr.toFloatOrNull() ?: -1f
                date_upload = parseRelativeDate(dateStr)
                
                url = if (urlStr.isNotBlank() && urlStr != "null") {
                    urlStr
                } else {
                    val safeChapNum = if (numString.isNotBlank() && numString != "null") numString else "0"
                    "$mangaUrl/$id-chapter-$safeChapNum"
                }
            })
        }
        
        if (chapters.isEmpty()) {
            val domChapters = document.select("a[href*=-chapter-], a.mchap-row__primary")
            chapters.addAll(domChapters.map { el ->
                SChapter.create().apply {
                    name = el.selectFirst("span.mchap-row__ch")?.text()?.ifBlank { el.text() } ?: el.text().trim()
                    chapter_number = Regex("""[\d.]+""").find(name)?.value?.toFloatOrNull() ?: -1f
                    setUrlWithoutDomain(el.attr("href"))
                }
            })
        }
        
        return chapters.sortedByDescending { it.chapter_number }
    }

    // ---- Pages ------------------------------------------------------------

    override fun imageRequest(page: Page): Request {
        val imageUrl = page.imageUrl ?: return super.imageRequest(page)
        val urlWithoutFragment = imageUrl.substringBefore('#')
        val imageHost = urlWithoutFragment.toHttpUrlOrNull()?.host.orEmpty()
        val isScrambled = imageUrl.contains("#scrambled")
        val isV3 = urlWithoutFragment.toHttpUrlOrNull()?.queryParameterNames?.contains("v3") == true
        val isLegacyScramble = isScrambled && !isV3
        val baseUrlHost = baseUrl.toHttpUrl().host
        val requestHeaders = if (
            imageHost.isNotEmpty() &&
            !imageHost.endsWith(baseUrlHost) &&
            !isLegacyScramble
        ) {
            headersBuilder()
                .removeAll("Origin")
                .build()
        } else {
            headers
        }
        return GET(urlWithoutFragment, requestHeaders)
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val url = chapter.url
        val chapterId = Regex("""/([^/]+)-chapter-""").find(url)?.groupValues?.get(1)
        
        if (chapterId != null && chapterId.matches(Regex("""^\d+$"""))) {
            val apiHeaders = headersBuilder()
                .set("Accept", "application/json, text/plain, */*")
                .set("Referer", baseUrl + url)
                .build()
            return GET("$baseUrl/api/v1/chapters/$chapterId", apiHeaders)
        }
        
        return GET(baseUrl + url, headers)
    }

    private data class PageInfo(val url: String, val s: Int)

    override fun pageListParse(response: Response): List<Page> {
        val bodyStr = response.body?.string() ?: ""
        val contentType = response.header("Content-Type") ?: ""

        val pagesInfo = mutableListOf<PageInfo>()

        // 1. Process the API JSON Response
        if (contentType.contains("json", ignoreCase = true) || bodyStr.trim().startsWith("{")) {
            val root = try { JSONObject(bodyStr) } catch(e: Exception) { JSONObject() }
            val pagesObj = root.optJSONObject("result")?.optJSONObject("pages")
            
            if (pagesObj != null) {
                val baseUrlStr = pagesObj.optString("baseUrl").trimEnd('/')
                val items = pagesObj.optJSONArray("items")
                if (items != null) {
                    for (i in 0 until items.length()) {
                        val img = items.optJSONObject(i) ?: continue
                        val path = img.optString("url").trimStart('/')
                        val s = img.optInt("s", 0)
                        val fullUrl = if (path.startsWith("http")) path else "$baseUrlStr/$path"
                        pagesInfo.add(PageInfo(fullUrl, s))
                    }
                }
            } else {
                pagesInfo.addAll(findImageUrlsRecursively(root))
            }
        }

        // 2. Fallback to HTML parsing
        if (pagesInfo.isEmpty()) {
            val document = org.jsoup.Jsoup.parse(bodyStr, response.request.url.toString())
            val domImgs = document.select("img.rpage-page__img, .page-image img, img.chapter-img")
            if (domImgs.isNotEmpty()) {
                pagesInfo.addAll(domImgs.map { PageInfo(it.attr("abs:src"), 0) })
            } else {
                val script = document.selectFirst("script#initial-data")
                if (script != null) {
                    try {
                        val root = JSONObject(script.data())
                        val queries = root.optJSONObject("queries") ?: root
                        pagesInfo.addAll(findImageUrlsRecursively(queries))
                    } catch (e: Exception) {}
                }
            }
        }
        
        if (pagesInfo.isNotEmpty()) {
            return pagesInfo.mapIndexed { index, info -> 
                val full = info.url
                val isV3 = info.s == 1 || full.contains("?v3")
                val isLegacyScramble = !isV3 && (index + 1) % 4 == 0
                val url = when {
                    isV3 -> {
                        val httpUrl = full.toHttpUrlOrNull()
                        if (httpUrl != null && !httpUrl.queryParameterNames.contains("v3")) {
                            httpUrl.newBuilder().addQueryParameter("v3", null).build().toString()
                        } else {
                            full
                        }
                    }
                    isLegacyScramble -> "$full#scrambled"
                    else -> full
                }
                Page(index, imageUrl = url)
            }
        }
        
        throw Exception("No pages found. Cloudflare verification might be required.")
    }

    private fun findImageUrlsRecursively(obj: Any?): List<PageInfo> {
        if (obj is JSONObject) {
            listOf("images", "pages", "blocks", "chapter_images", "data", "list", "chapter").forEach { arrKey ->
                val arr = obj.optJSONArray(arrKey)
                if (arr != null) {
                    val urls = extractUrlsFromArray(arr, strict = false)
                    if (urls.isNotEmpty()) return urls
                }
            }
            
            for (key in obj.keys()) {
                val found = findImageUrlsRecursively(obj.get(key))
                if (found.isNotEmpty()) return found
            }
        } else if (obj is JSONArray) {
            val stringUrls = extractUrlsFromArray(obj, strict = true)
            if (stringUrls.isNotEmpty()) return stringUrls
            
            for (i in 0 until obj.length()) {
                val found = findImageUrlsRecursively(obj.get(i))
                if (found.isNotEmpty()) return found
            }
        }
        return emptyList()
    }

    private fun extractUrlsFromArray(arr: JSONArray, strict: Boolean): List<PageInfo> {
        val pages = mutableListOf<PageInfo>()
        for (i in 0 until arr.length()) {
            val item = arr.get(i)
            var url = ""
            var s = 0
            if (item is JSONObject) {
                url = item.optString("url").ifBlank { item.optString("src") }.ifBlank { item.optString("image") }
                s = item.optInt("s", 0)
            } else if (item is String) {
                url = item
            }
            
            if (url.startsWith("http")) {
                if (!strict || url.contains("wowpic") || url.contains("comix.to") || url.matches(Regex(".*\\.(jpg|png|webp|avif|jpeg).*"))) {
                    pages.add(PageInfo(url, s))
                }
            }
        }
        return pages
    }

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException("Not used")
}

// ============================== Image Descrambler ==============================

object Descrambler {

    private const val GRID_COLS = 5
    private const val GRID_ROWS = 5
    private const val NUM_TILES = GRID_COLS * GRID_ROWS

    private const val ENC_MULTIPLIER = 1000005
    private const val ENC_INCREMENT = 1234567891
    private const val LCG_MULTIPLIER = 1664525
    private const val LCG_INCREMENT = 1013904223

    private val JPEG_MEDIA = "image/jpeg".toMediaType()

    val interceptor = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        if (!response.isSuccessful) return@Interceptor response

        val rawScrambleSeed = response.header("x-scramble-seed")
        val rawScrambleGrid = response.header("x-scramble-grid")
        val rawScrambleAlgo = response.header("x-scramble-algo")
        val rawScrambleHash = response.header("x-scramble-hash")
        val rawEncSeed = response.header("x-enc-seed")
        val rawEncAlgo = response.header("x-enc-algo")

        val encSeed = rawEncSeed?.toLongOrNull()?.toInt()
        val encLen = response.header("x-enc-len")?.toIntOrNull()
        val scrambleSeed = rawScrambleSeed?.toLongOrNull()?.toInt()
        val scrambleHash = decodeScrambleHash(rawScrambleHash)

        val needsXor = encSeed != null && encSeed != 0 && encLen != null
        val shouldDescrambleGrid = rawScrambleGrid == "5x5" &&
            (rawScrambleAlgo == null || rawScrambleAlgo == "1" || rawScrambleAlgo == "2" || rawScrambleAlgo == "3") &&
            scrambleSeed != null && scrambleSeed != 0

        if (!needsXor && !shouldDescrambleGrid) return@Interceptor response

        val body = response.body ?: return@Interceptor response
        val bodyMediaType = body.contentType()

        val originalBytes = body.bytes()
        val bytes = if (needsXor) {
            decodeEncodedBytes(originalBytes, encSeed, encLen, rawEncAlgo)
        } else {
            originalBytes
        }

        if (shouldDescrambleGrid) {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return@Interceptor response.newBuilder()
                    .code(500)
                    .message("Failed to decode image")
                    .body("Failed to decode image".toResponseBody("text/plain".toMediaType()))
                    .build()

            val descrambled = descramble(bitmap, scrambleSeed xor scrambleHash, rawScrambleAlgo)
            bitmap.recycle()

            val output = Buffer()
            descrambled.compress(Bitmap.CompressFormat.JPEG, 90, output.outputStream())
            descrambled.recycle()

            return@Interceptor response.newBuilder()
                .removeHeader("Content-Length")
                .removeHeader("Content-Type")
                .body(output.asResponseBody(JPEG_MEDIA, output.size))
                .build()
        }

        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (bitmap != null) {
            val output = Buffer()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output.outputStream())
            bitmap.recycle()

            return@Interceptor response.newBuilder()
                .removeHeader("Content-Encoding")
                .header("Content-Type", JPEG_MEDIA.toString())
                .header("Content-Length", output.size.toString())
                .body(output.asResponseBody(JPEG_MEDIA, output.size))
                .build()
        }

        response.newBuilder()
            .removeHeader("Content-Encoding")
            .removeHeader("Content-Length")
            .removeHeader("Content-Type")
            .body(bytes.toResponseBody(bodyMediaType))
            .build()
    }

    private fun decodeEncodedBytes(bytes: ByteArray, seed: Int, length: Int, algo: String?): ByteArray {
        if (algo != "2") {
            return decodeWithLcg(bytes, seed, length)
        }

        val candidates = listOf(
            decodeWithXorshift(bytes, seed or 1, length, false),
            decodeWithXorshift(bytes, seed, length, false),
            decodeWithXorshift(bytes, seed or 1, length, true),
            decodeWithLcg(bytes, seed, length),
        )
        return candidates.firstOrNull { it.hasImageSignature() } ?: candidates.first()
    }

    private fun decodeWithXorshift(bytes: ByteArray, initialState: Int, length: Int, highByte: Boolean): ByteArray {
        val result = bytes.copyOf()
        var state = initialState
        val limit = minOf(result.size, length)
        for (i in 0 until limit) {
            state = nextXorshiftState(state)
            val key = if (highByte) state ushr 24 else state and 0xFF
            result[i] = (result[i].toInt() xor key).toByte()
        }
        return result
    }

    private fun decodeWithLcg(bytes: ByteArray, seed: Int, length: Int): ByteArray {
        val result = bytes.copyOf()
        var state = seed
        val limit = minOf(result.size, length)
        for (i in 0 until limit) {
            state = state * ENC_MULTIPLIER + ENC_INCREMENT
            result[i] = (result[i].toInt() xor (state ushr 24)).toByte()
        }
        return result
    }

    private fun nextXorshiftState(state: Int): Int {
        var next = state
        next = next xor (next shl 13)
        next = next xor (next ushr 17)
        return next xor (next shl 5)
    }

    private fun decodeScrambleHash(hash: String?): Int = when (hash?.trim()) {
        "03632" -> 58414
        "02900" -> 117532
        else -> 0
    }

    private fun ByteArray.hasImageSignature(): Boolean = size >= 12 && (
        (this[0] == 'R'.code.toByte() && this[1] == 'I'.code.toByte() && this[2] == 'F'.code.toByte() &&
            this[3] == 'F'.code.toByte() && this[8] == 'W'.code.toByte() && this[9] == 'E'.code.toByte() &&
            this[10] == 'B'.code.toByte() && this[11] == 'P'.code.toByte()) ||
        (this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte()) ||
        (this[0] == 0x89.toByte() && this[1] == 'P'.code.toByte() && this[2] == 'N'.code.toByte() &&
            this[3] == 'G'.code.toByte())
    )

    private fun descramble(bitmap: Bitmap, seed: Int, algo: String?): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val tileW = width / GRID_COLS
        val tileH = height / GRID_ROWS
        val order = if (algo == "3") buildOrder(seed, NUM_TILES) else buildOrderLcg(seed, NUM_TILES)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        for (dstIdx in 0 until NUM_TILES) {
            val srcIdx = order[dstIdx]
            val srcCol = srcIdx % GRID_COLS
            val srcRow = srcIdx / GRID_COLS
            val dstCol = dstIdx % GRID_COLS
            val dstRow = dstIdx / GRID_COLS
            val srcRect = Rect(srcCol * tileW, srcRow * tileH, (srcCol + 1) * tileW, (srcRow + 1) * tileH)
            val dstRect = Rect(dstCol * tileW, dstRow * tileH, (dstCol + 1) * tileW, (dstRow + 1) * tileH)
            canvas.drawBitmap(bitmap, srcRect, dstRect, null)
        }
        return output
    }

    private fun buildOrder(seed: Int, n: Int): IntArray {
        val arr = IntArray(n) { it }
        var state = seed or 1
        for (i in n - 1 downTo 1) {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            val j = (state.toLong() and 0xFFFFFFFFL) % (i + 1)
            val tmp = arr[i]
            arr[i] = arr[j.toInt()]
            arr[j.toInt()] = tmp
        }
        return IntArray(n).also { inverse ->
            for (i in arr.indices) {
                inverse[arr[i]] = i
            }
        }
    }

    private fun buildOrderLcg(seed: Int, n: Int): IntArray {
        val arr = IntArray(n) { it }
        var state = seed
        for (i in n - 1 downTo 1) {
            state = state * LCG_MULTIPLIER + LCG_INCREMENT
            val j = (state.toLong() and 0xFFFFFFFFL) % (i + 1)
            val tmp = arr[i]
            arr[i] = arr[j.toInt()]
            arr[j.toInt()] = tmp
        }
        return IntArray(n).also { inverse ->
            for (i in arr.indices) {
                inverse[arr[i]] = i
            }
        }
    }
}
