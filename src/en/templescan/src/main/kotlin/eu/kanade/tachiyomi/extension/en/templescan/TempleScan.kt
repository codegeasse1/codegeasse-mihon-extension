package eu.kanade.tachiyomi.extension.en.templescan

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import rx.Observable
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.min

/*
 * TempleScan (https://templescan.net) — Next.js App-Router site.
 * All data comes from the RSC (React Server Components) flight stream, requested
 * with an "rsc: 1" header; the responses are raw flight text, not HTML.
 *
 *     Browse  : /comics  (rsc:1) -> chunk with an "allComics" array (whole catalog).
 *               Popular = sorted by total_views, Latest = sorted by update_chapter;
 *               search/status filters applied client-side, then paginated in memory.
 *     Manga   : /comic/<slug> (rsc:1) -> object with "seriesData" (details AND
 *               all chapters with their embedded page image URLs).
 *     Chapter : /comic/<slug>/<chapter-slug> (rsc:1) -> object with a "pages" array.
 *
 * RSC stream layout: "<hex-id>:<value>" rows separated by "\n". "T" rows hold raw
 * text ("<hex-id>:T<hexByteLen>,<content>") and are referenced from JSON as "$<id>".
 * (The only reference used here is the manga description; everything else is inline.)
 */
class TempleScan : HttpSource() {

