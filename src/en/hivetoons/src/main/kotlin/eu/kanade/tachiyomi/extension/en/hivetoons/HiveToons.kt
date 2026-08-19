package eu.kanade.tachiyomi.extension.en.hivetoons

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
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

/*
 * HiveToons (https://hivetoons.org) — formerly Hive Scans (hivescans.com),
 * "vastro" (Vite + Astro) platform, same stack as Asura Scans.
 *
 *     Popular : /series?page=N                    (server-rendered cards)
 *     Latest  : /latest-updates?page=N
 *     Search  : /series?searchTerm=<q>&page=N
 *     Manga   : /series/<slug> -> series data lives in the
 *               <astro-island opts*=SeriesChaptersPanelIsland> props
 *               (devalue-encoded JSON: title/description/cover/status/genres).
 *     Chapters: GET https://api.hivetoons.org/api/chapters?postId=<id>
 *               (postId is parsed from the series page island props)
 *     Pages   : GET https://api.hivetoons.org/api/chapter/content
 *               ?mangaslug=<slug>&chapterslug=<chapter-slug>
 *               -> { "isAccessible": bool, "images": [{ url, order }] }
 *               Coin-locked chapters return isAccessible=false (no images).
 */
class HiveToons : HttpSource() {

    override val name = "HiveToons"
    override val baseUrl = "https://hivetoons.org"
    override val lang = "en"
    override val supportsLatest = true

    private val apiBase = "https://api.hivetoons.org"

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", BROWSER_UA)
        .set("Referer", "$baseUrl/")

    // =========================== Browse & Search =========================

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/series?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asDocument()
        // Cards are <a title> anchors wrapping the cover <img>. The /series/ href filter
        // matches the thumb anchor (the plain title anchor has no img, so it's skipped).
        // Also fixes the Latest tab, whose grid is NOT wrapped in <section[aria-busy]>.
        val mangas = document.select("a[title][href*='/series/']").mapNotNull(::mangaFromCard)
        val hasNextPage = document.selectFirst(
            "nav[aria-label=Pagination] button[aria-label=\"Next page\"]:not([disabled])",
        ) != null
        return MangasPage(mangas, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/latest-updates?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage =
        popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/series?searchTerm=${URLEncoder.encode(query, "utf-8")}&page=$page", headers)

    override fun searchMangaParse(response: Response): MangasPage =
        popularMangaParse(response)

    private fun mangaFromCard(link: Element): SManga? {
        val img = link.selectFirst("img") ?: return null
        return SManga.create().apply {
            setUrlWithoutDomain(link.absUrl("href"))
            title = link.attr("title").trim()
                .ifBlank { link.text().trim() }
                .ifBlank { img.attr("alt").trim() }
            thumbnail_url = imageFromElement(img) ?: ""
        }
    }

    // =========================== Manga Details ===========================

    override fun getMangaUrl(manga: SManga): String =
        if (manga.url.startsWith("http")) manga.url else baseUrl + manga.url

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val html = response.body?.string() ?: throw IOException("Empty response body")
        val document = Jsoup.parse(html, response.request.url.toString())
        val post = parseSeriesPost(html)

        return SManga.create().apply {
            title = post?.string("postTitle")
                .takeIf { !it.isNullOrBlank() }
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: ""
            setUrlWithoutDomain(response.request.url.toString())
            post?.string("featuredImage")?.takeIf { it.isNotBlank() }?.let { thumbnail_url = it }
            val statusText = post?.string("seriesStatus").orEmpty()
            status = when {
                statusText.contains("ongoing", true) -> SManga.ONGOING
                statusText.contains("completed", true) -> SManga.COMPLETED
                statusText.contains("hiatus", true) -> SManga.ON_HIATUS
                statusText.contains("cancel", true) -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
            post?.array("genres")?.takeIf { it.size() > 0 }?.let { genres ->
                val names = genres.mapNotNull { it.string("name") }.filter { it.isNotBlank() }
                if (names.isNotEmpty()) genre = names.joinToString()
            }
            post?.string("artist")?.takeIf { it.isNotBlank() }?.let { author = it }
            post?.string("postContent")?.let { htmlDesc ->
                val text = htmlDesc.replace(HTML_TAG_REGEX, " ").replace(WHITESPACE_REGEX, " ").trim()
                if (text.isNotBlank()) description = text
            }
        }
    }

    // ============================== Chapters =============================

    override fun getChapterUrl(chapter: SChapter): String =
        if (chapter.url.startsWith("http")) chapter.url else baseUrl + chapter.url

    override fun chapterListRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val html = response.body?.string() ?: return emptyList()
        val post = parseSeriesPost(html) ?: return emptyList()
        val postId = post.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: return emptyList()
        val slug = response.request.url.toString().substringAfter("$baseUrl/series/").substringBefore("/")
        if (slug.isBlank()) return emptyList()

        val chaptersJson = client.newCall(GET("$apiBase/api/chapters?postId=$postId", headers)).execute()
            .use { apiResponse ->
                val body = apiResponse.body?.string() ?: return@use JsonArray()
                runCatching {
                    val json = JsonParser.parseString(body)
                    json.asJsonObject.get("post")?.asJsonObject?.get("chapters")?.asJsonArray
                }.getOrNull() ?: JsonArray()
            }

        return chaptersJson.mapNotNull { element ->
            val chapter = element.asJsonObject
            val number = chapter.get("number")?.takeIf { it.isJsonPrimitive }?.asFloat ?: return@mapNotNull null
            val chapterSlug = chapter.get("slug")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
            SChapter.create().apply {
                url = "/series/$slug/$chapterSlug"
                val numText = if (number % 1f == 0f) number.toInt().toString() else number.toString()
                name = buildString {
                    append("Chapter ")
                    append(numText)
                    chapter.get("title")?.takeIf { it.isJsonPrimitive }?.asString
                        ?.takeIf { it.isNotBlank() }?.let { append(" - ").append(it) }
                }
                chapter_number = number
                date_upload = chapter.get("createdAt")?.takeIf { it.isJsonPrimitive }?.asString
                    ?.let { text -> runCatching { DATE_FORMAT.parse(text)?.time ?: 0L }.getOrDefault(0L) }
                    ?: 0L
            }
        }
    }

