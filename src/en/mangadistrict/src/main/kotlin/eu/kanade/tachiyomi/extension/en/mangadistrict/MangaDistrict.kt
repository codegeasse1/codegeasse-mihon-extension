package eu.kanade.tachiyomi.extension.en.mangadistrict

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/*
 * Manga District (https://mangadistrict.com) — a Wordpress/Madara site hosting
 * adult manhwa/webtoons (isNsfw). Madara-standard markup:
 *
 *     Popular : /series/?m_orderby=views      (paged: /series/page/N/?m_orderby=views)
 *     Latest  : /series/?m_orderby=modified
 *     Search  : /?s=<q>&post_type=wp-manga    (server-rendered, single page)
 *     Details : post page -> div.post-title h1, div.summary_image img,
 *               div.summary__content (description), div.post-content_item rows
 *               (Author(s)/Genre(s)/Status).
 *     Chapters: li.wp-manga-chapter -> a (newest first; date in
 *               `.chapter-release-date .timediff` — a[title] holds relative
 *               dates like "7 hours ago", plain text holds "MMMM d, yyyy").
 *     Pages   : chapter page -> .reading-content .page-break img. The first
 *               image (id="image-99999", cdn.../assets/publication/.../000001.jpg)
 *               is a site promo banner, not manga art, so it is skipped.
 */
class MangaDistrict : HttpSource() {

    override val name = "Manga District"
    override val baseUrl = "https://mangadistrict.com"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", BROWSER_UA)
        .set("Referer", "$baseUrl/")
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")

    // =========================== Browse ===========================

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/series/${searchPage(page)}?m_orderby=views", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseMangaList(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/series/${searchPage(page)}?m_orderby=modified", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseMangaList(response)

    // =========================== Search ===========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // Search results come back on a single page (posts_per_page=30 and this
        // site has <30 results per query); pages beyond 1 return the archive,
        // which searchMangaParse discards.
        if (page > 1) return GET("$baseUrl/series/", headers)
        val q = URLEncoder.encode(query, "utf-8")
        return GET("$baseUrl/?s=$q&post_type=wp-manga", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        if (response.request.url.queryParameter("s").isNullOrBlank()) {
            return MangasPage(emptyList(), false)
        }
        return parseMangaList(response)
    }

    override fun getFilterList(): FilterList = FilterList()

    private fun parseMangaList(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("div.page-item-detail.manga").mapNotNull { element ->
            val titleEl = element.selectFirst("div.post-title a") ?: return@mapNotNull null
            val url = titleEl.absUrl("href")
            if (url.isBlank()) return@mapNotNull null
            SManga.create().apply {
                title = titleEl.ownText().ifBlank { titleEl.text() }.trim()
                setUrlWithoutDomain(url)
                thumbnail_url = element.selectFirst("img")?.let { imageUrl(it) }
            }
        }
        return MangasPage(mangas, hasNextPage(document, currentPage(response)))
    }

