package eu.kanade.tachiyomi.extension.en.manhwa18cc

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
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
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URLEncoder
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

/*
 * Manhwa18CC (https://manhwa18.cc) — an 18+ manhwa/manhua site using a
 * Madara-style WordPress template. Browse/search/manga/chapter lists are all
 * plain SSR HTML, and reading images are plain <img> srcs inside the reader.
 *
 *     Browse  : /webtoons/<N>         (all titles)   -> div.manga-item
 *               /page/<N>             (home, latest)  -> div.manga-item
 *     Search  : /search?q=<q>&page=N                  -> div.manga-item
 *     Genre   : /webtoon-genre/<slug>/<N>             -> div.manga-item
 *     Manga   : /webtoon/<slug>       -> h1, .summary_image, .author-content,
 *               .genres-content, .panel-story-description .dsct; chapters in
 *               ul.row-content-chapter li.a-h a.chapter-name (+ chapter-time)
 *     Chapter : /webtoon/<slug>/chapter-<n> -> div.read-content img
 *
 * Pagination is <ul class="pagination"><li class="next"><a href=..>..</a></li>.
 * Covers come from the /manga/ CDN and reading pages from img*.manhwa18.cc,
 * both plain JPEGs with no hotlink protection.
 */
class Manhwa18Cc : HttpSource() {

    override val name = "Manhwa18CC"
    override val baseUrl = "https://manhwa18.cc"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder =
        super.headersBuilder()
            .set("User-Agent", BROWSER_UA)
            .set("Referer", "$baseUrl/")

    // ============================== Filters ==============================

    override fun getFilterList(): FilterList = FilterList(
        GenreList(GENRES.map { Genre(it.first, it.second) }),
    )

    private class Genre(name: String, val key: String) : Filter.TriState(name)

    private class GenreList(genres: List<Genre>) : Filter.Group<Genre>("Genres", genres)

    // =========================== Browse & Search =========================

    private fun archiveUrl(page: Int): String = when (page) {
        1 -> "$baseUrl/webtoons"
        else -> "$baseUrl/webtoons/$page"
    }

    private fun homeUrl(page: Int): String = when (page) {
        1 -> baseUrl
        else -> "$baseUrl/page/$page"
    }

    override fun popularMangaRequest(page: Int): Request =
        GET(archiveUrl(page), headers)

    override fun popularMangaParse(response: Response): MangasPage =
        parseList(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET(homeUrl(page), headers)

    override fun latestUpdatesParse(response: Response): MangasPage =
        parseList(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val genre = filters.firstOrNull { it is GenreList } as GenreList?
            ?.state
            ?.filter { it.isIncluded() }
            ?.joinToString(",") { it.key }
            ?.takeIf { it.isNotBlank() }

        val url = when {
            query.isNotBlank() ->
                "$baseUrl/search?q=${URLEncoder.encode(query, "utf-8")}&page=$page"
            genre != null ->
                if (page == 1) "$baseUrl/webtoon-genre/$genre" else "$baseUrl/webtoon-genre/$genre/$page"
            else -> archiveUrl(page)
        }
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage =
        parseList(response)

    private fun parseList(response: Response): MangasPage {
        val document = response.asDocument()
        val mangas = document.select(LIST_ITEM_SELECTOR).mapNotNull(::mangaFromElement)
        val hasNextPage = document.selectFirst("ul.pagination li.next a") != null
        return MangasPage(mangas, hasNextPage)
    }

    private fun mangaFromElement(element: Element): SManga? {
        val urlElement = element.selectFirst("div.thumb a") ?: return null
        return SManga.create().apply {
            setUrlWithoutDomain(urlElement.absUrl("href"))
            title = urlElement.attr("title").ifBlank { urlElement.attr("abs:href").substringAfterLast('/') }
            element.selectFirst("div.thumb img")?.let { thumbnail_url = imageFromElement(it) }
        }
    }

    // =========================== Manga Details ===========================

    override fun getMangaUrl(manga: SManga): String =
        if (manga.url.startsWith("http")) manga.url else baseUrl + manga.url

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asDocument()
        val manga = SManga.create().apply {
            title = document.selectFirst("h1")?.ownText()?.trim().orEmpty()
            setUrlWithoutDomain(response.request.url.toString())
            document.selectFirst(".summary_image img")?.let { thumbnail_url = imageFromElement(it) }
            document.select(".author-content a").eachText().joinToString().takeIf { it.isNotBlank() }?.let { author = it }
            document.select(".artist-content a").eachText().joinToString().takeIf { it.isNotBlank() }?.let { artist = it }
            val genres = document.select(".genres-content a").eachText()
            if (genres.isNotEmpty()) genre = genres.joinToString()
            document.selectFirst("div.post-content_item:has(h5:contains(Status)) .summary-content")?.let { el ->
                val text = el.text().trim()
                status = when {
                    COMPLETED_STATUS.any { text.contains(it, ignoreCase = true) } -> SManga.COMPLETED
                    ONGOING_STATUS.any { text.contains(it, ignoreCase = true) } -> SManga.ONGOING
                    HIATUS_STATUS.any { text.contains(it, ignoreCase = true) } -> SManga.ON_HIATUS
                    CANCELED_STATUS.any { text.contains(it, ignoreCase = true) } -> SManga.CANCELLED
                    else -> SManga.UNKNOWN
                }
            }
            document.selectFirst(".panel-story-description .dsct")?.let { description = it.text() }
        }
        return manga
    }

    // ============================== Chapters =============================

    override fun getChapterUrl(chapter: SChapter): String =
        if (chapter.url.startsWith("http")) chapter.url else baseUrl + chapter.url

    override fun chapterListRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asDocument()
        return document.select("ul.row-content-chapter li.a-h a.chapter-name").mapNotNull { element ->
            SChapter.create().apply {
                url = element.absUrl("href").substringBefore("?style=paged")
                name = element.text().trim()
                chapter_number = CHAPTER_NUMBER_REGEX.find(name)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
                element.parent()?.selectFirst("span.chapter-time")?.text()?.let {
                    date_upload = parseChapterDate(it)
                }
            }
        }.asReversed()
    }

