package eu.kanade.tachiyomi.extension.en.toonily

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
import java.text.SimpleDateFormat
import java.util.Locale

/*
 * Toonily (https://toonily.com) is a WordPress reader (custom Madara-like
 * theme) serving manhwa/webtoon with an 18+ content warning. Server-rendered
 * HTML, no API, and chapter images live on data.tnlycdn.com behind
 * Cloudflare — the image CDN answers OkHttp requests only when they carry a
 * browser User-Agent, a Referer from toonily.com and the mature cookie.
 *
 *     Popular  : /serie/?m_orderby=views (+ /serie/page/<n>/)
 *     Latest   : /  and /page/<n>/
 *     Search   : /?s=<query>&paged=<n>
 *     Browse   : /genre/<slug>/  (+ /genre/<slug>/page/<n>/)
 *     Details  : /serie/<slug>/
 *     Chapters : li.wp-manga-chapter a[href]  (newest first, all in HTML)
 *     Pages    : /serie/<slug>/chapter-<n>/ -> .reading-content img
 *
 * Cover images ship in small -<w>x<h>.jpg sizes; the full-resolution file
 * drops that size segment, which the HD-cover handling in imageRequest
 * takes advantage of.
 */
class Toonily : HttpSource() {

    override val name = "Toonily"
    override val baseUrl = "https://toonily.com"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder =
        super.headersBuilder()
            .set("User-Agent", BROWSER_UA)
            .set("Referer", "$baseUrl/")
            .set("Cookie", MATURE_COOKIE)

    // ========================== Search & Browse ===========================

    override fun getFilterList(): FilterList = FilterList(
        GenreFilter(),
    )