    // =============================== Pages ===============================

    override fun pageListRequest(chapter: SChapter): Request {
        val segments = chapter.url.substringAfter("/series/").split("/")
        val mangaSlug = segments.getOrNull(0).orEmpty()
        val chapterSlug = segments.getOrNull(1).orEmpty()
        return GET("$apiBase/api/chapter/content?mangaslug=$mangaSlug&chapterslug=$chapterSlug", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val body = response.body?.string() ?: throw IOException("Empty response body")
        val json = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return emptyList()
        val accessible = json.get("isAccessible")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        if (!accessible) return emptyList()
        val images = json.get("images")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return images.mapIndexedNotNull { index, element ->
            val url = element.asJsonObject.get("url")?.takeIf { it.isJsonPrimitive }?.asString
                ?: return@mapIndexedNotNull null
            Page(index, imageUrl = url)
        }
    }

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl ?: page.url, headers.newBuilder().set("Referer", "$baseUrl/").build())

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ====================== Series-post island parsing ===================

    private fun parseSeriesPost(html: String): JsonObject? {
        val island = ISLAND_REGEX.find(html)?.value ?: return null
        val props = PROPS_REGEX.find(island)?.groupValues?.get(1) ?: return null
        val decoded = decodeDevalue(JsonParser.parseString(unescapeHtmlEntities(props)))
        return (decoded as? JsonObject)?.get("post")?.takeIf { it.isJsonObject }?.asJsonObject
    }

    private fun decodeDevalue(node: JsonElement): JsonElement {
        if (node.isJsonArray) {
            val arr = node.asJsonArray
            if (arr.size() == 2 && arr[0].isJsonPrimitive && arr[0].asString in DECODE_TYPES) {
                when (arr[0].asString) {
                    "0" -> return decodeDevalue(arr[1])
                    "1" -> return JsonArray().apply { arr[1].asJsonArray.forEach { add(decodeDevalue(it)) } }
                    "2" -> return JsonObject().apply {
                        arr[1].asJsonArray.forEach { pair ->
                            val p = pair.asJsonArray
                            add(p[0].asString, decodeDevalue(p[1]))
                        }
                    }
                    "3" -> return decodeDevalue(arr[1])
                    "5" -> return arr[1]
                    "6" -> return decodeDevalue(arr[1])
                }
            }
            return JsonArray().apply { arr.forEach { add(decodeDevalue(it)) } }
        }
        if (node.isJsonObject) {
            return JsonObject().apply {
                node.asJsonObject.entrySet().forEach { add(it.key, decodeDevalue(it.value)) }
            }
        }
        return node
    }

    private fun unescapeHtmlEntities(s: String): String =
        s.replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")

    private fun JsonObject.string(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: ""

    private fun JsonObject.array(key: String): JsonArray =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()

    private fun JsonElement.string(key: String): String =
        takeIf { it.isJsonObject }?.asJsonObject?.string(key) ?: ""

    // ========================== Image helpers ============================

    private fun imageFromElement(element: Element): String? {
        val url = element.attr("data-src").trim()
            .ifEmpty { element.attr("data-lazy-src").trim() }
            .ifEmpty { element.attr("data-cfsrc").trim() }
            .ifEmpty { element.attr("src").trim() }
        return url.takeIf { it.isNotBlank() }
    }

    private fun Response.asDocument(): Document {
        val body = body?.string() ?: throw IOException("Empty response body")
        return Jsoup.parse(body, request.url.toString())
    }

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        private val ISLAND_REGEX = Regex("""<astro-island[^>]*opts="[^"]*SeriesChaptersPanelIsland[^"]*"[^>]*>""")
        private val PROPS_REGEX = Regex("""props="([^"]+)"""")

        private val DECODE_TYPES = listOf("0", "1", "2", "3", "5", "6")

        private val HTML_TAG_REGEX = Regex("""<[^>]*>""")
        private val WHITESPACE_REGEX = Regex("""\s+""")

        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
    }
}
