package eu.kanade.tachiyomi.extension.ko.joatoon

import com.google.gson.JsonParser
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

/*
 * Joatoon (조아툰, https://joa-new.com, alias joa-vip.com) — Korean webtoon
 * aggregator built on Laravel + Livewire. Has a dedicated adult section
 * (/toon/adult), so this source is marked nsfw.
 *
 *     Browse   : /toon/general?sort=popular      (cards: div.img-card-wrapper)
 *     Latest   : /toon/general                   (newest-first by default)
 *     Search   : /toon/search?k=<query>          (single page, same cards)
 *     Paginate : /toon/general?[sort=..]&cursor=<token> with the header
 *                X-Requested-With: XMLHttpRequest -> JSON {"html","next_cursor",
 *                "has_more"}. The initial cursor is embedded in the page as
 *                x-data="infiniteScroll(true, '<base64url(zlib(json))>')".
 *     Manga    : /toon/w/<id>                    (h1 title, img[data-cover],
 *                p.line-clamp-3 description, span.genre-badge-large genres,
 *                chapter rows with a.text-sm[href*=/c/] links + dates)
 *     Chapter  : /toon/w/<id>/c/<cid>            (pages are img[data-src] in #chapters)
 *
 * Images are served from https://joa-vip.com/toonimg/... (absolute URLs).
 */
class Joatoon : HttpSource() {

    override val name = "Joatoon"
    override val baseUrl = "https://joa-new.com"
    override val lang = "ko"
    override val supportsLatest = true

    @Volatile
    private var nextCursor: String? = null
    private var browseSort = ""

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", BROWSER_UA)
        .set("Referer", "$baseUrl/")
        .set("Accept-Language", "ko-KR,ko;q=0.9,en-US,en;q=0.8")

    private val cursorHeaders: Headers
        get() = headers.newBuilder().set("X-Requested-With", "XMLHttpRequest").build()

    // =========================== Browse & Search =========================

    override fun popularMangaRequest(page: Int): Request {
        if (page == 1) {
            nextCursor = null
            browseSort = "sort=popular"
            return GET("$baseUrl$GENERAL_PATH?sort=popular", headers)
        }
        return cursorRequest()
    }

    override fun popularMangaParse(response: Response): MangasPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request {
        if (page == 1) {
            nextCursor = null
            browseSort = ""
            return GET("$baseUrl$GENERAL_PATH", headers)
        }
        return cursorRequest()
    }

    override fun latestUpdatesParse(response: Response): MangasPage = parseList(response)

    private fun cursorRequest(): Request {
        val cursor = nextCursor.orEmpty()
        val query = listOf(browseSort, "cursor=$cursor").filter { it.isNotBlank() }.joinToString("&")
        return GET("$baseUrl$GENERAL_PATH?$query", cursorHeaders)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = if (query.isBlank()) {
            "$baseUrl$GENERAL_PATH"
        } else {
            "$baseUrl/toon/search?k=${URLEncoder.encode(query, "utf-8")}"
        }
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body?.string() ?: return MangasPage(emptyList(), false))
        return MangasPage(parseCards(doc), false)
    }

    private fun parseList(response: Response): MangasPage {
        val isCursorPage = !response.request.url.queryParameter("cursor").isNullOrEmpty()
        val body = response.body?.string() ?: return MangasPage(emptyList(), false)

        if (!isCursorPage) {
            val doc = Jsoup.parse(body)
            val mangas = parseCards(doc)
            val match = doc.selectFirst("div[x-data^=\"infiniteScroll\"]")
                ?.attr("x-data")
                ?.let { INFINITE_SCROLL_REGEX.find(it) }
            nextCursor = match?.groupValues?.getOrNull(2)
            val hasMore = match?.groupValues?.getOrNull(1) == "true" && !nextCursor.isNullOrEmpty()
            return MangasPage(mangas, hasMore)
        }

        val json = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
            ?: return MangasPage(emptyList(), false)
        val doc = Jsoup.parse(json.get("html")?.takeUnless { it.isJsonNull }?.asString.orEmpty())
        nextCursor = json.get("next_cursor")?.takeUnless { it.isJsonNull }?.asString
        val hasMore = json.get("has_more")?.takeUnless { it.isJsonNull }?.asBoolean ?: false
        return MangasPage(parseCards(doc), hasMore)
    }

    private fun parseCards(doc: Document): List<SManga> {
        return doc.select("div.img-card-wrapper").mapNotNull { card ->
            val link = card.selectFirst("a[href*='/toon/w/']") ?: return@mapNotNull null
            val title = link.selectFirst("img")?.attr("alt")
                ?.takeIf { it.isNotBlank() }
                ?: link.text().trim()
            if (title.isBlank()) return@mapNotNull null
            SManga.create().apply {
                this.title = title
                setUrlWithoutDomain(link.absUrl("href"))
                thumbnail_url = link.selectFirst("img")?.absUrl("src").orEmpty()
            }
        }
    }

    // =========================== Manga Details ===========================

    override fun getMangaUrl(manga: SManga): String =
        if (manga.url.startsWith("http")) manga.url else baseUrl + manga.url

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body?.string() ?: throw IOException("Empty response body"))
        return SManga.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
            thumbnail_url = doc.selectFirst("img[data-cover]")?.absUrl("src")
                ?.ifBlank { null }
                ?: doc.selectFirst("meta[property='og:image']")?.attr("content").orEmpty()
            description = doc.selectFirst("p.line-clamp-3")?.text()?.trim()
            genre = doc.select("span.genre-badge-large").joinToString(", ") { it.text().trim() }
                .takeIf { it.isNotBlank() }
        }
    }

    // ============================== Chapters =============================

    override fun getChapterUrl(chapter: SChapter): String =
        if (chapter.url.startsWith("http")) chapter.url else baseUrl + chapter.url

    override fun chapterListRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = Jsoup.parse(response.body?.string() ?: return emptyList())
        val seriesTitle = doc.selectFirst("h1")?.text()?.trim().orEmpty()
        return doc.select("a.text-sm[href*='/c/']").map { a ->
            SChapter.create().apply {
                url = a.absUrl("href")
                val fullName = a.text().trim()
                name = if (seriesTitle.isNotBlank() && fullName.startsWith(seriesTitle)) {
                    fullName.removePrefix(seriesTitle).trim()
                } else {
                    fullName
                }
                chapter_number = CHAPTER_NUMBER_REGEX.find(name)
                    ?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: 0f
                date_upload = dateFormat.tryParse(
                    a.parents().asList().firstNotNullOfOrNull { it.selectFirst("span.text-xs.text-gray-400") }
                        ?.text().orEmpty(),
                )
            }
        }
    }

    // =============================== Pages ===============================

    override fun pageListRequest(chapter: SChapter): Request =
        GET(getChapterUrl(chapter), headers)

    override fun pageListParse(response: Response): List<Page> {
        val doc = Jsoup.parse(response.body?.string() ?: return emptyList())
        return doc.select("#chapters img[data-src]").mapIndexed { i, img ->
            Page(i, imageUrl = img.attr("data-src"))
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        private const val GENERAL_PATH = "/toon/general"

        private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
        private val INFINITE_SCROLL_REGEX = Regex("""infiniteScroll\((true|false),\s*'([^']*)'\)""")
        private val CHAPTER_NUMBER_REGEX = Regex("""(\d+)\s*화""")

        private fun SimpleDateFormat.tryParse(s: String): Long =
            runCatching { parse(s.trim()).time }.getOrDefault(0L)
    }
}