    override fun popularMangaRequest(page: Int): Request {
        val url = if (page <= 1) {
            "$baseUrl/serie/?m_orderby=views"
        } else {
            "$baseUrl/serie/page/$page/?m_orderby=views"
        }
        return GET(url, headersBuilder().build())
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = response.asJsoup()
        return MangasPage(parseGrid(doc), hasNextPage = hasNextPage(doc))
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page <= 1) "$baseUrl/" else "$baseUrl/page/$page/"
        return GET(url, headersBuilder().build())
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val doc = response.asJsoup()
        return MangasPage(parseGrid(doc), hasNextPage = hasNextPage(doc))
    }

    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request {
        val genreIdx = filters.filterIsInstance<GenreFilter>().firstOrNull()?.state ?: 0
        val genreSlug = if (genreIdx > 0 && genreIdx <= GENRES.size) GENRES[genreIdx - 1].second else null

        val url = when {
            query.isBlank() && genreSlug != null ->
                if (page <= 1) "$baseUrl/genre/$genreSlug/" else "$baseUrl/genre/$genreSlug/page/$page/"
            else ->
                "$baseUrl/?s=${URLEncoder.encode(query.trim(), "UTF-8")}&paged=$page"
        }
        return GET(url, headersBuilder().build())
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val doc = response.asJsoup()
        return MangasPage(parseGrid(doc), hasNextPage = hasNextPage(doc))
    }

    // ========================== Manga Details =============================

    override fun getMangaUrl(manga: SManga): String = manga.url

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(manga.url, headersBuilder().build())

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()
        val titleEl = doc.selectFirst(".post-title h1")
        titleEl?.select("span.manga-title-badges")?.remove()
        val title = titleEl?.text()?.trim()
            ?: doc.title().substringBefore(" - Toonily").trim()

        val author = infoItem(doc, "Writer(s)")
        val artist = infoItem(doc, "Artist(s)")
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
            thumbnail_url = doc.selectFirst(".summary_image img")?.absUrl("src")
            this.author = author
            this.artist = artist
            this.status = status
            genre = doc.select(".genres-content a[rel=tag]").map { it.text().trim() }.distinct().joinToString()
            description = doc.selectFirst(".content-area .summary__content")?.text()?.trim()
                ?: doc.selectFirst(".summary__content")?.text()?.trim().orEmpty()
        }
    }

    // ============================= Chapters ===============================

    override fun chapterListRequest(manga: SManga): Request =
        GET(manga.url, headersBuilder().build())

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = response.asJsoup()
        val items = doc.select("li.wp-manga-chapter")
        val count = items.size
        return items.mapIndexed { index, li ->
            val a = li.selectFirst("a[href]") ?: return@mapIndexed null
            SChapter.create().apply {
                url = a.absUrl("href")
                name = a.text().trim()
                date_upload = parseChapterDate(li.selectFirst(".chapter-release-date")?.text())
                chapter_number = parseChapterNumber(name, url)
                    ?: (count - index).toFloat()
            }
        }.filterNotNull()
    }

    override fun getChapterUrl(chapter: SChapter): String = chapter.url

    // =============================== Pages ================================

    override fun pageListRequest(chapter: SChapter): Request =
        GET(chapter.url, headersBuilder().build())

    override fun pageListParse(response: Response): List<Page> {
        val doc = response.asJsoup()
        return doc.select(".reading-content img").mapIndexed { index, img ->
            val url = img.absUrl("src")
            Page(index, url, url)
        }
    }

    override fun imageRequest(page: Page): Request {
        val url = page.imageUrl ?: page.url
        return GET(hdCoverUrl(url), headersBuilder().build())
    }

    // ============================= Utilities ==============================

    private fun hasNextPage(doc: Document): Boolean =
        doc.selectFirst("link[rel=next]") != null

    private fun parseGrid(doc: Document): List<SManga> =
        doc.select(".page-item-detail").mapNotNull { card ->
            val a = card.selectFirst(".item-thumb a")
                ?: card.selectFirst(".post-title a")
                ?: return@mapNotNull null
            SManga.create().apply {
                url = a.absUrl("href")
                title = a.attr("title").ifBlank { a.text() }.trim()
                thumbnail_url = card.selectFirst(".item-thumb img")?.absUrl("src")
            }
        }

    private fun infoItem(doc: Document, heading: String): String =
        doc.select(".post-content_item").mapNotNull { item ->
            val h = item.selectFirst(".summary-heading h5")?.text()?.trim() ?: return@mapNotNull null
            if (h.equals(heading, ignoreCase = true)) {
                item.selectFirst(".summary-content")?.text()?.trim()
            } else null
        }.firstOrNull() ?: ""

    private fun hdCoverUrl(url: String): String {
        // static.tnlycdn.com serves small covers as name-<w>x<h>.jpg — the
        // full-res image drops the size segment, keeping the extension.
        if (!url.startsWith("https://static.tnlycdn.com/")) return url
        val m = SD_COVER_REGEX.find(url) ?: return url
        return url.substring(0, m.range.first) + m.groupValues[3]
    }

    // ============================== Filters ===============================

    private class GenreFilter : Filter.Select<String>(
        "Genre",
        arrayOf("All") + GENRES.map { it.first },
    )

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        private const val MATURE_COOKIE = "toonily-mature=1"

        private val SD_COVER_REGEX = Regex("""-(\d+)x(\d+)(\.\w+)$""")

        internal val GENRES = listOf(
            "Action" to "action",
            "Adventure" to "adventure",
            "Comedy" to "comedy",
            "Crime" to "crime",
            "Drama" to "drama",
            "Fantasy" to "fantasy",
            "Gossip" to "gossip",
            "Historical" to "historical",
            "Horror" to "horror",
            "Isekai" to "isekai",
            "Josei" to "josei",
            "Magic" to "magic",
            "Mature" to "mature",
            "Mystery" to "mystery",
            "Psychological" to "psychological",
            "Romance" to "romance",
            "School Life" to "school-life",
            "Sci-Fi" to "scifi-webtoon",
            "Seinen" to "seinen",
            "Shoujo" to "shoujo",
            "Shounen" to "shounen",
            "Slice of Life" to "slice-of-life",
            "Sports" to "sports",
            "Supernatural" to "supernatural",
            "Thriller" to "thriller",
            "Tragedy" to "tragedy",
            "Villainess" to "villainess",
            "Wuxia" to "wuxia",
            "Yaoi" to "yaoi",
            "Yuri" to "yuri",
        )
    }
}

// ========================= Top-level helpers ==========================

private fun parseChapterDate(text: String?): Long {
    if (text.isNullOrBlank()) return 0L
    val t = text.trim()
    if (t.equals("up", ignoreCase = true) || t.equals("today", ignoreCase = true)) return System.currentTimeMillis()

    val rel = parseRelativeTime(t)
    if (rel != 0L) return rel

    return dateFormat.runCatching { parse(t)?.time }.getOrNull() ?: 0L
}

private val dateFormat = SimpleDateFormat("MMM d, yy", Locale.US)

private fun parseRelativeTime(text: String?): Long {
    if (text.isNullOrBlank()) return 0L
    val t = text.lowercase()
    val now = System.currentTimeMillis()
    if (t.contains("yesterday")) return now - DAY_MS
    val m = Regex("""(\d+)\s*(minute|hour|day|week|month|year)s?""").find(t)
        ?: return 0L
    val n = m.groupValues[1].toLong()
    val mult = when (m.groupValues[2]) {
        "minute" -> MINUTE_MS
        "hour" -> HOUR_MS
        "day" -> DAY_MS
        "week" -> WEEK_MS
        "month" -> MONTH_MS
        "year" -> YEAR_MS
        else -> 0L
    }
    return now - n * mult
}

private fun parseChapterNumber(name: String, url: String): Float? {
    Regex("""chapter\s*([\d.,]+)""", RegexOption.IGNORE_CASE).find(name)?.let {
        return it.groupValues[1].replace(",", ".").toFloatOrNull()
    }
    Regex("""/chapter-([\d]+)/""").find(url)?.let {
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
