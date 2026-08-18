package eu.kanade.tachiyomi.extension.en.divascans

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

/*
 * Diva Scans (https://divascans.com, alias divascans.org) — Next.js App-Router
 * site. Contains adult/mixed manhwa (isNsfw). Coin-locked chapters are hidden.
 *
 *     Browse  : /api/series?sort=popular&page=N        -> {"data":[...],"meta":{...}}
 *     Latest  : /api/series?page=N
 *     Search  : /api/search?q=<q>&page=N               -> {"series":[...]}
 *     Manga   : /series/<type>/<slug>                  -> data lives in the Next.js
 *               RSC flight stream embedded in the HTML (self.__next_f.push):
 *               a JSON object containing "series" (details) and a top-level
 *               "chapters" array.
 *     Chapter : /series/<type>/<slug>/chapter/<num>    -> RSC object with a "pages"
 *               array of { imageUrl }. (Covers are served from
 *               https://media.divascans.org without the /uploads/ prefix.)
 */
class DivaScans : HttpSource() {

    override val name = "Diva Scans"
    override val baseUrl = "https://divascans.com"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", BROWSER_UA)
        .set("Referer", "$baseUrl/")
        .set("Accept", "application/json, text/plain, */*")
        .set("Accept-Language", "en-US,en;q=0.5")

