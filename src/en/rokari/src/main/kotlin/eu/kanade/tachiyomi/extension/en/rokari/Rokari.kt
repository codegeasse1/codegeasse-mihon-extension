package eu.kanade.tachiyomi.extension.en.rokari

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
 * Rokari (https://rokaricomics.com) — MangaNova theme (WordPress).
 * Romance/shoujo-leaning manhwa aggregator with a coin paywall on the
 * newest chapter(s) of a series (those pages show a "locked" screen and
 * will yield no images; older chapters are free).
 *
 *     Popular : /manga/?page=N&order=popular
 *     Latest  : /manga/?page=N&order=update
 *     Search  : /?s=<q>&post_type=wp-manga            (page 1)
 *               /page/N/?s=<q>&post_type=wp-manga     (pages > 1)
 *     Manga   : /manga/<slug>                 -> h1.entry-title, .thumb img,
 *               table.infotable rows (Status/Author), .seriestugenre a genres,
 *               .entry-content-single description
 *     Chapters: .eplister li .eph-num a       -> chapter URL /<slug>-chapter-<n>/
 *     Chapter : /<slug>-chapter-<n>/          -> div#readerarea img
 */
class Rokari : HttpSource() {

    override val name = "Rokari"
    override val baseUrl = "https://rokaricomics.com"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", BROWSER_UA)
        .set("Referer", "$baseUrl/")

    // =========================== Browse & Search =========================

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/manga/?page=$page&order=popular", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asDocument()
        val mangas = document.select(".listupd .bs .bsx").mapNotNull(::mangaFromCard)
        val hasNextPage = document.selectFirst(".hpage .r, .pagination .next") != null
        return MangasPage(mangas, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/manga/?page=$page&order=update", headers)

    override fun latestUpdatesParse(response: Response): MangasPage =
        popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encoded = URLEncoder.encode(query, "utf-8")
        val url = if (page == 1) {
            "$baseUrl/?s=$encoded&post_type=wp-manga"
        } else {
            "$baseUrl/page/$page/?s=$encoded&post_type=wp-manga"
        }
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage =
        popularMangaParse(response)

    private fun mangaFromCard(card: Element): SManga? {
        val link = card.selectFirst("a") ?: return null
        val img = link.selectFirst("img") ?: return null
        return SManga.create().apply {
            setUrlWithoutDomain(link.absUrl("href"))
            title = link.attr("title")
                .takeIf { it.isNotBlank() }
                ?: link.text()
                ?: img.attr("title")
                ?: img.attr("alt")
                ?: ""
            thumbnail_url = imageFromElement(img) ?: ""
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
            title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
            setUrlWithoutDomain(response.request.url.toString())
            document.selectFirst(".thumb img")?.let { thumbnail_url = imageFromElement(it) ?: "" }
            fun infoRow(label: String): String? =
                document.select("table.infotable tr").firstOrNull { row ->
                    row.selectFirst("td")?.text()?.trim()?.equals(label, true) == true
                }?.select("td")?.last()?.text()?.trim()
            val statusText = infoRow("Status")
            status = when {
                statusText?.contains("ongoing", true) == true -> SManga.ONGOING
                statusText?.contains("completed", true) == true -> SManga.COMPLETED
                statusText?.contains("hiatus", true) == true -> SManga.ON_HIATUS
                statusText?.contains("cancel", true) == true -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
            infoRow("Author")?.takeIf { it.isNotBlank() && it != "n/a" }?.let { author = it }
            infoRow("Artist")?.takeIf { it.isNotBlank() && it != "n/a" }?.let { artist = it }
            document.select(".seriestugenre a").eachText().joinToString().takeIf { it.isNotBlank() }?.let { genre = it }
            document.selectFirst(".entry-content-single p")?.let { description = it.text() }
        }
    }

    // ============================== Chapters =============================

    override fun getChapterUrl(chapter: SChapter): String =
        if (chapter.url.startsWith("http")) chapter.url else baseUrl + chapter.url

    override fun chapterListRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asDocument()
        return document.select(".eplister li").mapNotNull { li ->
            val link = li.selectFirst(".eph-num a") ?: return@mapNotNull null
            SChapter.create().apply {
                url = link.absUrl("href")
                name = li.selectFirst(".chapternum")?.text()?.trim().orEmpty().ifBlank { link.text() }
                chapter_number = CHAPTER_NUMBER_REGEX.find(name)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
                date_upload = li.selectFirst(".chapterdate")?.text()
                    ?.let { text -> runCatching { DATE_FORMAT.parse(text)?.time ?: 0L }.getOrDefault(0L) }
                    ?: 0L
            }
        }
    }

    // =============================== Pages ===============================

    override fun pageListRequest(chapter: SChapter): Request =
        GET(getChapterUrl(chapter), headers)

    override fun pageListParse(response: Response): List<Page> {
        val chapterUrl = response.request.url.toString()
        val html = response.body?.string() ?: throw IOException("Empty response body")
        val document = Jsoup.parse(html, chapterUrl)

        val htmlPages = document.select("#readerarea img").mapNotNull { element ->
            imageFromElement(element)
        }
        if (htmlPages.isNotEmpty()) {
            return htmlPages.mapIndexed { index, url -> Page(index, chapterUrl, url) }
        }

        val match = IMAGE_LIST_REGEX.find(html) ?: return emptyList()
        val imageList = runCatching { JsonParser.parseString(match.groupValues[1]).asJsonArray }.getOrNull()
            ?: return emptyList()

        return imageList.mapIndexed { index, element ->
            Page(index, chapterUrl, element.asString)
        }
    }

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl ?: page.url, headers.newBuilder().set("Referer", "$baseUrl/").build())

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

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

        private val CHAPTER_NUMBER_REGEX = Regex("""(\d+(?:\.\d+)?)""")
        private val IMAGE_LIST_REGEX = Regex("""\"images\"\s*:\s*(\[.*?])""")

        private val DATE_FORMAT = SimpleDateFormat("MMMM dd, yyyy", Locale.US)
    }
}