    override val name = "TempleScan"
    override val baseUrl = "https://templescan.net"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", BROWSER_UA)
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)

    private val rscHeaders = headersBuilder().set("rsc", "1").build()

    private var seriesCache: List<JsonObject> = emptyList()

    // =========================== Browse & Search =========================

    override fun fetchPopularManga(page: Int): Observable<MangasPage> =
        fetchCatalog(page, "", OrderFilter.TRENDING, "")

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> =
        fetchCatalog(page, "", OrderFilter.UPDATED, "")

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        val status = filters.filterIsInstance<StatusFilter>().firstOrNull()?.selected.orEmpty()
        val order = filters.filterIsInstance<OrderFilter>().firstOrNull()?.selected ?: OrderFilter.UPDATED
        return fetchCatalog(page, query, order, status)
    }

    private fun fetchCatalog(page: Int, query: String, order: String, status: String): Observable<MangasPage> {
        if (page == 1) {
            client.newCall(GET("$baseUrl/comics", rscHeaders)).execute().use { response ->
                seriesCache = parseBrowse(response)
            }
        }
        return Observable.just(parseDirectory(page, query, order, status))
    }

    private fun parseBrowse(response: Response): List<JsonObject> {
        val flight = parseFlight(flightData(response))
        val container = flight.firstObject("allComics") ?: return emptyList()
        val arr = container.get("allComics")
        if (!arr.isJsonArray) return emptyList()
        return arr.asJsonArray.mapNotNull { if (it is JsonObject) it else null }
    }

    private fun parseDirectory(page: Int, query: String, order: String, status: String): MangasPage {
        var list = seriesCache.filter { series ->
            val title = series.stringOrNull("title").orEmpty()
            val alt = series.stringOrNull("alternative_names").orEmpty()
            val queryOk = query.isBlank() ||
                title.contains(query, ignoreCase = true) ||
                alt.contains(query, ignoreCase = true)
            val statusOk = status.isEmpty() || series.stringOrNull("status") == status
            queryOk && statusOk
        }
        list = when (order) {
            OrderFilter.UPDATED -> list.sortedByDescending {
                it.stringOrNull("update_chapter")?.let(::parseDate) ?: 0L
            }
            OrderFilter.TRENDING -> list.sortedByDescending {
                it.longOrNull("total_views") ?: 0L
            }
            else -> list
        }
        val from = (page - 1) * PAGE_SIZE
        val slice = list.drop(from).take(PAGE_SIZE)
        return MangasPage(slice.mapNotNull(::browseToManga), from + PAGE_SIZE < list.size)
    }

    private fun browseToManga(obj: JsonObject): SManga? {
        val slug = obj.stringOrNull("series_slug") ?: return null
        return SManga.create().apply {
            url = "/comic/$slug"
            title = obj.stringOrNull("title") ?: ""
            thumbnail_url = obj.stringOrNull("thumbnail") ?: ""
        }
    }

    override fun getFilterList(): FilterList = FilterList(StatusFilter(), OrderFilter())

    // =========================== Manga Details ============================

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), rscHeaders)

    override fun mangaDetailsParse(response: Response): SManga {
        val flight = parseFlight(flightData(response))
        val data = flight.firstObject("seriesData") ?: return SManga.create()

        val rawDescription = data.stringOrNull("description")
        val description = if (rawDescription != null && rawDescription.startsWith("$")) {
            flight.text[rawDescription.drop(1)] ?: rawDescription
        } else {
            rawDescription.orEmpty()
        }

        return SManga.create().apply {
            url = data.stringOrNull("series_slug")?.let { "/comic/$it" } ?: response.request.url.toString()
            title = data.stringOrNull("title")?.trim().orEmpty()
            thumbnail_url = data.stringOrNull("thumbnail") ?: ""
            status = when (data.stringOrNull("status")) {
                "Ongoing" -> SManga.ONGOING
                "Hiatus" -> SManga.ON_HIATUS
                "Completed" -> SManga.COMPLETED
                "Canceled", "Dropped" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
            author = data.stringOrNull("author")
            artist = data.stringOrNull("studio")

            val cleanDescription = if (description.contains("#")) {
                description.substringBefore("#").replace(LAST_WORD_REGEX, "").trim()
            } else {
                description.trim()
            }
            this.description = buildString {
                append(Jsoup.clean(cleanDescription, Safelist.none()))
                data.stringOrNull("alternative_names")?.takeIf { it.isNotBlank() }?.let {
                    append("\n\n")
                    append("Alternative Name: $it\n")
                }
            }

            genre = buildList {
                data.stringOrNull("badge")?.takeIf { it.isNotBlank() }?.let { add(it) }
                data.stringOrNull("release_year")?.takeIf { it.isNotBlank() }?.let { add(it) }
                if (data.booleanOrNull("adult") == true) add("Adult")
                data.arrayOrNull("tag_series")?.forEach { element ->
                    (element as? JsonObject)?.get("tag")
                        ?.takeIf { it.isJsonObject }?.asJsonObject?.stringOrNull("name")
                        ?.takeIf { it.isNotBlank() }?.let { add(it) }
                }
                description.takeIf { it.contains("#") }?.let { desc ->
                    TEXT_TAGS_REGEX.findAll(desc).forEach { match -> add(match.groupValues[1]) }
                }
            }.filter { it.isNotBlank() }.joinToString()
        }
    }

    // ============================== Chapters ==============================

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    override fun chapterListRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), rscHeaders)

    override fun chapterListParse(response: Response): List<SChapter> {
        val flight = parseFlight(flightData(response))
        val data = flight.firstObject("seriesData") ?: return emptyList()
        val mangaSlug = data.stringOrNull("series_slug") ?: return emptyList()

        return data.arrayOrNull("Season").flatMap { seasonElement ->
            val season = seasonElement as? JsonObject ?: return@flatMap emptyList()
            val chapters = season.arrayOrNull("Chapter") ?: return@flatMap emptyList()
            chapters.mapNotNull { chapterElement ->
                val chapter = chapterElement as? JsonObject ?: return@mapNotNull null
                if (chapter.intOrNull("price") != 0) return@mapNotNull null
                val slug = chapter.stringOrNull("chapter_slug") ?: return@mapNotNull null
                val name = chapter.stringOrNull("chapter_name") ?: ""
                SChapter.create().apply {
                    url = "/comic/$mangaSlug/$slug"
                    this.name = buildString {
                        append(name)
                        chapter.stringOrNull("chapter_title")?.takeIf { it.isNotBlank() }?.let {
                            append(": ", it)
                        }
                    }
                    date_upload = chapter.stringOrNull("created_at")?.let(::parseDate) ?: 0L
                    chapter_number = CHAPTER_NUMBER_REGEX.find(name)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
                }
            }
        }
    }

    // =============================== Pages ================================

    override fun pageListRequest(chapter: SChapter): Request =
        GET(getChapterUrl(chapter), rscHeaders)

    override fun pageListParse(response: Response): List<Page> {
        val flight = parseFlight(flightData(response))
        val pagesContainer = flight.firstObject("pages") { obj ->
            val value = obj.get("pages")
            value.isJsonArray && value.asJsonArray.firstOrNull()
                ?.takeIf { it.isJsonPrimitive }?.asString?.startsWith("http") == true
        } ?: return emptyList()

        val pageUrl = response.request.url.toString()
        return pagesContainer.get("pages").asJsonArray.mapIndexed { index, element ->
            Page(index, pageUrl, element.asString)
        }
    }

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl ?: page.url, headers)

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ======================= Unsupported defaults =========================

    override fun popularMangaRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun popularMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException()
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        throw UnsupportedOperationException()
    override fun searchMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()

    // ======================= RSC flight parsing ===========================

    private fun flightData(response: Response): String {
        val body = response.body?.string() ?: throw IOException("Empty response body")
        if (!body.contains("self.__next_f.push")) return body
        val sb = StringBuilder()
        for (match in PUSH_REGEX.findAll(body)) {
            runCatching {
                val array = JsonParser.parseString(match.groupValues[1]).asJsonArray
                sb.append(array[1].asString)
            }
        }
        return sb.toString()
    }

    private fun parseFlight(stream: String): Flight {
        val text = HashMap<String, String>()
        val values = ArrayList<JsonElement>()
        var pos = 0
        val n = stream.length
        while (pos < n) {
            val colon = stream.indexOf(':', pos)
            if (colon == -1) break
            val id = stream.substring(pos, colon)
            if (id.isEmpty() || !id.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                pos = colon + 1
                continue
            }
            if (colon + 1 >= n) break
            when (stream[colon + 1]) {
                'T' -> {
                    val comma = stream.indexOf(',', colon + 2)
                    if (comma == -1) break
                    val byteLen = stream.substring(colon + 2, comma).toIntOrNull(16) ?: break
                    val start = comma + 1
                    var end = start
                    var bytes = 0
                    while (end < n && bytes < byteLen) {
                        val c = stream[end]
                        bytes += when {
                            c.code < 0x80 -> 1
                            c.code < 0x800 -> 2
                            Character.isHighSurrogate(c) -> {
                                end++
                                4
                            }
                            else -> 3
                        }
                        end++
                    }
                    text[id] = stream.substring(start, end)
                    pos = end
                }
                else -> {
                    val end = scanJsonValue(stream, colon + 1) ?: break
                    runCatching { values.add(JsonParser.parseString(stream.substring(colon + 1, end))) }
                    pos = end
                }
            }
            while (pos < n && stream[pos] == '\n') pos++
        }
        return Flight(text, values)
    }

    /** Returns the end index (exclusive) of the JSON value starting at [start]. */
    private fun scanJsonValue(stream: String, start: Int): Int? {
        if (start >= stream.length) return null
        if (stream[start] == '{' || stream[start] == '[') {
            var depth = 0
            var inString = false
            var i = start
            while (i < stream.length) {
                val c = stream[i]
                if (inString) {
                    if (c == '\\') {
                        i += 2
                        continue
                    }
                    if (c == '"') inString = false
                } else {
                    when (c) {
                        '"' -> inString = true
                        '{', '[' -> depth++
                        '}', ']' -> {
                            depth--
                            if (depth == 0) return i + 1
                        }
                        else -> {}
                    }
                }
                i++
            }
            return null
        }
        val newline = stream.indexOf('\n', start)
        return if (newline == -1) stream.length else newline
    }

    private class Flight(
        val text: Map<String, String>,
        val values: List<JsonElement>,
    ) {
        fun firstObject(key: String, predicate: (JsonObject) -> Boolean = { true }): JsonObject? {
            for (value in values) {
                findObject(value, key, predicate)?.let { return it }
            }
            return null
        }

        private fun findObject(element: JsonElement, key: String, predicate: (JsonObject) -> Boolean): JsonObject? {
            if (element is JsonObject) {
                if (element.has(key) && predicate(element)) return element
                for ((_, child) in element.entrySet()) {
                    findObject(child, key, predicate)?.let { return it }
                }
            } else if (element is JsonArray) {
                for (child in element) {
                    findObject(child, key, predicate)?.let { return it }
                }
            }
            return null
        }
    }

    // =============================== Filters ==============================

    private abstract class SelectFilter(
        name: String,
        private val options: List<Pair<String, String>>,
        defaultValue: String? = null,
    ) : Filter.Select<String>(
        name,
        options.map { it.first }.toTypedArray(),
        options.indexOfFirst { it.second == defaultValue }.coerceAtLeast(0),
    ) {
        val selected: String get() = options[state].second
    }

    private class StatusFilter :
        SelectFilter(
            "Status",
            listOf("", "Ongoing", "Hiatus", "Completed", "Canceled", "Dropped").map { it to it },
        )

    private class OrderFilter(default: String? = null) :
        SelectFilter(
            "Order by",
            listOf(
                "Update Chapter" to OrderFilter.UPDATED,
                "Created At" to OrderFilter.CREATED,
                "Trending" to OrderFilter.TRENDING,
            ),
            default,
        ) {
        companion object {
            const val UPDATED = "updated"
            const val CREATED = "created"
            const val TRENDING = "views"
        }
    }

    // =============================== Helpers ==============================

    private fun parseDate(value: String): Long {
        for (format in DATE_FORMATS) {
            runCatching { return format.parse(value)?.time ?: 0L }
        }
        return 0L
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        if (has(key) && get(key).isJsonPrimitive) get(key).asString else null

    private fun JsonObject.longOrNull(key: String): Long? =
        if (has(key) && get(key).isJsonPrimitive) runCatching { get(key).asLong }.getOrNull() else null

    private fun JsonObject.intOrNull(key: String): Int? =
        if (has(key) && get(key).isJsonPrimitive) runCatching { get(key).asInt }.getOrNull() else null

    private fun JsonObject.booleanOrNull(key: String): Boolean? =
        if (has(key) && get(key).isJsonPrimitive) runCatching { get(key).asBoolean }.getOrNull() else null

    private fun JsonObject.arrayOrNull(key: String): JsonArray? =
        if (has(key) && get(key).isJsonArray) get(key).asJsonArray else null

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        private const val PAGE_SIZE = 20

        private val PUSH_REGEX =
            Regex("""self\.__next_f\.push\(\s*(\[1,"(?:\\.|.)*?"\])\s*\)""", RegexOption.DOT_MATCHES_ALL)
        private val CHAPTER_NUMBER_REGEX = Regex("""(\d+(?:\.\d+)?)""")
        private val TEXT_TAGS_REGEX = Regex("""(?i)#(\w+)""")
        private val LAST_WORD_REGEX = Regex("""[\w\s]+:?\s*$""")
        private val DATE_FORMATS = listOf(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US),
        ).onEach { it.timeZone = TimeZone.getTimeZone("UTC") }
    }
}
