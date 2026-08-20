package eu.kanade.tachiyomi.extension.en.allporncomics

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
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
import java.util.Locale

/*
 * AllPornComics (https://allporncomics.co) — a Madara WordPress reader. Plain
 * server-rendered HTML, no Cloudflare, chapter images served from the same host.
 *
 *     Home/Latest : /comics-home/ (+ /page/N/)                    -> .page-item-detail grid
 *     Popular     : /comics-home/?m_orderby=views (+ /page/N/)    -> .page-item-detail grid
 *     Browse      : /comic-genre/<slug>/ (+ /page/N/)             -> .page-item-detail grid
 *     Search      : /?s=<q>&post_type=wp-manga (+ /page/N/)       -> .c-tabs-item__content cards
 *     Details     : /comic/<slug>
 *     Chapters    : .wp-manga-chapter li <a href>  (newest first, dd/MM/yyyy dates)
 *     Pages       : /comic/<slug>/chapter-<n> -> .reading-content img
 *
 * Pagination is path-based (/page/N/), detected via a .nextpostslink anchor.
 */
class AllPornComics : HttpSource() {

    override val name = "AllPornComics"
    override val baseUrl = "https://allporncomics.co"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder =
        super.headersBuilder()
            .set("User-Agent", BROWSER_UA)
            .set("Referer", "$baseUrl/")

    // ========================== Search & Browse ===========================

    override fun getFilterList(): FilterList = FilterList(
        GenreFilter(),
    )

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/comics-home${pageSuffix(page, "m_orderby=views")}", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseListing(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/comics-home${pageSuffix(page)}", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseListing(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val genre = filters.filterIsInstance<GenreFilter>().firstOrNull()?.selected
        return if (query.isBlank() && genre != null) {
            GET("$baseUrl/comic-genre/$genre${pageSuffix(page)}", headers)
        } else {
            val q = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
            GET("$baseUrl${pageSuffix(page, "s=$q&post_type=wp-manga")}", headers)
        }
    }

    override fun searchMangaParse(response: Response): MangasPage = parseListing(response)

    private fun parseListing(response: Response): MangasPage {
        val doc = response.asJsoup()
        val mangas = buildList {
            doc.select(".page-item-detail").forEach { card ->
                parseGridCard(card)?.let { add(it) }
            }
            doc.select(".c-tabs-item__content").forEach { card ->
                parseSearchCard(card)?.let { add(it) }
            }
        }
        return MangasPage(mangas, hasNextPage = doc.selectFirst("a.nextpostslink") != null)
    }

    private fun parseGridCard(card: Element): SManga? {
        val a = card.selectFirst(".item-thumb a") ?: card.selectFirst(".post-title a") ?: return null
        val title = a.attr("title").ifBlank { cleanTitle(a) }.trim()
        if (title.isEmpty()) return null
        return SManga.create().apply {
            url = a.absUrl("href")
            this.title = title
            thumbnail_url = card.selectFirst(".item-thumb img")?.let(::imgUrl)
        }
    }

    private fun parseSearchCard(card: Element): SManga? {
        val a = card.selectFirst(".tab-thumb a[href]") ?: card.selectFirst(".post-title a") ?: return null
        val title = a.attr("title")
            .ifBlank { card.selectFirst(".post-title a")?.let(::cleanTitle).orEmpty() }
            .trim()
        if (title.isEmpty()) return null
        return SManga.create().apply {
            url = a.absUrl("href")
            this.title = title
            thumbnail_url = card.selectFirst(".tab-thumb img")?.let(::imgUrl)
        }
    }

    // ========================== Manga Details =============================

    override fun getMangaUrl(manga: SManga): String = manga.url

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()
        val title = doc.selectFirst(".post-title h1")?.text()?.trim()
            ?: doc.title().substringBefore(" - All Porn Comics").trim()

        val author = infoItem(doc, "Author(s)").ifBlank { infoItem(doc, "Artist(s)") }
        val status = when (infoItem(doc, "Status").lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "cancelled", "dropped" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }

        return SManga.create().apply {
            this.title = title
            url = response.request.url.toString()
            thumbnail_url = doc.selectFirst(".summary_image img")?.let(::imgUrl)
            this.author = author
            this.artist = author
            this.status = status
            genre = doc.select(".genres-content a[rel=tag]").map { it.text().trim() }.distinct().joinToString()
            description = doc.selectFirst(".description-summary .summary__content")?.text()?.trim().orEmpty()
        }
    }

    // ============================= Chapters ===============================

    override fun chapterListRequest(manga: SManga): Request =
        GET(manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = response.asJsoup()
        val items = doc.select("li.wp-manga-chapter")
        val count = items.size
        return items.mapIndexed { index, li ->
            val a = li.selectFirst("a[href]") ?: return@mapIndexed null
            SChapter.create().apply {
                url = a.absUrl("href")
                name = a.text().trim()
                date_upload = parseDate(li.selectFirst(".chapter-release-date")?.text())
                chapter_number = parseChapterNumber(name, url)
                    ?: (count - index).toFloat()
            }
        }.filterNotNull()
    }

    override fun getChapterUrl(chapter: SChapter): String = chapter.url

    // =============================== Pages ================================

    override fun pageListRequest(chapter: SChapter): Request =
        GET(chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        val doc = response.asJsoup()
        return doc.select(".reading-content img").mapIndexedNotNull { index, img ->
            val url = imgUrl(img) ?: return@mapIndexedNotNull null
            Page(index, url, url)
        }
    }

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl!!, headers)

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException()

    // ============================= Utilities ==============================

    private fun pageSuffix(page: Int, query: String = ""): String {
        val q = if (query.isEmpty()) "" else "?$query"
        return if (page <= 1) q else "/page/$page/$q"
    }

    // Madara lazy-loads some imgs with an empty `src` and the real URL in data-src.
    private fun imgUrl(img: Element): String? {
        val attr = when {
            img.attr("data-src").isNotBlank() -> "data-src"
            img.attr("data-lazy-src").isNotBlank() -> "data-lazy-src"
            img.attr("src").isNotBlank() -> "src"
            else -> return null
        }
        return img.absUrl(attr).ifBlank { null }
    }

    private fun cleanTitle(a: Element): String =
        a.clone().apply { select(".manga-title-badges, .badge").remove() }.text().trim()

    private fun infoItem(doc: Document, heading: String): String =
        doc.select(".post-content_item").mapNotNull { item ->
            val h = item.selectFirst(".summary-heading h5")?.text()?.trim() ?: return@mapNotNull null
            if (h.equals(heading, ignoreCase = true)) {
                item.selectFirst(".summary-content")?.text()?.trim()
            } else null
        }.firstOrNull() ?: ""

    // ============================== Filters ===============================

    private class GenreFilter : Filter.Select<String>(
        "Genre",
        arrayOf("All") + GENRES.map { it.first },
    ) {
        val selected: String? get() = if (state == 0) null else GENRES[state - 1].second
    }

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        private val GENRES = listOf(
            "3D Comics" to "3d",
            "Western" to "western",
            "Webtoon" to "webtoon",
            "Manga" to "manga",
            "Manhwa" to "adult-manhwa",
            "Uncensored" to "uncensored",
        )
    }
}

// ========================= Top-level helpers ==========================

private fun parseDate(text: String?): Long {
    if (text.isNullOrBlank()) return 0L
    val t = text.trim()
    parseExactDate(t)?.let { return it }
    parseRelativeTime(t)?.let { return it }
    return 0L
}

private fun parseExactDate(text: String): Long? {
    val m = Regex("""\b(\d{2})/(\d{2})/(\d{4})\b""").find(text) ?: return null
    val day = m.groupValues[1].toIntOrNull() ?: return null
    val month = m.groupValues[2].toIntOrNull() ?: return null
    val year = m.groupValues[3].toIntOrNull() ?: return null
    val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
    cal.clear()
    cal.set(year, month - 1, day)
    return cal.timeInMillis
}

private fun parseRelativeTime(text: String): Long? {
    val t = text.lowercase(Locale.US)
    val now = System.currentTimeMillis()
    if (t.contains("yesterday")) return now - DAY_MS
    if (t.contains("today") || t.contains("just now")) return now
    val m = Regex("""(\d+)\s*(minute|hour|day|week|month|year)s?""").find(t) ?: return null
    val n = m.groupValues[1].toLong()
    val mult = when (m.groupValues[2]) {
        "minute" -> MINUTE_MS
        "hour" -> HOUR_MS
        "day" -> DAY_MS
        "week" -> WEEK_MS
        "month" -> MONTH_MS
        "year" -> YEAR_MS
        else -> return null
    }
    return now - n * mult
}

private fun parseChapterNumber(name: String, url: String): Float? {
    Regex("""chapter\s*([\d.,]+)""", RegexOption.IGNORE_CASE).find(name)?.let {
        return it.groupValues[1].replace(",", ".").toFloatOrNull()
    }
    Regex("""/chapter-([\d.]+)/?""").find(url)?.let {
        return it.groupValues[1].toFloatOrNull()
    }
    return null
}

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 86_400_000L
private const val WEEK_MS = 604_800_000L
private const val MONTH_MS = 2_592_000_000L
private const val YEAR_MS = 31_536_000_000L
