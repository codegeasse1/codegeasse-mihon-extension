package eu.kanade.tachiyomi.extension.en.asurascans

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonNull
import com.google.gson.JsonParser
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/*
 * Asura Scans (https://asurascans.com) — Astro site with a JSON API.
 *
 *     Browse  : GET api.asurascans.com/api/series?offset=N&limit=20&sort=...
 *               (sort: latest|popular|rating|title|update, order: asc|desc,
 *               plus optional status=/type=/search=) -> {data, meta:{has_more}}
 *     Details : the series page (/comics/<slug>) embeds the details as an Astro
 *               "props" attribute holding a devalue-encoded JSON object
 *               (values wrapped as [0, value] / arrays as [1, [...]]).
 *     Chapters: GET api.asurascans.com/api/series/<slug>/chapters -> {data:[...]}
 *     Pages   : the chapter page embeds a "pages" prop with the image URLs.
 *               Locked/premium chapters instead need the authenticated API
 *               (access_token cookie + X-Page-Token) and their images come as
 *               scrambled tiles (tiles[] permutation of a cols x rows grid)
 *               that this source reassembles in an image response interceptor.
 */
class AsuraScans : HttpSource() {

    override val name = "Asura Scans"
    override val baseUrl = "https://asurascans.com"
    override val lang = "en"
    override val supportsLatest = true

    private val apiUrl = "https://api.asurascans.com/api"

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", BROWSER_UA)
        .set("Referer", "$baseUrl/")

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(::scrambledImageInterceptor)
        .build()

    // =========================== Browse & Search =========================

    override fun popularMangaRequest(page: Int): Request =
        seriesApiRequest(page, "", "popular", "desc")

    override fun popularMangaParse(response: Response): MangasPage = parseSeries(response)

    override fun latestUpdatesRequest(page: Int): Request =
        seriesApiRequest(page, "", "latest", "desc")

    override fun latestUpdatesParse(response: Response): MangasPage = parseSeries(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val sort = filters.filterIsInstance<SortFilter>().firstOrNull()
        val status = filters.filterIsInstance<StatusFilter>().firstOrNull()?.selected.orEmpty()
        val type = filters.filterIsInstance<TypeFilter>().firstOrNull()?.selected.orEmpty()
        return seriesApiRequest(
            page,
            query,
            sort?.selectedSort ?: "latest",
            sort?.selectedOrder ?: "desc",
            status,
            type,
        )
    }

    override fun searchMangaParse(response: Response): MangasPage = parseSeries(response)

    private fun seriesApiRequest(
        page: Int,
        query: String,
        sort: String,
        order: String,
        status: String = "",
        type: String = "",
    ): Request {
        val builder = "$apiUrl/series".toHttpUrl().newBuilder()
            .addQueryParameter("offset", ((page - 1) * PER_PAGE_LIMIT).toString())
            .addQueryParameter("limit", PER_PAGE_LIMIT.toString())
            .addQueryParameter("sort", sort)
            .addQueryParameter("order", order)
        if (query.isNotBlank()) builder.addQueryParameter("search", query)
        if (status.isNotBlank()) builder.addQueryParameter("status", status)
        if (type.isNotBlank()) builder.addQueryParameter("type", type)
        return GET(builder.build().toString(), headers)
    }

