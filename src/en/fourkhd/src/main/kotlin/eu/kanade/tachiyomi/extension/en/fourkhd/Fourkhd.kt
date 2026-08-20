package eu.kanade.tachiyomi.extension.en.fourkhd

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/*
 * 4KHD (https://www.4khd.com — this extension talks to the mirror
 * https://iftye.uuss.uk) — a WordPress photo-set site. All internal links are
 * absolute and point at the canonical 4khd.com domain, so they are rewritten
 * back to the mirror base.
 *
 *     Latest/Browse : /?query-3-page=N        -> li.wp-block-post grid (block query pagination,
 *                                                thousands of pages — unlimited until the end)
 *     Search        : /?s=<q>                 -> li.wp-block-post grid (single page, site offers no more)
 *     Details       : /content/<n>/<slug>.html
 *     Pages         : .entry-content img; long sets are split across /2 /3 … sub-pages
 */
class Fourkhd : HttpSource() {

    override val name = "4KHD"
    override val baseUrl = "https://iftye.uuss.uk"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder =
        super.headersBuilder()
            .set("User-Agent", BROWSER_UA)
            .set("Referer", "$baseUrl/")

    override fun getFilterList(): FilterList = FilterList()

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/?query-3-page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage =
        parseListing(response, pageOf(response))

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/?query-3-page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage =
        parseListing(response, pageOf(response))

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        return GET("$baseUrl/?s=$q", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val doc = response.asJsoup()
        return MangasPage(parseCards(doc), hasNextPage = false)
    }

    private fun parseListing(response: Response, page: Int): MangasPage {
        val doc = response.asJsoup()
        return MangasPage(parseCards(doc), hasNextPage = hasNextPage(doc, page))
    }

    private fun parseCards(doc: Document): List<SManga> =
        doc.select("li.wp-block-post").mapNotNull { li ->
            val a = li.selectFirst(".wp-block-post-title a[href]")
                ?: li.selectFirst(".wp-block-post-featured-image a[href]")
                ?: return@mapNotNull null
            val title = a.text().trim()
            val url = pageUrl(a.absUrl("href"))
            if (title.isEmpty() || url.isEmpty()) return@mapNotNull null
            SManga.create().apply {
                this.url = url
                this.title = title
                thumbnail_url = li.selectFirst(".wp-block-post-featured-image img")?.let(::absSrc)
            }
        }

    // ========================== Manga Details =============================

    override fun getMangaUrl(manga: SManga): String = manga.url

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()
        val title = doc.selectFirst(".wp-block-post-title")?.text()?.trim()
            ?: doc.title().substringBefore(" - 4KHD").trim()
        return SManga.create().apply {
            this.title = title
            url = response.request.url.toString()
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            this.status = SManga.UNKNOWN
            description = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim().orEmpty()
        }
    }

    // ============================= Chapters ===============================

    override fun chapterListRequest(manga: SManga): Request =
        GET(manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = response.asJsoup()
        return listOf(
            SChapter.create().apply {
                url = response.request.url.toString()
                name = "Photo Set"
                date_upload = publishedDate(doc)
                chapter_number = 1f
            },
        )
    }

    override fun getChapterUrl(chapter: SChapter): String = chapter.url

    // =============================== Pages ================================

    override fun pageListRequest(chapter: SChapter): Request =
        GET(chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        val doc = response.asJsoup()
        val images = buildList {
            pageImages(doc)?.let { addAll(it) }
        }
        // Long photo sets are split across /2 /3 … sub-pages — fetch them all.
        val extraPages = doc.select(".page-links li.numpages a.page-numbers[href]").mapNotNull { a ->
            pageUrl(a.absUrl("href")).takeIf { it.isNotEmpty() }
        }
        for (href in extraPages) {
            runCatching {
                client.newCall(GET(href, headers)).execute().use { resp ->
                    pageImages(resp.asJsoup())?.let { images.addAll(it) }
                }
            }
        }
        return images.distinct().mapIndexed { index, url -> Page(index, url, url) }
    }

    private fun pageImages(doc: Document): List<String>? {
        val imgs = doc.select(".entry-content img")
        if (imgs.isEmpty()) return null
        return imgs.mapNotNull { absSrc(it) }
    }

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl!!, headers)

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException()

    // ============================= Utilities ==============================

    // All in-page links are canonical 4khd.com URLs — point them at the mirror.
    private fun pageUrl(raw: String): String = when {
        raw.startsWith(CANONICAL_ROOT) -> baseUrl + raw.removePrefix(CANONICAL_ROOT)
        raw.startsWith(CANONICAL_ROOT_ALT) -> baseUrl + raw.removePrefix(CANONICAL_ROOT_ALT)
        else -> raw
    }

    private fun absSrc(img: Element): String? {
        val attr = when {
            img.attr("data-src").isNotBlank() -> "data-src"
            img.attr("src").isNotBlank() -> "src"
            else -> return null
        }
        return img.absUrl(attr).ifBlank { null }
    }

    private fun pageOf(response: Response): Int {
        val m = Regex("""query-\d+-page=(\d+)""").find(response.request.url.toString())
        return m?.groupValues?.get(1)?.toIntOrNull() ?: 1
    }

    private fun hasNextPage(doc: Document, page: Int): Boolean =
        doc.select(".wp-block-query-pagination a.page-numbers[href]").any { a ->
            val m = Regex("""query-\d+-page=(\d+)""").find(a.attr("href"))
            (m?.groupValues?.get(1)?.toIntOrNull() ?: 0) > page
        }

    private fun publishedDate(doc: Document): Long {
        val json = doc.selectFirst("script[type=application/ld+json]")?.data() ?: return 0L
        val m = Regex("\"datePublished\":\"?([^\",}]+)").find(json) ?: return 0L
        return parseIsoDate(m.groupValues[1])
    }

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        private const val CANONICAL_ROOT = "https://www.4khd.com"
        private const val CANONICAL_ROOT_ALT = "https://4khd.com"
    }
}

private fun parseIsoDate(text: String): Long {
    val m = Regex("""(\d{4})-(\d{2})-(\d{2})""").find(text) ?: return 0L
    val year = m.groupValues[1].toIntOrNull() ?: return 0L
    val month = m.groupValues[2].toIntOrNull() ?: return 0L
    val day = m.groupValues[3].toIntOrNull() ?: return 0L
    val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
    cal.clear()
    cal.set(year, month - 1, day)
    return cal.timeInMillis
}
