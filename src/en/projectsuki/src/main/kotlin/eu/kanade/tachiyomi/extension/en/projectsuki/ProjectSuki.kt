package eu.kanade.tachiyomi.extension.en.projectsuki

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URLEncoder

/*
 * Project Suki (https://projectsuki.com) — a custom PHP aggregator. All pages
 * are plain SSR HTML (Cloudflare in front, but content renders fine to plain
 * HTTP clients).
 *
 *     Browse  : /browse            (page 1, alphabetical title list)
 *               /browse/<N>        (next pages; the URL is 0-indexed, so page
 *                                   2 is /browse/1 — the "Next" button points
 *                                   there). 30 books per page.
 *     Latest  : /                 (latest-updates feed on the homepage, no paging)
 *     Search  : /search?q=<q>&page=<N>   (also 0-indexed: page 1 = page=0)
 *     Manga   : /book/<id>        -> h2[itemprop=title], img.img-thumbnail,
 *               rows .row.py-1 with a .strong label (Author / Artist / Status),
 *               div[itemprop=genre] a, #descriptionCollapse. Chapters live in
 *               table.table tbody tr.row a[href^="/read/"] (ownText is the
 *               chapter name, e.g. "Chapter 156").
 *     Reader  : /read/<book>/<chap>/1 renders page 1 inside .strip-reader img;
 *               the remaining pages come from POST /callpage with the JSON body
 *               {"bookid":<book>,"chapterid":<chap>,"first":"true"} and
 *               X-Requested-With: XMLHttpRequest. The response is a JSON object
 *               {"src":"<img ...>"} whose src HTML holds every remaining page
 *               image (paths are /images/gallery/<book>/<hash>/NNN?). Images
 *               are indexed by their trailing zero-padded page number.
 */
class ProjectSuki : HttpSource() {

    override val name = "Project Suki"
    override val baseUrl = "https://projectsuki.com"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", BROWSER_UA)
        .set("Referer", "$baseUrl/browse")

    // =========================== Browse & Search =========================

    override fun popularMangaRequest(page: Int): Request =
        GET(browseUrl(page), headers)