    private fun hasNextPage(document: Document, page: Int): Boolean {
        if (document.selectFirst("link[rel=next]") != null) return true
        val lastPage = document.selectFirst(".wp-pagenavi a.last")?.attr("href")
            ?.let { Regex("page/(\\d+)/").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        return lastPage != null && page < lastPage
    }

    private fun currentPage(response: Response): Int {
        val segments = response.request.url.pathSegments
        val idx = segments.indexOf("page")
        return if (idx != -1) segments.getOrNull(idx + 1)?.toIntOrNull() ?: 1 else 1
    }

    private fun searchPage(page: Int): String = if (page == 1) "" else "page/$page/"

    // =========================== Details ==========================

    override fun getMangaUrl(manga: SManga): String =
        if (manga.url.startsWith("http")) manga.url else baseUrl + manga.url

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), headers)

    override fun mangaDetailsParse(response: Response): SManga = SManga.create().apply {
        val document = response.asJsoup()
        title = document.selectFirst("div.post-title h1, div.post-title h3, #manga-title > h1")
            ?.ownText()?.trim() ?: ""
        thumbnail_url = document.selectFirst("div.summary_image img")?.let { imageUrl(it) }
        author = document.select("div.author-content > a")
            .eachText()
            .filter { it.isNotBlank() && !it.contains(updatingRegex) }
            .joinToString()
        genre = document.select("div.genres-content a").eachText().joinToString()
        status = document.selectFirst("div.summary-heading:contains(Status) + div")
            ?.text()?.trim()?.let { parseStatus(it) } ?: SManga.UNKNOWN
        document.selectFirst("div.summary__content")?.let { desc ->
            description = if (desc.select("p").text().isNotBlank()) {
                desc.select("p").joinToString("\n\n") { it.text().trim() }.trim()
            } else {
                desc.text().trim()
            }
        }
    }

    // =========================== Chapters =========================

    override fun getChapterUrl(chapter: SChapter): String =
        if (chapter.url.startsWith("http")) chapter.url else baseUrl + chapter.url

    override fun chapterListRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), headers)

    override fun chapterListParse(response: Response): List<SChapter> = response.asJsoup()
        .select("li.wp-manga-chapter")
        .mapNotNull { element ->
            val link = element.selectFirst("a") ?: return@mapNotNull null
            val url = link.absUrl("href")
            if (url.isBlank()) return@mapNotNull null
            SChapter.create().apply {
                name = link.text().trim().ifBlank { link.attr("title") }
                setUrlWithoutDomain(url)
                chapter_number = parseChapterNumber(name)
                date_upload = chapterDate(element)
            }
        }

    private fun chapterDate(element: Element): Long {
        val timediff = element.selectFirst(".chapter-release-date .timediff") ?: return 0L
        val title = timediff.selectFirst("a")?.attr("title")?.trim()
        if (!title.isNullOrBlank()) return parseDate(title)
        val text = timediff.text().trim()
        return if (text.isBlank()) 0L else parseDate(text)
    }

    // ============================ Pages ===========================

    override fun pageListRequest(chapter: SChapter): Request =
        GET(getChapterUrl(chapter), headers)

    override fun pageListParse(response: Response): List<Page> = response.asJsoup()
        .select(".reading-content .page-break img")
        .mapIndexedNotNull { index, img ->
            if (img.id() == "image-99999") return@mapIndexedNotNull null
            val imageUrl = imageUrl(img)
            if (imageUrl.isBlank()) null
            else Page(index, url = imageUrl, imageUrl = imageUrl)
        }

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl ?: page.url, headers)

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException("imageUrl is resolved in pageListParse")

    // =========================== Helpers ==========================

    private fun imageUrl(element: Element): String = when {
        element.hasAttr("data-default-src") -> element.absUrl("data-default-src").trim()
        element.hasAttr("data-src") -> element.absUrl("data-src").trim()
        element.hasAttr("data-lazy-src") -> element.absUrl("data-lazy-src").trim()
        element.hasAttr("srcset") -> srcsetBest(element.attr("srcset"))
        else -> element.absUrl("src").trim()
    }

    /** Picks the largest image from a `srcset` list. */
    private fun srcsetBest(srcset: String): String {
        var best = ""
        var bestW = -1
        for (part in srcset.split(",")) {
            val tokens = part.trim().split(Regex("\\s+"))
            if (tokens.isEmpty() || !tokens[0].startsWith("http")) continue
            val url = tokens[0]
            val w = tokens.getOrNull(1)?.removeSuffix("w")?.toIntOrNull() ?: -1
            if (w > bestW) {
                best = url
                bestW = w
            }
        }
        return if (bestW >= 0) best else srcset.split(",").lastOrNull()?.trim()?.substringBefore(" ") ?: ""
    }

    private fun parseStatus(text: String): Int = when {
        text.contains("completed", true) -> SManga.COMPLETED
        text.contains("ongoing", true) -> SManga.ONGOING
        text.contains("hiatus", true) -> SManga.ON_HIATUS
        text.contains("cancel", true) -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    private fun parseChapterNumber(name: String): Float =
        Regex("\\d+(?:\\.\\d+)?").findAll(name).lastOrNull()?.value?.toFloatOrNull() ?: -1f

    private fun parseDate(text: String): Long {
        val lower = text.lowercase()
        return when {
            lower.contains("ago") -> parseRelativeDate(lower)
            lower.contains("yesterday") -> midnight(daysAgo = 1)
            lower.contains("today") -> midnight(daysAgo = 0)
            else -> runCatching { DATE_FORMAT.parse(text)?.time ?: 0L }.getOrDefault(0L)
        }
    }

    private fun parseRelativeDate(text: String): Long {
        val match = Regex("(\\d+)\\s*(minute|hour|day|week|month|year)s?\\s+ago").find(text)
            ?: return 0L
        val amount = match.groupValues[1].toIntOrNull() ?: return 0L
        val cal = Calendar.getInstance()
        return when (match.groupValues[2]) {
            "minute" -> cal.apply { add(Calendar.MINUTE, -amount) }.timeInMillis
            "hour" -> cal.apply { add(Calendar.HOUR_OF_DAY, -amount) }.timeInMillis
            "day" -> cal.apply { add(Calendar.DAY_OF_MONTH, -amount) }.timeInMillis
            "week" -> cal.apply { add(Calendar.DAY_OF_MONTH, -amount * 7) }.timeInMillis
            "month" -> cal.apply { add(Calendar.MONTH, -amount) }.timeInMillis
            "year" -> cal.apply { add(Calendar.YEAR, -amount) }.timeInMillis
            else -> 0L
        }
    }

    private fun midnight(daysAgo: Int): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_MONTH, -daysAgo)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        private val updatingRegex = Regex("updating|atualizando", RegexOption.IGNORE_CASE)

        private val DATE_FORMAT = SimpleDateFormat("MMMM d, yyyy", Locale.US)
    }
}
