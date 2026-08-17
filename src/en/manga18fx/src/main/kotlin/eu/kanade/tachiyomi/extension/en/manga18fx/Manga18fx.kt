package eu.kanade.tachiyomi.extension.en.manga18fx

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

/*
 * Manga18fx (https://manga18fx.com) — a custom PHP reader that closely mimics
 * the Madara WordPress layout. Everything is plain SSR HTML: listings, the
 * manga page with all chapters, and the reader pages themselves (reading
 * images are real <img> tags on the img*.manga18fx.com CDN, no hotlink
 * protection, no AJAX).
 *
 *     Browse  : /              (latest, page 1)   -> div.bsx-item
 *               /page/<N>      (latest pagination)
 *               /hot-manga?page=<N>   (weekly popular)
 *     Search  : /search?q=<q>&page=<N>
 *     Genre   : /manga-genre/<slug>[/<N>]
 *     Manga   : /manga/<slug>  -> div.post-title h1, .summary_image img,
 *               .author-content, .artist-content, .genres-content, Status in
 *               div.post-content_item:has(h5:contains(Status)), .panel-story-
 *               description .dsct; chapters (newest first) in
 *               ul.row-content-chapter li.a-h a.chapter-name
 *     Chapter : /manga/<slug>/chapter-<n> -> div.read-content img (the page
 *               images carry an /online/ URL; the related-manga thumbnails
 *               in the same container are filtered out)
 *
 * Pagination is Bootstrap-style: hasNextPage = ul.pagination li.next a (a
 * disabled next has no <a>). The site hosts uncensored/adult titles too.
 */
class Manga18fx : HttpSource() {

    override val name = "Manga18fx"
    override val baseUrl = "https://manga18fx.com"
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

    override fun popularMangaRequest(page: Int): Request =
        GET(hotUrl(page), headers)

    override fun popularMangaParse(response: Response): MangasPage =
        parseList(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET(latestUrl(page), headers)

    override fun latestUpdatesParse(response: Response): MangasPage =
        parseList(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val genreFilter = filters.filterIsInstance<GenreList>().firstOrNull()
        val genre = genreFilter
            ?.state
            ?.filter { it.isIncluded() }
            ?.joinToString(",") { it.key }
            ?.takeIf { it.isNotBlank() }

        val url = when {
            query.isNotBlank() ->
                "$baseUrl/search?q=${URLEncoder.encode(query, "utf-8")}&page=$page"
            genre != null ->
                if (page == 1) "$baseUrl/manga-genre/$genre" else "$baseUrl/manga-genre/$genre/$page"
            else -> latestUrl(page)
        }
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage =
        parseList(response)

    private fun hotUrl(page: Int): String =
        if (page == 1) "$baseUrl/hot-manga" else "$baseUrl/hot-manga?page=$page"

    private fun latestUrl(page: Int): String =
        if (page == 1) baseUrl else "$baseUrl/page/$page"

    private fun parseList(response: Response): MangasPage {
        val document = response.asDocument()
        val mangas = document.select(LIST_ITEM_SELECTOR).mapNotNull(::mangaFromElement)
        val hasNextPage = document.selectFirst("ul.pagination li.next a") != null
        return MangasPage(mangas, hasNextPage)
    }

    private fun mangaFromElement(element: Element): SManga? {
        val link = element.selectFirst(".thumb-manga a") ?: return null
        return SManga.create().apply {
            setUrlWithoutDomain(link.absUrl("href"))
            title = link.attr("title")
                .takeIf { it.isNotBlank() }
                ?: element.selectFirst("h3.tt a")?.text()
                ?: link.selectFirst("img")?.attr("alt")
                ?: ""
            link.selectFirst("img")?.let { thumbnail_url = imageFromElement(it) }
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
            title = document.selectFirst(".post-title h1")?.text()?.trim().orEmpty()
            setUrlWithoutDomain(response.request.url.toString())
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
            document.selectFirst(".summary_image img")?.let { thumbnail_url = imageFromElement(it) }
        }
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
                url = element.absUrl("href")
                name = element.text().trim()
                chapter_number = CHAPTER_NUMBER_REGEX.find(name)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
            }
        }
    }

    // =============================== Pages ===============================

    override fun pageListRequest(chapter: SChapter): Request =
        GET(getChapterUrl(chapter), headers)

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asDocument()
        val chapterUrl = response.request.url.toString()
        return document.select(".read-content img").mapIndexedNotNull { index, element ->
            val imageUrl = imageFromElement(element)
            if (imageUrl.isNullOrBlank() || !imageUrl.contains("/online/")) null
            else Page(index, chapterUrl, imageUrl)
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

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        private const val LIST_ITEM_SELECTOR = "div.bsx-item"

        private val CHAPTER_NUMBER_REGEX = Regex("""(\d+(?:\.\d+)?)""")

        private val COMPLETED_STATUS = listOf("Completed", "Completo", "Concluído")
        private val ONGOING_STATUS = listOf("OnGoing", "Ongoing", "Updating")
        private val HIATUS_STATUS = listOf("Hiatus", "On Hold", "Paused")
        private val CANCELED_STATUS = listOf("Canceled", "Cancelled", "Dropped")
    }
}

// Mirrors the /manga-genre/ taxonomy on https://manga18fx.com (header nav).
private val GENRES = listOf(
    "Action" to "action",
    "Adventure" to "adventure",
    "Comedy" to "comedy",
    "Drama" to "drama",
    "Ecchi" to "ecchi",
    "Fantasy" to "fantasy",
    "Harem" to "harem",
    "Isekai" to "isekai",
    "Manhua" to "manhua",
    "Manhwa" to "manhwa",
    "Mature" to "mature",
    "Mystery" to "mystery",
    "Psychological" to "psychological",
    "Reincarnation" to "reincarnation",
    "Romance" to "romance",
    "School Life" to "school-life",
    "Seinen" to "seinen",
    "Shoujo" to "shoujo",
    "Shounen" to "shounen",
    "Supernatural" to "supernatural",
    "Thriller" to "thriller",
    "Tragedy" to "tragedy",
    "Uncensored Manhwa" to "uncensored-manhwa",
)