    override fun popularMangaParse(response: Response): MangasPage =
        parseList(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET(baseUrl, headers)

    override fun latestUpdatesParse(response: Response): MangasPage =
        parseList(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/search?q=${URLEncoder.encode(query, "utf-8")}&page=${page - 1}", headers)

    override fun searchMangaParse(response: Response): MangasPage =
        parseList(response)

    private fun browseUrl(page: Int): String =
        if (page == 1) "$baseUrl/browse" else "$baseUrl/browse/${page - 1}"

    private fun parseList(response: Response): MangasPage {
        val document = response.asDocument()
        val mangas = document.select("a[href^=\"/book/\"]")
            .mapNotNull(::mangaFromLink)
            .distinctBy { it.url }
        val hasNextPage = document.select(".pagination a").any { it.text().trim() == "Next" }
        return MangasPage(mangas, hasNextPage)
    }

    private fun mangaFromLink(link: Element): SManga? {
        val href = link.attr("abs:href")
        if (!href.startsWith("$baseUrl/book/")) return null
        val img = link.selectFirst("img") ?: return null
        return SManga.create().apply {
            setUrlWithoutDomain(href)
            title = link.attr("aria-label")
                .takeIf { it.isNotBlank() }
                ?: img.attr("title")
                ?: img.attr("alt")
            thumbnail_url = img.absUrl("src")
        }
    }

    // =========================== Manga Details ===========================

    override fun getMangaUrl(manga: SManga): String =
        if (manga.url.startsWith("http")) manga.url else baseUrl + manga.url

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asDocument()
        return SManga.create().apply {
            title = document.selectFirst("h2[itemprop=title]")?.text()?.trim().orEmpty()
            setUrlWithoutDomain(response.request.url.toString())
            document.selectFirst("img.img-thumbnail")?.let { thumbnail_url = it.absUrl("src") }
            rowLinks(document, "Author").takeIf { it.isNotBlank() }?.let { author = it }
            rowLinks(document, "Artist").takeIf { it.isNotBlank() }?.let { artist = it }
            document.select("div[itemprop=genre] a").eachText().joinToString().takeIf { it.isNotBlank() }?.let { genre = it }
            val statusText = rowValue(document, "Status")?.lowercase()
            status = when {
                statusText?.contains("ongoing") == true -> SManga.ONGOING
                statusText?.contains("completed") == true -> SManga.COMPLETED
                statusText?.contains("hiatus") == true -> SManga.ON_HIATUS
                statusText?.contains("cancel") == true -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
            document.selectFirst("#descriptionCollapse")?.let { description = it.text() }
        }
    }

    private fun rowValue(document: Document, label: String): String? =
        document.select(".row.py-1")
            .firstOrNull { it.selectFirst(".strong")?.text()?.trim() == "$label:" }
            ?.selectFirst(".col-8.col-md-9")?.text()?.trim()

    private fun rowLinks(document: Document, label: String): String =
        document.select(".row.py-1")
            .firstOrNull { it.selectFirst(".strong")?.text()?.trim() == "$label:" }
            ?.select(".col-8.col-md-9 a")
            ?.eachText()
            ?.joinToString()
            .orEmpty()

    // ============================== Chapters =============================

    override fun getChapterUrl(chapter: SChapter): String =
        if (chapter.url.startsWith("http")) chapter.url else baseUrl + chapter.url

    override fun chapterListRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asDocument()
        return document.select("table.table tbody tr.row a[href^=\"/read/\"]").mapNotNull { link ->
            SChapter.create().apply {
                url = link.attr("abs:href")
                name = link.ownText().trim().ifBlank { link.text().trim() }
                chapter_number = CHAPTER_NUMBER_REGEX.find(name)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
            }
        }.distinctBy { it.url }
    }

    // =============================== Pages ===============================

    override fun pageListRequest(chapter: SChapter): Request =
        GET(getChapterUrl(chapter), headers)

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asDocument()
        val chapterUrl = response.request.url.toString()
        val found = mutableListOf<Pair<Int, String>>()

        document.select(".strip-reader img").firstOrNull()?.let { element ->
            element.absUrl("src").takeIf { it.contains(GALLERY_PREFIX) }?.let {
                found += pageNumber(it) to it
            }
        }

        val match = CHAPTER_URL_REGEX.find(chapterUrl)
        val bookId = match?.groupValues?.get(1)
        val chapterId = match?.groupValues?.get(2)
        if (bookId != null && chapterId != null) {
            val body = "{\"bookid\":$bookId,\"chapterid\":$chapterId,\"first\":\"true\"}"
                .toRequestBody(CALLPAGE_MEDIA_TYPE)
            val request = POST("$baseUrl/callpage", callpageHeaders(), body)
            client.newCall(request).execute().use { callpageResponse ->
                val json = callpageResponse.body?.string()
                if (json != null) {
                    val srcHtml = try {
                        JsonParser.parseString(json).asJsonObject.get("src").asString
                    } catch (e: Exception) {
                        null
                    }
                    if (srcHtml != null) {
                        val fragment = Jsoup.parseBodyFragment(srcHtml, baseUrl)
                        fragment.select("img").forEach { element ->
                            val url = element.absUrl("src")
                            if (url.contains(GALLERY_PREFIX) && found.none { it.second == url }) {
                                found += pageNumber(url) to url
                            }
                        }
                    }
                }
            }
        }

        return found.sortedBy { it.first }
            .mapIndexed { index, (_, url) -> Page(index, chapterUrl, url) }
    }

    private fun callpageHeaders() = headers.newBuilder()
        .set("X-Requested-With", "XMLHttpRequest")
        .build()

    private fun pageNumber(imageUrl: String): Int =
        PAGE_NUMBER_REGEX.find(imageUrl)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl ?: page.url, headers)

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ========================== Helpers ==================================

    private fun Response.asDocument(): Document {
        val body = body?.string() ?: throw IOException("Empty response body")
        return Jsoup.parse(body, request.url.toString())
    }

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        private const val GALLERY_PREFIX = "/images/gallery/"

        private val CALLPAGE_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val CHAPTER_NUMBER_REGEX = Regex("""(\d+(?:\.\d+)?)""")

        private val CHAPTER_URL_REGEX = Regex("""/read/(\d+)/(\d+)/""")

        private val PAGE_NUMBER_REGEX = Regex("""/images/gallery/[^/]+/[^/]+/(\d+)\??$""")
    }
}