    // =============================== Pages ===============================

    override fun pageListRequest(chapter: SChapter): Request =
        GET(getChapterUrl(chapter), headers)

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asDocument()
        val chapterUrl = response.request.url.toString()
        return document.select(".read-content img").mapIndexedNotNull { index, element ->
            val imageUrl = imageFromElement(element)
            if (imageUrl.isNullOrBlank()) null else Page(index, chapterUrl, imageUrl)
        }
    }

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl ?: page.url, headers.newBuilder().set("Referer", page.url).build())

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ========================== Image helpers ============================

    private fun imageFromElement(element: Element): String? = when {
        element.hasAttr("data-src") -> element.absUrl("data-src")
        element.hasAttr("data-lazy-src") -> element.absUrl("data-lazy-src")
        element.hasAttr("data-cfsrc") -> element.absUrl("data-cfsrc")
        else -> element.absUrl("src")
    }

    private fun Response.asDocument(): Document {
        val body = body?.string() ?: throw IOException("Empty response body")
        return Jsoup.parse(body, request.url.toString())
    }

    // ========================== Date helpers =============================

    private fun parseChapterDate(date: String?): Long {
        date ?: return 0L
        return try {
            SimpleDateFormat("dd MMM yyyy", Locale.US).parse(date.trim())?.time ?: 0L
        } catch (_: ParseException) {
            0L
        }
    }

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        private const val LIST_ITEM_SELECTOR = "div.manga-item"

        private val CHAPTER_NUMBER_REGEX = Regex("""(\d+(?:\.\d+)?)""")

        private val COMPLETED_STATUS = listOf("Completed", "Completo", "Concluído")
        private val ONGOING_STATUS = listOf("OnGoing", "Ongoing", "Updating")
        private val HIATUS_STATUS = listOf("Hiatus", "On Hold", "Paused")
        private val CANCELED_STATUS = listOf("Canceled", "Cancelled", "Dropped")
    }
}

// Mirrors the /webtoon-genre/ taxonomy on https://manhwa18.cc.
private val GENRES = listOf(
    "Action" to "action",
    "Adult" to "adult",
    "Adventure" to "adventure",
    "BL" to "bl",
    "Comedy" to "comedy",
    "Comics" to "comics",
    "Doujinshi" to "doujinshi",
    "Drama" to "drama",
    "Ecchi" to "ecchi",
    "Family" to "family",
    "Fantasy" to "fantasy",
    "Gender Bender" to "gender-bender",
    "GL" to "gl",
    "Harem" to "harem",
    "Hentai" to "hentai",
    "Historical" to "historical",
    "Horror" to "horror",
    "Isekai" to "isekai",
    "Josei" to "josei",
    "Magic" to "magic",
    "Martial Arts" to "martial-arts",
    "Mature" to "mature",
    "Mecha" to "mecha",
    "Mystery" to "mystery",
    "NTR" to "ntr",
    "Psychological" to "psychological",
    "Romance" to "romance",
    "School Life" to "school-life",
    "Sci-fi" to "sci-fi",
    "Seinen" to "seinen",
    "Shoujo" to "shoujo",
    "Shounen" to "shounen",
    "Slice of Life" to "slice-of-life",
    "Smut" to "smut",
    "Sports" to "sports",
    "Supernatural" to "supernatural",
    "Thriller" to "thriller",
    "Tragedy" to "tragedy",
    "Yaoi" to "yaoi",
    "Yuri" to "yuri",
)
