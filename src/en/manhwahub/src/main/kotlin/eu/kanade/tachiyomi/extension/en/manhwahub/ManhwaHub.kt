package eu.kanade.tachiyomi.extension.en.manhwahub

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

/*
 * ManhwaHub (https://manhwahub.net) is a Madara WordPress reader with an
 * 18+ content warning. Everything is plain server-rendered HTML — no API,
 * no Cloudflare challenge, and chapter images live on cdn.manhwahub.net
 * with no referer requirement.
 *
 *     Home/Latest : GET /?page=<n>                 -> .page-item-detail grid
 *     Popular     : GET /?page=1 (homepage)        -> #slide-top .item block
 *     Search      : GET /search?s=<query>&page=<n> -> .page-item-detail grid
 *     Browse      : GET /genre/<slug>?page=<n>     -> .page-item-detail grid
 *     Details     : GET /webtoon/<slug>
 *     Chapters    : .wp-manga-chapter li <a href>  (newest first)
 *     Pages       : GET /webtoon/<slug>/chapter-<n> -> .reading-content img
 *
 * All requests carry a browser User-Agent + Referer so the server treats us
 * like a normal reader. Pagination is detected via the <link rel=next> tag.
 */
class ManhwaHub : HttpSource() {

    override val name = "ManhwaHub"
    override val baseUrl = "https://manhwahub.net"
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
        GET("$baseUrl/?page=$page", headersBuilder().build())

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = response.asJsoup()
        val pageNum = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        // Page 1 shows a dedicated "Popular Web Updates" carousel.
        val mangas = if (pageNum <= 1) parsePopular(doc) else parseGrid(doc)
        return MangasPage(mangas, hasNextPage = hasNextPage(doc))
    }

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/?page=$page", headersBuilder().build())

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
                "$baseUrl/genre/$genreSlug?page=$page"
            else ->
                "$baseUrl/search?s=${URLEncoder.encode(query.trim(), "UTF-8")}&page=$page"
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
        val title = doc.selectFirst(".post-title h1")?.text()?.trim()
            ?: doc.title().substringBefore(" - ManhwaHub").trim()

        val author = infoItem(doc, "Author(s)").ifBlank { infoItem(doc, "Artist(s)") }
        val status = when (infoItem(doc, "status").lowercase()) {
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
            this.artist = author
            this.status = status
            genre = doc.select(".genres-content a[rel=tag]").map { it.text().trim() }.distinct().joinToString()
            description = doc.selectFirst(".description-summary .summary__content")?.text()?.trim().orEmpty()
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
                date_upload = parseRelativeTime(li.selectFirst(".chapter-release-date")?.text())
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
        return doc.select(".reading-content img.chapter-img").mapIndexed { index, img ->
            val url = img.absUrl("src")
            Page(index, url, url)
        }
    }

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl!!, headersBuilder().build())

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

    private fun parsePopular(doc: Document): List<SManga> =
        doc.select("#slide-top .item").mapNotNull { item ->
            val cover = item.selectFirst(".img-item a[href]") ?: return@mapNotNull null
            val titleLink = item.selectFirst(".info-item .line-2 a")
            SManga.create().apply {
                url = cover.absUrl("href")
                title = titleLink?.text()?.trim()
                    ?: cover.selectFirst("img")?.attr("alt")?.trim()
                    ?: ""
                thumbnail_url = cover.selectFirst("img")?.absUrl("src")
            }
        }

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
    )

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        internal val GENRES = listOf(
            "Action" to "action",
            "Adult" to "adult",
            "Adventure" to "adventure",
            "Comedy" to "comedy",
            "Cooking" to "cooking",
            "Detective" to "detective",
            "Doujinshi" to "doujinshi",
            "Drama" to "drama",
            "Ecchi" to "ecchi",
            "Fantasy" to "fantasy",
            "Gender Bender" to "gender-bender",
            "Harem" to "harem",
            "Historical" to "historical",
            "Horror" to "horror",
            "Josei" to "josei",
            "Manga" to "manga",
            "Manhua" to "manhua",
            "Manhwa" to "manhwa",
            "Martial Arts" to "martial-arts",
            "Mature" to "mature",
            "Mecha" to "mecha",
            "Mystery" to "mystery",
            "One shot" to "one-shot",
            "Psychological" to "psychological",
            "Romance" to "romance",
            "School Life" to "school-life",
            "Sci-fi" to "sci-fi",
            "Seinen" to "seinen",
            "Shoujo" to "shoujo",
            "Shoujo Ai" to "shoujo-ai",
            "Shounen" to "shounen",
            "Shounen Ai" to "shounen-ai",
            "Slice of Life" to "slice-of-life",
            "Smut" to "smut",
            "Soft Yaoi" to "soft-yaoi",
            "Soft Yuri" to "soft-yuri",
            "Sports" to "sports",
            "Supernatural" to "supernatural",
            "Tragedy" to "tragedy",
            "Webtoon" to "webtoon",
            "Yaoi" to "yaoi",
            "Yuri" to "yuri",
        )
    }
}

// ========================= Top-level helpers ==========================

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
    Regex("""/chapter-([\d]+)""").find(url)?.let {
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
