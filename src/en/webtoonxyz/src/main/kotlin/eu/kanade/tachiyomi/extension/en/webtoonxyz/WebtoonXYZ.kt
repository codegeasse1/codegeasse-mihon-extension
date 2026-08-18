package eu.kanade.tachiyomi.extension.en.webtoonxyz

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

/*
 * WebtoonXYZ (https://www.webtoon.xyz) — a MADARA WordPress reader (custom /
 *read/ slug routing). Browse and search pages both render card lists:
 *
 *     Browse  : /webtoons/?m_orderby=<latest|views>      (page 1)
 *               /webtoons/page/N/?m_orderby=<latest|views>
 *               Cards: div.page-item-detail  (cover in img[data-src])
 *     Search  : /?s=<query>&post_type=wp-manga&m_orderby=latest
 *               /page/N/?s=<query>&post_type=wp-manga&m_orderby=latest
 *               Cards: div.c-tabs-item__content
 *     Details : /read/<slug>/  -> summary, genres, status + li.wp-manga-chapter
 *     Pages   : /read/<slug>/chapter-N/  -> div.reading-content img[data-src]
 *               (images on cdn8.webtoon.xyz)
 *
 * Page 1 URLs have no /page/N/ segment; pagination is a
 * "nav.paging-navigation .nav-previous a" (Older Posts) link.
 */
class WebtoonXYZ : HttpSource() {

    override val name = "WebtoonXYZ"
    override val baseUrl = "https://www.webtoon.xyz"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", BROWSER_UA)
        .set("Referer", "$baseUrl/")

    // =========================== Browse & Search =========================

    override fun popularMangaRequest(page: Int): Request = browseRequest(page, "views")

    override fun popularMangaParse(response: Response): MangasPage = parseCatalog(response)

    override fun latestUpdatesRequest(page: Int): Request = browseRequest(page, "latest")

    override fun latestUpdatesParse(response: Response): MangasPage = parseCatalog(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET(searchUrl(page, query), headers)

    override fun searchMangaParse(response: Response): MangasPage = parseCatalog(response)

    override fun getFilterList(): FilterList = FilterList()

    private fun browseRequest(page: Int, order: String): Request =
        GET(
            if (page <= 1) "$baseUrl/webtoons/?m_orderby=$order"
            else "$baseUrl/webtoons/page/$page/?m_orderby=$order",
            headers,
        )

    private fun searchUrl(page: Int, query: String): String {
        val encoded = URLEncoder.encode(query, "utf-8").replace("+", "%20")
        val base = "$baseUrl/?s=$encoded&post_type=wp-manga&m_orderby=latest"
        return if (page <= 1) base else "$baseUrl/page/$page/?s=$encoded&post_type=wp-manga&m_orderby=latest"
    }

    private fun parseCatalog(response: Response): MangasPage {
        val doc = Jsoup.parse(
            response.body?.string() ?: throw IOException("Empty response body"),
            baseUrl,
        )
        val mangas = doc.select("div.page-item-detail, div.c-tabs-item__content")
            .mapNotNull(::mangaFromCard)
        val hasNext = doc.selectFirst("nav.paging-navigation .nav-previous a") != null
        return MangasPage(mangas, hasNext)
    }

    private fun mangaFromCard(card: Element): SManga? {
        val link = card.selectFirst("a[href*='/read/']") ?: return null
        val url = link.absUrl("href").removePrefix(baseUrl)
        if (url.isBlank() || "/read/" !in url) return null
        val title = link.attr("title").ifBlank {
            card.selectFirst(".post-title a")?.text().orEmpty()
        }.trim()
        if (title.isBlank()) return null
        val img = card.selectFirst("img[data-src]")
        return SManga.create().apply {
            this.url = url
            this.title = title
            thumbnail_url = coverUrl(img)
        }
    }

    /** The card's srcset is sorted by width; use the largest, else data-src. */
    private fun coverUrl(img: Element?): String {
        if (img == null) return ""
        val srcset = img.attr("data-srcset")
        if (srcset.isNotBlank()) {
            val largest = srcset.split(",")
                .lastOrNull()
                ?.trim()
                ?.substringBefore(" ")
                ?.trim()
            if (!largest.isNullOrBlank()) return largest
        }
        return img.attr("data-src").trim()
    }

    // =========================== Manga Details ===========================

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET("$baseUrl${manga.url}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(
            response.body?.string() ?: throw IOException("Empty response body"),
            baseUrl,
        )
        return SManga.create().apply {
            url = response.request.url.toString().removePrefix(baseUrl)
            title = doc.selectFirst(".post-title h1")?.text()?.trim().orEmpty()
            thumbnail_url = doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?.trim()
                .orEmpty()
                .ifBlank {
                    doc.selectFirst(".summary_image img")?.absUrl("src").orEmpty()
                }
            description = doc.selectFirst(".description-summary .summary__content")?.text()?.trim().orEmpty()
            genre = doc.select(".genres-content a").joinToString(", ") { it.text() }
            author = doc.select(".author-content a").joinToString(", ") { it.text() }
            artist = doc.select(".artist-content a").joinToString(", ") { it.text() }
            status = parseStatus(detailValue(doc, "Status"))
        }
    }