    // =========================== Browse & Search =========================

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/api/series?sort=popular&page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseMangaList(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/api/series?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseMangaList(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = if (query.isBlank()) {
            "$baseUrl/api/series?page=$page"
        } else {
            "$baseUrl/api/search?q=${URLEncoder.encode(query, "utf-8")}&page=$page"
        }
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseMangaList(response)

    private fun parseMangaList(response: Response): MangasPage {
        val body = response.body?.string() ?: throw IOException("Empty response body")

        val (items, totalPages) = runCatching {
            val json = JsonParser.parseString(body)
            if (json.isJsonArray) {
                json.asJsonArray to null
            } else {
                val obj = json.asJsonObject
                when {
                    obj.has("data") -> obj.get("data").asJsonArray to
                        (obj.get("meta")?.asJsonObject?.get("totalPages")?.takeIf { it.isJsonPrimitive }?.asInt)
                    obj.has("series") -> obj.get("series").asJsonArray to null
                    else -> JsonArray() to null
                }
            }
        }.getOrDefault(JsonArray() to null)

        val mangas = items.mapNotNull { element ->
            val item = element.asJsonObject
            val title = item.string("title").ifBlank { return@mapNotNull null }
            val slug = item.string("urlSlug").ifBlank { item.string("slug") }.ifBlank { return@mapNotNull null }
            val type = item.string("type").lowercase()
            val urlType = if (type.contains("novel")) "novel" else "comic"
            SManga.create().apply {
                this.title = title
                url = "/series/$urlType/$slug"
                thumbnail_url = cleanImageUrl(item.string("coverImage"))
            }
        }

        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val hasNextPage = totalPages?.let { currentPage < it } ?: (mangas.size >= 12)
        return MangasPage(mangas, hasNextPage)
    }

    // =========================== Manga Details ===========================

    override fun getMangaUrl(manga: SManga): String =
        if (manga.url.startsWith("http")) manga.url else baseUrl + manga.url

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val html = response.body?.string() ?: throw IOException("Empty response body")
        val doc = Jsoup.parse(html)
        val manga = SManga.create()

        val flight = parseFlight(html)
        val series = flight.firstObject("series")?.get("series")?.takeIf { it.isJsonObject }?.asJsonObject

        if (series != null) {
            manga.title = series.string("title")
            manga.thumbnail_url = cleanImageUrl(series.string("coverImage"))
            series.string("description").takeIf { it.isNotBlank() }?.let { raw ->
                manga.description = cleanHtml(raw)
            }
            val statusText = series.string("status")
            manga.status = when {
                statusText.contains("ongoing", true) -> SManga.ONGOING
                statusText.contains("completed", true) -> SManga.COMPLETED
                statusText.contains("hiatus", true) -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
            val origin = formatSlug(series.string("origin"))
            val genres = series.array("genres").mapNotNull { it.string("name") }
            val tags = series.array("tags").mapNotNull { it.string("name") }
            val genreList = listOf(origin).plus(genres).plus(tags).filter { it.isNotBlank() }
            if (genreList.isNotEmpty()) manga.genre = genreList.joinToString()
            if (manga.description.isNullOrBlank()) {
                manga.description = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
            }
            return manga
        }

        manga.title = doc.selectFirst("h1")?.text()?.trim().orEmpty().ifBlank { doc.title() }
        manga.thumbnail_url = cleanImageUrl(doc.selectFirst("img[src*='cover'], img[src*='thumbnail']")?.absUrl("src").orEmpty())
        doc.selectFirst("main p")?.let { manga.description = it.text() }
        doc.selectFirst("span:containsOwn(Status)")?.nextElementSibling()?.text()?.let { statusText ->
            manga.status = when {
                statusText.contains("ongoing", true) -> SManga.ONGOING
                statusText.contains("completed", true) -> SManga.COMPLETED
                statusText.contains("hiatus", true) -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
        }
        return manga
    }

    // ============================== Chapters =============================

    override fun getChapterUrl(chapter: SChapter): String =
        if (chapter.url.startsWith("http")) chapter.url else baseUrl + chapter.url

    override fun chapterListRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val html = response.body?.string() ?: return emptyList()
        val flight = parseFlight(html)
        val host = flight.firstObject("series") ?: return emptyList()
        val chaptersArray = host.get("chapters")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: host.get("series")?.takeIf { it.isJsonObject }?.asJsonObject?.get("chapters")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return emptyList()

        val segments = response.request.url.pathSegments
        val rawType = if (segments.size >= 2) segments[segments.size - 2] else "comic"
        val type = if (rawType.contains("novel")) "novel" else "comic"
        val slug = segments.lastOrNull() ?: return emptyList()

        val chapters = chaptersArray.mapNotNull { element ->
            val chap = element.asJsonObject
            val isLocked = chap.get("isLocked")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
            val coinPrice = chap.get("coinPrice")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
            if (isLocked || coinPrice > 0) return@mapNotNull null
            val num = chap.get("number")?.takeIf { it.isJsonPrimitive } ?: return@mapNotNull null
            val numStr = num.asString
            SChapter.create().apply {
                url = "/series/$type/$slug/chapter/$numStr"
                val title = chap.string("title")
                name = title.ifBlank { "Chapter $numStr" }
                chapter_number = numStr.toFloatOrNull() ?: 0f
                date_upload = chap.string("publishedAt")
                    ?.let { text -> runCatching { DATE_FORMAT.parse(text)?.time ?: 0L }.getOrDefault(0L) }
                    ?: 0L
            }
        }
        return chapters.sortedByDescending { it.chapter_number }
    }

    // =============================== Pages ===============================

    override fun pageListRequest(chapter: SChapter): Request =
        GET(getChapterUrl(chapter), headers)

    override fun pageListParse(response: Response): List<Page> {
        val html = response.body?.string() ?: throw IOException("Empty response body")
        val doc = Jsoup.parse(html)

        val flight = parseFlight(html)
        val pages = flight.firstObject("pages")?.get("pages")?.takeIf { it.isJsonArray }?.asJsonArray
        if (pages != null && pages.size() > 0) {
            return pages.mapIndexedNotNull { index, element ->
                val url = cleanImageUrl(element.asJsonObject.string("imageUrl"))
                if (url.isBlank()) null else Page(index, imageUrl = url)
            }
        }

        val domImages = doc.select("div.reader-images img, div.chapter-container img, main img[src*='chapter']")
        if (domImages.isNotEmpty()) {
            return domImages.mapIndexedNotNull { index, element ->
                val url = cleanImageUrl(element.absUrl("data-src").ifBlank { element.absUrl("src") })
                if (url.isBlank()) null else Page(index, imageUrl = url)
            }
        }

        return emptyList()
    }

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl ?: page.url, headers.newBuilder().set("Referer", "$baseUrl/").build())

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ====================== Next.js RSC flight parsing ===================

    private fun parseFlight(html: String): Flight {
        val stream = StringBuilder()
        for (match in NEXT_F_PUSH_REGEX.findAll(html)) {
            stream.append(unescapeJsString(match.groupValues[1]))
        }
        return parseFlightStream(stream.toString())
    }

    private fun unescapeJsString(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\') {
                out.append(c)
                i++
                continue
            }
            val next = s.getOrNull(i + 1) ?: break
            when (next) {
                'n' -> out.append('\n')
                't' -> out.append('\t')
                'r' -> out.append('\r')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'v' -> out.append('\u000B')
                '0' -> out.append('\u0000')
                '\\' -> out.append('\\')
                '"' -> out.append('"')
                '\'' -> out.append('\'')
                '/' -> out.append('/')
                'u' -> {
                    if (i + 6 <= s.length) {
                        val hex = s.substring(i + 2, i + 6)
                        val char = hex.toIntOrNull(16)
                        if (char != null) out.append(char.toChar()) else out.append('u')
                        i += 6
                        continue
                    }
                    out.append('u')
                }
                else -> out.append(next)
            }
            i += 2
        }
        return out.toString()
    }

    private fun parseFlightStream(stream: String): Flight {
        val values = ArrayList<JsonElement>()
        var pos = 0
        val n = stream.length
        var guard = 0
        while (pos < n && guard++ < 5000) {
            val colon = stream.indexOf(':', pos)
            if (colon == -1) break
            val id = stream.substring(pos, colon)
            if (id.isEmpty() || !id.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                pos = colon + 1
                continue
            }
            if (colon + 1 >= n) break
            val c = stream[colon + 1]
            if (c == 'T') {
                val comma = stream.indexOf(',', colon + 2)
                if (comma == -1) break
                val byteLen = stream.substring(colon + 2, comma).toIntOrNull(16) ?: break
                val start = comma + 1
                var end = start
                var bytes = 0
                while (end < n && bytes < byteLen) {
                    val cp = stream.codePointAt(end)
                    bytes += when {
                        cp < 0x80 -> 1
                        cp < 0x800 -> 2
                        cp < 0x10000 -> 3
                        else -> 4
                    }
                    if (cp >= 0x10000) end++
                    end++
                }
                pos = end
            } else {
                val end = scanJsonValue(stream, colon + 1) ?: break
                val raw = stream.substring(colon + 1, end)
                // RSC emits a bare $undefined token which is not valid JSON.
                val sanitized = raw.replace("\$undefined", "null")
                runCatching { values.add(JsonParser.parseString(sanitized)) }
                pos = end
            }
            while (pos < n && stream[pos] == '\n') pos++
        }
        return Flight(values)
    }

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

    private class Flight(val values: List<JsonElement>) {
        fun firstObject(key: String): JsonObject? {
            for (value in values) {
                findObject(value, key)?.let { return it }
            }
            return null
        }

        private fun findObject(element: JsonElement, key: String): JsonObject? {
            if (element.isJsonObject) {
                val obj = element.asJsonObject
                if (obj.has(key)) return obj
                for ((_, child) in obj.entrySet()) {
                    findObject(child, key)?.let { return it }
                }
            } else if (element.isJsonArray) {
                for (child in element.asJsonArray) {
                    findObject(child, key)?.let { return it }
                }
            }
            return null
        }
    }

    // ============================= Utilities =============================

    private fun cleanHtml(raw: String): String {
        val cleaned = raw
            .replace("\\n", "\n")
            .replace("<br/>", "\n")
            .replace("<br />", "\n")
            .replace("<br>", "\n")
            .replace("</p>", "\n")
        return Jsoup.parseBodyFragment(cleaned).text().trim()
    }

    private fun cleanImageUrl(url: String): String {
        if (url.isEmpty() || url.startsWith("data:")) return url
        val absoluteUrl = if (url.startsWith("/")) "$baseUrl$url" else url
        val httpUrl = absoluteUrl.toHttpUrlOrNull() ?: return url
        var cleanUrl = httpUrl.queryParameter("url") ?: absoluteUrl
        if (cleanUrl.startsWith("/")) cleanUrl = "$baseUrl$cleanUrl"
        return cleanUrl
            .replace("divascans.org", "media.divascans.org")
            .replace("media.media.divascans.org", "media.divascans.org")
            .replace("/_next/image", "")
            .replace("/uploads/", "/")
            .substringBefore("?")
    }

    private fun formatSlug(slug: String): String =
        slug.replace("-", " ").replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }

    private fun JsonObject.string(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: ""

    private fun JsonObject.array(key: String): JsonArray =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()

    private fun JsonElement.string(key: String): String =
        takeIf { it.isJsonObject }?.asJsonObject?.string(key) ?: ""

    companion object {
        private val NEXT_F_PUSH_REGEX =
            Regex("""self\.__next_f\.push\(\[1,"([\s\S]*?)"\]\)""")

        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
    }
}