    private fun parseSeries(response: Response): MangasPage {
        val json = runCatching {
            JsonParser.parseString(response.body?.string() ?: "").asJsonObject
        }.getOrNull() ?: return MangasPage(emptyList(), false)

        val hasMore = json.get("meta")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("has_more")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
        val data = json.get("data")?.takeIf { it.isJsonArray }?.asJsonArray ?: return MangasPage(emptyList(), hasMore)

        val mangas = data.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val slug = obj.stringOrNull("slug") ?: return@mapNotNull null
            SManga.create().apply {
                url = "/comics/$slug"
                title = obj.stringOrNull("title") ?: ""
                thumbnail_url = obj.stringOrNull("cover") ?: obj.stringOrNull("coverUrl") ?: ""
            }
        }
        return MangasPage(mangas, hasMore)
    }

    override fun getFilterList(): FilterList = FilterList(SortFilter(), StatusFilter(), TypeFilter())

    // =========================== Manga Details ============================

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asDocument()
        val data = document.extractAstroProp("title", "description") ?: return SManga.create()

        return SManga.create().apply {
            title = data.stringOrNull("title")?.trim().orEmpty()
            thumbnail_url = data.stringOrNull("coverUrl") ?: ""
            author = data.stringOrNull("author")
            artist = data.stringOrNull("artist")
            val plainDescription = data.stringOrNull("description")?.let { Jsoup.parseBodyFragment(it).text() }.orEmpty()
            description = buildString {
                append(plainDescription)
                data.longOrNull("popularityRank")?.let {
                    if (isNotEmpty()) append("\n\n")
                    append("Rank: #$it")
                }
                data.doubleOrNull("rating")?.let {
                    if (isNotEmpty()) append("\n\n")
                    append("Rating: %.2f".format(it))
                }
                data.stringOrNull("alternativeTitles")?.let { altTitles ->
                    val clean = altTitles
                        .split("•", ",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    if (clean.isNotEmpty()) {
                        if (isNotEmpty()) append("\n\n")
                        append("Alternative Titles:\n")
                        clean.forEach { append("- $it\n") }
                    }
                }
            }.trimEnd()
            genre = data.get("genres")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { it.takeIf { g -> g.isJsonObject }?.asJsonObject?.stringOrNull("name") }
                ?.joinToString()
            status = when (data.stringOrNull("status")?.lowercase()) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                "hiatus" -> SManga.ON_HIATUS
                "dropped", "axed" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
        }
    }

    // ============================== Chapters ==============================

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    override fun chapterListRequest(manga: SManga): Request {
        val slug = manga.url.trim('/').substringAfterLast('/')
        return GET("$apiUrl/series/$slug/chapters", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val json = runCatching {
            JsonParser.parseString(response.body?.string() ?: "").asJsonObject
        }.getOrNull() ?: return emptyList()
        val data = json.get("data")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()

        val now = System.currentTimeMillis()
        return data.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val number = obj.floatOrNull("number") ?: return@mapNotNull null
            val seriesSlug = obj.stringOrNull("series_slug") ?: return@mapNotNull null

            val isPremium = obj.booleanOrNull("is_premium") == true
            val earlyUntil = obj.stringOrNull("early_access_until")
            val locked = isPremium ||
                (earlyUntil != null && parseIsoDate(earlyUntil) > now)

            SChapter.create().apply {
                url = "/comics/$seriesSlug/chapter/${number.toInt()}"
                name = buildString {
                    if (locked) append("🔒 ")
                    append("Chapter ${number.toInt()}")
                    obj.stringOrNull("title")?.takeIf { it.isNotBlank() }?.let { append(" - $it") }
                }
                date_upload = obj.stringOrNull("published_at")?.let(::parseIsoDate)
                    ?: obj.stringOrNull("created_at")?.let(::parseIsoDate)
                    ?: 0L
                chapter_number = number
            }
        }
    }

    // =============================== Pages ================================

    override fun pageListRequest(chapter: SChapter): Request =
        GET(getChapterUrl(chapter), headers)

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asDocument()
        val pageUrl = response.request.url.toString()

        var pages = document.extractAstroProp("pages")?.get("pages")
            ?.takeIf { it.isJsonArray }?.asJsonArray
        if (pages == null || pages.size() == 0) {
            pages = fetchPremiumPages(document, response) ?: return emptyList()
        }

        return pages.mapIndexedNotNull { index, element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapIndexedNotNull null
            val imageUrl = obj.stringOrNull("url") ?: return@mapIndexedNotNull null

            val finalUrl = obj.get("tiles")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.takeIf { it.size() > 0 }
                ?.let { tiles ->
                    val cols = obj.intOrNull("tile_cols") ?: DEFAULT_TILE_COLS
                    val rows = obj.intOrNull("tile_rows") ?: DEFAULT_TILE_ROWS
                    val pageData = PageData(tiles.map { it.asInt }, cols, rows)
                    imageUrl.toHttpUrl().newBuilder()
                        .fragment(GSON.toJson(pageData))
                        .build()
                        .toString()
                }
                ?: imageUrl

            Page(index, pageUrl, finalUrl)
        }
    }

    private fun fetchPremiumPages(document: Document, response: Response): JsonArray? {
        val accessToken = client.cookieJar.loadForRequest(baseUrl.toHttpUrl())
            .firstOrNull { it.name == "access_token" }?.value
            ?: return null
        val pageToken = document.selectFirst("script:containsData(pageToken)")?.data()
            ?.let { PAGE_TOKEN_REGEX.find(it)?.groupValues?.get(1) }
            ?: "asura-reader-2026"
        val mangaSlug = response.request.url.pathSegments.getOrNull(1)
            ?.replace(RANDOM_SUFFIX_REGEX, "")
            ?: return null
        val number = response.request.url.pathSegments.getOrNull(3) ?: return null
        val url = "$apiUrl/series/$mangaSlug/chapters/$number"
        val premiumHeaders = headersBuilder()
            .set("Authorization", "Bearer $accessToken")
            .set("X-Page-Token", pageToken)
            .build()
        return runCatching {
            client.newCall(GET(url, premiumHeaders)).execute().use { premiumResponse ->
                val json = JsonParser.parseString(premiumResponse.body?.string() ?: "").asJsonObject
                json.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.get("chapter")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.get("pages")?.takeIf { it.isJsonArray }?.asJsonArray
            }
        }.getOrNull()
    }

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl ?: page.url, headers)

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ==================== Scrambled tiles reassembly =====================

    private fun scrambledImageInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val fragment = request.url.fragment ?: return response
        if (!fragment.startsWith("{")) return response

        val pageData = runCatching { GSON.fromJson(fragment, PageData::class.java) }.getOrNull()
            ?: return response
        val source = response.use {
            BitmapFactory.decodeStream(it.body?.byteStream())
        } ?: throw IOException("Failed to decode image")

        val tileW = source.width / pageData.tileCols
        val tileH = source.height / pageData.tileRows
        val output = Bitmap.createBitmap(
            tileW * pageData.tileCols,
            tileH * pageData.tileRows,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(output)
        for (w in pageData.tiles.indices) {
            val j = pageData.tiles[w]
            val srcCol = w % pageData.tileCols
            val srcRow = w / pageData.tileCols
            val dstCol = j % pageData.tileCols
            val dstRow = j / pageData.tileCols
            val srcRect = Rect(srcCol * tileW, srcRow * tileH, (srcCol + 1) * tileW, (srcRow + 1) * tileH)
            val dstRect = Rect(dstCol * tileW, dstRow * tileH, (dstCol + 1) * tileW, (dstRow + 1) * tileH)
            canvas.drawBitmap(source, srcRect, dstRect, null)
        }

        val buffer = Buffer().apply {
            output.compress(Bitmap.CompressFormat.WEBP, 100, outputStream())
        }
        source.recycle()
        output.recycle()

        return response.newBuilder()
            .body(buffer.asResponseBody("image/webp".toMediaType()))
            .build()
    }

    // ======================= Astro prop parsing ===========================

    private fun Document.extractAstroProp(vararg keys: String): JsonObject? {
        val selector = keys.joinToString("") { "[props*=$it]" }
        val props = selectFirst(selector)?.attr("props") ?: return null
        val json = runCatching { JsonParser.parseString(props) }.getOrNull() ?: return null
        return unwrapAstro(json)?.takeIf { it.isJsonObject }?.asJsonObject
    }

    private fun unwrapAstro(element: JsonElement): JsonElement? = when {
        element.isJsonArray -> {
            val array = element.asJsonArray
            when {
                array.size() == 0 -> JsonNull.INSTANCE
                array.size() == 1 -> JsonNull.INSTANCE
                array.size() == 2 && array[0].isJsonPrimitive -> unwrapAstro(array[1])
                else -> JsonArray().apply { array.forEach { add(unwrapAstro(it)) } }
            }
        }
        element.isJsonObject -> {
            val obj = element.asJsonObject
            JsonObject().apply { obj.entrySet().forEach { (key, value) -> add(key, unwrapAstro(value)) } }
        }
        else -> element
    }

    // =============================== Filters ==============================

    private class SortFilter(default: String? = null) :
        Filter.Sort(
            "Sort By",
            SORTS.map { it.first }.toTypedArray(),
            Selection(SORTS.indexOfFirst { it.second == default }.coerceAtLeast(0), ascending = false),
        ) {
        val selectedSort: String get() = SORTS[state?.index ?: 0].second
        val selectedOrder: String get() = if (state?.ascending == true) "asc" else "desc"
    }

    private class StatusFilter :
        SelectFilter(
            "Status",
            listOf("", "ongoing", "completed", "hiatus", "dropped", "axed").map { it to it },
        )

    private class TypeFilter :
        SelectFilter(
            "Type",
            listOf("", "manhwa", "manhua", "manga").map { it to it },
        )

    private abstract class SelectFilter(
        name: String,
        private val options: List<Pair<String, String>>,
    ) : Filter.Select<String>(
        name,
        options.map { it.first }.toTypedArray(),
        options.indexOfFirst { it.second.isEmpty() }.coerceAtLeast(0),
    ) {
        val selected: String get() = options[state].second
    }

    // =============================== Helpers ==============================

    private fun Response.asDocument(): Document {
        val body = body?.string() ?: throw IOException("Empty response body")
        return Jsoup.parse(body, request.url.toString())
    }

    private fun parseIsoDate(value: String): Long {
        val base = value.replace(FRACTIONAL_REGEX, "").removeSuffix("Z")
        return runCatching { ISO_DATE_FORMAT.parse(base)?.time ?: 0L }.getOrDefault(0L)
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        if (has(key) && get(key).isJsonPrimitive) get(key).asString else null

    private fun JsonObject.longOrNull(key: String): Long? =
        if (has(key) && get(key).isJsonPrimitive) runCatching { get(key).asLong }.getOrNull() else null

    private fun JsonObject.doubleOrNull(key: String): Double? =
        if (has(key) && get(key).isJsonPrimitive) runCatching { get(key).asDouble }.getOrNull() else null

    private fun JsonObject.floatOrNull(key: String): Float? =
        if (has(key) && get(key).isJsonPrimitive) runCatching { get(key).asFloat }.getOrNull() else null

    private fun JsonObject.intOrNull(key: String): Int? =
        if (has(key) && get(key).isJsonPrimitive) runCatching { get(key).asInt }.getOrNull() else null

    private fun JsonObject.booleanOrNull(key: String): Boolean? =
        if (has(key) && get(key).isJsonPrimitive) runCatching { get(key).asBoolean }.getOrNull() else null

    private data class PageData(
        val tiles: List<Int> = emptyList(),
        val tileCols: Int = 4,
        val tileRows: Int = 5,
    )

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        private const val PER_PAGE_LIMIT = 20
        private const val DEFAULT_TILE_COLS = 4
        private const val DEFAULT_TILE_ROWS = 5

        private val SORTS = listOf(
            "Latest Update" to "latest",
            "Popular" to "popular",
            "Rating" to "rating",
            "A-Z" to "title",
            "Newest" to "update",
        )

        private val GSON = Gson()
        private val FRACTIONAL_REGEX = Regex("\\.\\d+")
        private val PAGE_TOKEN_REGEX = Regex("""pageToken\*=\*"([^"]+)"""")
        private val RANDOM_SUFFIX_REGEX = Regex("""-[a-z0-9]{8}$""")
        private val ISO_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}