    private fun detailValue(doc: Document, heading: String): String? =
        doc.select(".post-content_item")
            .firstOrNull { it.selectFirst(".summary-heading h5")?.text() == heading }
            ?.selectFirst(".summary-content")
            ?.text()
            ?.trim()

    private fun parseStatus(value: String?): Int {
        if (value == null) return SManga.UNKNOWN
        return when {
            "ongoing" in value.lowercase() -> SManga.ONGOING
            "completed" in value.lowercase() -> SManga.COMPLETED
            "hiatus" in value.lowercase() -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
    }

    // ============================== Chapters =============================

    override fun chapterListRequest(manga: SManga): Request =
        GET("$baseUrl${manga.url}", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = Jsoup.parse(
            response.body?.string() ?: throw IOException("Empty response body"),
            baseUrl,
        )
        return doc.select("li.wp-manga-chapter").mapNotNull { item ->
            val link = item.selectFirst("a") ?: return@mapNotNull null
            val url = link.absUrl("href").removePrefix(baseUrl)
            if (url.isBlank()) return@mapNotNull null
            val name = link.text().trim()
            SChapter.create().apply {
                this.url = url
                this.name = name
                chapter_number = chapterNumber(name)
                date_upload = item.selectFirst(".chapter-release-date a")?.attr("title")
                    ?.let(::parseRelativeDate) ?: 0L
            }
        }
    }

    private fun chapterNumber(name: String): Float {
        val match = Regex("""chapter\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(name)
        return match?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
    }

    /** Parses relative dates like "2 hours ago" or "3 days ago". */
    private fun parseRelativeDate(value: String): Long {
        if (value.isBlank()) return 0L
        val match = Regex(
            """(\d+)\s*(second|minute|hour|day|week|month|year)s?\s*ago""",
            RegexOption.IGNORE_CASE,
        ).find(value) ?: return 0L
        val count = match.groupValues[1].toLongOrNull() ?: return 0L
        val unitMillis = when (match.groupValues[2].lowercase()) {
            "second" -> 1_000L
            "minute" -> 60_000L
            "hour" -> 3_600_000L
            "day" -> 86_400_000L
            "week" -> 604_800_000L
            "month" -> 2_592_000_000L
            "year" -> 31_536_000_000L
            else -> return 0L
        }
        return System.currentTimeMillis() - count * unitMillis
    }

    // =============================== Pages ===============================

    override fun pageListRequest(chapter: SChapter): Request =
        GET("$baseUrl${chapter.url}", headers)

    override fun pageListParse(response: Response): List<Page> {
        val doc = Jsoup.parse(
            response.body?.string() ?: throw IOException("Empty response body"),
            baseUrl,
        )
        val chapterUrl = response.request.url.toString()
        return doc.select("div.reading-content img").mapIndexedNotNull { index, img ->
            val src = img.attr("data-src").trim().ifBlank { img.absUrl("src") }
            if (src.isBlank() || src.contains(LAZY_PLACEHOLDER)) null
            else Page(index, chapterUrl, src)
        }
    }

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl ?: page.url, headers)

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        private const val LAZY_PLACEHOLDER = "dflazy.jpg"
    }
}
