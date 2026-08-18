package eu.kanade.tachiyomi.extension.en.mangamaniacs

import com.google.gson.JsonParser
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/*
 * MangaManiacs (https://mangamaniacs.org) â a Wordpress/Madara site hosting
 * uncensored yaoi/BL manga & manhwa (isNsfw). Madara-standard markup:
 *
 *     Popular : /manga/?m_orderby=views      (paged: /manga/page/N/?m_orderby=views)
 *     Latest  : /manga/?m_orderby=latest
 *     Search  : the site's server-side `?s=` search returns "No matches found" for
 *               everything (it never indexes the wp-manga posts), so search uses the
 *               site's own AJAX autocomplete endpoint (admin-ajax.php
 *               `wp-manga-search-manga`), which returns JSON {title,url} items.
 *     Details : post page -> .post-title h1, div.summary_image img, .post-content_item
 *               rows (Author(s), Genre(s), Status), no description on most posts.
 *     Chapters: li.wp-manga-chapter -> a (newest first, date in
 *               `.chapter-release-date` like "1 day ago").
 *     Pages   : chapter page -> .reading-content img (direct full images hosted on
 *               images.mangamaniacs.org; Referer header set for hotlink safety).
 */
class MangaManiacs : HttpSource() {

    override val name = "MangaManiacs"
    override val baseUrl = "https://mangamaniacs.org"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", BROWSER_UA)
        .set("Referer", "$baseUrl/")
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")

    private val xhrHeaders by lazy {
        headersBuilder().set("X-Requested-With", "XMLHttpRequest").build()
    }

    // =========================== Browse ===========================

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/manga/${searchPage(page)}?m_orderby=views", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseMangaList(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/manga/${searchPage(page)}?m_orderby=latest", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseMangaList(response)

    // =========================== Search ===========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // The AJAX autocomplete has no pagination.
        if (page > 1) return GET("$baseUrl/manga/", headers)
        val form = FormBody.Builder()
            .add("action", "wp-manga-search-manga")
            .add("title", query)
            .build()
        return POST("$baseUrl/wp-admin/admin-ajax.php", xhrHeaders, form)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val body = response.body?.string() ?: return MangasPage(emptyList(), false)
        val json = runCatching { JsonParser.parseString(body) }.getOrNull()
        val data = json?.asJsonObject?.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return MangasPage(emptyList(), false)
        val mangas = data.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val title = obj.get("title")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
            val url = obj.get("url")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
            if (title.isNullOrBlank() || url.isNullOrBlank()) return@mapNotNull null
            SManga.create().apply {
                this.title = title
                setUrlWithoutDomain(url)
            }
        }
        return MangasPage(mangas, false)
    }

    override fun getFilterList(): FilterList = FilterList()

    // =========================== Details ==========================

    override fun getMangaUrl(manga: SManga): String =
        if (manga.url.startsWith("http")) manga.url else baseUrl + manga.url

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), headers)

    override fun mangaDetailsParse(response: Response): SManga = mangaDetailsParse(response.asJsoup())

    private fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("div.post-title h3, div.post-title h1, #manga-title > h1")
            ?.ownText()?.trim() ?: ""
        thumbnail_url = document.selectFirst("div.summary_image img")?.let { imageUrl(it) }
        author = document.select("div.author-content > a, div.manga-authors > a")
            .eachText()
            .filter { it.isNotBlank() && !it.contains(updatingRegex) }
            .joinToString()
        genre = document.select("div.genres-content a").eachText().joinToString()
        status = document.selectFirst("div.summary-heading:contains(Status) + div")
            ?.text()?.trim()?.let { parseStatus(it) } ?: SManga.UNKNOWN
        document.selectFirst("div.description-summary div.summary__content")?.let { desc ->
            description = if (desc.select("p").text().isNotBlank()) {
                desc.select("p").joinToString("\n\n") { it.text() }
            } else {
                desc.text()
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
                date_upload = element.selectFirst(".chapter-release-date")?.text()?.let(::parseDate) ?: 0L
            }
        }

    // ============================ Pages ===========================

    override fun pageListRequest(chapter: SChapter): Request =
        GET(getChapterUrl(chapter), headers)

    override fun pageListParse(response: Response): List<Page> = response.asJsoup()
        .select(".reading-content img")
        .mapIndexedNotNull { index, img ->
            val imageUrl = imageUrl(img)
            if (imageUrl.isBlank()) null
            else Page(index, url = response.request.url.toString(), imageUrl = imageUrl)
        }

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl ?: page.url, headers)

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException("imageUrl is resolved in pageListParse")

    // =========================== Helpers ==========================

    private fun parseMangaList(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("div.page-item-detail").mapNotNull { element ->
            val titleEl = element.selectFirst("div.post-title a") ?: return@mapNotNull null
            val url = titleEl.absUrl("href")
            if (url.isBlank()) return@mapNotNull null
            SManga.create().apply {
                title = titleEl.ownText().ifBlank { titleEl.text() }
                setUrlWithoutDomain(url)
                thumbnail_url = element.selectFirst("img")?.let { imageUrl(it) }
            }
        }
        val hasNextPage = document.selectFirst("div.nav-previous, nav.navigation-ajax, a.nextpostslink") != null
        return MangasPage(mangas, hasNextPage)
    }

    private fun searchPage(page: Int): String = if (page == 1) "" else "page/$page/"

    private fun imageUrl(element: Element): String = when {
        element.hasAttr("data-src") -> element.absUrl("data-src").trim()
        element.hasAttr("data-lazy-src") -> element.absUrl("data-lazy-src").trim()
        element.hasAttr("srcset") -> srcsetBest(element.attr("srcset"))
        else -> element.attr("src").trim()
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
        Regex("\\d+(?:\\.\\d+)?").find(name)?.value?.toFloatOrNull() ?: -1f

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
