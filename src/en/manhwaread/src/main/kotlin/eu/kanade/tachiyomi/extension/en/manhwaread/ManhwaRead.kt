package eu.kanade.tachiyomi.extension.en.manhwaread

import android.util.Base64
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
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URLEncoder
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

/*
 * ManhwaRead (https://manhwaread.com) — a WordPress manhwa reader whose theme
 * is a custom Tailwind build. Listings, the manga page and the full chapter
 * list are plain SSR HTML; reading images are NOT in the page — they are
 * injected from a base64 JSON blob (`var chapterData = {...}`) pointing at the
 * manread.xyz CDN, which is hotlink-protected and needs the site Referer.
 *
 *     Browse  : /manhwa/                 (archive, sorted by release)
 *               /manhwa/?sortby=weekly_top&order=desc   (top / popular)
 *               /manhwa/page/<N>/        (pagination)
 *     Search  : /?s=<q>                  (and /page/<N>/?s=<q>)
 *     Genre   : /genre/<slug>/           (and /genre/<slug>/page/<N>/)
 *     Manga   : /manhwa/<slug>/          -> .manga-titles h1, author/artist/
 *               publisher links, .manga-status data-status, #mangaDesc, all
 *               345 chapters inline in #chaptersList
 *     Chapter : /manhwa/<slug>/chapter-<n>/ -> chapterData base + src
 *
 * Pagination is WP-PageNavi: hasNextPage = a.nextpostslink present. Cover art
 * lives on mancover.xyz (no protection); reading pages on manread.xyz need the
 * Referer header set to the site, which headersBuilder does for every request.
 */
class ManhwaRead : HttpSource() {

    override val name = "ManhwaRead"
    override val baseUrl = "https://manhwaread.com"
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
        GET(archiveUrl(page, "weekly_top"), headers)

    override fun popularMangaParse(response: Response): MangasPage =
        parseList(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET(archiveUrl(page, "release"), headers)

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
                if (page == 1) "$baseUrl/?s=${URLEncoder.encode(query, "utf-8")}"
                else "$baseUrl/page/$page/?s=${URLEncoder.encode(query, "utf-8")}"
            genre != null ->
                if (page == 1) "$baseUrl/genre/$genre/" else "$baseUrl/genre/$genre/page/$page/"
            else -> archiveUrl(page, null)
        }
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage =
        parseList(response)

    private fun archiveUrl(page: Int, sort: String?): String {
        val base = if (page == 1) "$baseUrl/manhwa/" else "$baseUrl/manhwa/page/$page/"
        return if (sort != null) "$base?sortby=$sort&order=desc" else base
    }

    private fun parseList(response: Response): MangasPage {
        val document = response.asDocument()
        val mangas = document.select(LIST_ITEM_SELECTOR).mapNotNull(::mangaFromElement)
        val hasNextPage = document.selectFirst("a.nextpostslink") != null
        return MangasPage(mangas, hasNextPage)
    }

    private fun mangaFromElement(element: Element): SManga? {
        val link = element.selectFirst("a.manga-item__link") ?: return null
        return SManga.create().apply {
            setUrlWithoutDomain(link.absUrl("href"))
            title = link.text()
            element.selectFirst(".manga-item__img img")?.let { thumbnail_url = it.absUrl("src") }
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
            title = document.selectFirst(".manga-titles h1")?.text()?.trim().orEmpty()
            setUrlWithoutDomain(response.request.url.toString())
            document.select("a[href*=\"/author/\"] span:nth-of-type(2)")
                .eachText()
                .joinToString()
                .takeIf { it.isNotBlank() }
                ?.let { author = it }
            document.select("a[href*=\"/artist/\"] span:nth-of-type(2)")
                .eachText()
                .joinToString()
                .takeIf { it.isNotBlank() }
                ?.let { artist = it }
            val genres = document.select("a[href*=\"/genre/\"]").eachText()
            if (genres.isNotEmpty()) genre = genres.joinToString()
            document.selectFirst("#mangaDesc .manga-desc__content")?.let { description = it.text() }
            val statusText = document.selectFirst(".manga-status")?.attr("data-status")
            status = when (statusText) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                "canceled" -> SManga.CANCELLED
                "on-hold" -> SManga.ON_HIATUS
                "incomplete" -> SManga.PUBLISHING_FINISHED
                else -> SManga.UNKNOWN
            }
            document.selectFirst("head meta[property=og:image]")?.let { thumbnail_url = it.absUrl("content") }
        }
    }

    // ============================== Chapters =============================

    override fun getChapterUrl(chapter: SChapter): String =
        if (chapter.url.startsWith("http")) chapter.url else baseUrl + chapter.url

    override fun chapterListRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asDocument()
        return document.select("#chaptersList a.chapter-item").mapNotNull { element ->
            SChapter.create().apply {
                url = element.absUrl("href")
                name = element.selectFirst(".chapter-item__name")?.text()?.trim().orEmpty()
                chapter_number = CHAPTER_NUMBER_REGEX.find(name)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
                element.selectFirst(".chapter-item__date")?.text()?.let {
                    date_upload = parseChapterDate(it)
                }
            }
        }.asReversed()
    }

    // =============================== Pages ===============================

    override fun pageListRequest(chapter: SChapter): Request =
        GET(getChapterUrl(chapter), headers)

    override fun pageListParse(response: Response): List<Page> {
        val chapterUrl = response.request.url.toString()
        val html = response.body.string()
        val chapterData = CHAPTER_DATA_REGEX.find(html)?.groupValues?.get(1)
            ?: throw IOException("Chapter data not found")
        val json = try {
            JSONObject(chapterData)
        } catch (e: Exception) {
            throw IOException("Invalid chapter data", e)
        }
        val base = json.getString("base").removeSuffix("/")
        val decoded = try {
            String(Base64.decode(json.getString("data"), Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: Exception) {
            throw IOException("Invalid chapter images", e)
        }
        val pagesJson = try {
            JSONArray(decoded)
        } catch (e: Exception) {
            throw IOException("Invalid chapter images", e)
        }
        return buildList {
            for (i in 0 until pagesJson.length()) {
                val item = pagesJson.getJSONObject(i)
                val src = item.getString("src")
                if (src.isNotBlank()) add(Page(i, chapterUrl, "$base/$src"))
            }
        }
    }

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl ?: page.url, headers.newBuilder().set("Referer", page.url).build())

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ========================== Image helpers ============================

    private fun Response.asDocument(): Document {
        val body = body?.string() ?: throw IOException("Empty response body")
        return Jsoup.parse(body, request.url.toString())
    }

    private fun parseChapterDate(date: String?): Long {
        date ?: return 0L
        return try {
            SimpleDateFormat("dd/MM/yyyy", Locale.US).parse(date.trim())?.time ?: 0L
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

        private val CHAPTER_DATA_REGEX = Regex("""var\s+chapterData\s*=\s*(\{.*\})\s*;""")
    }
}

// Mirrors the /genre/ taxonomy on https://manhwaread.com.
private val GENRES = listOf(
    "Action" to "action",
    "Adult" to "adult",
    "Adventure" to "adventure",
    "Comedy" to "comedy",
    "Drama" to "drama",
    "Ecchi" to "ecchi",
    "Fantasy" to "fantasy",
    "Gender Bender" to "gender-bender",
    "Harem" to "harem",
    "Hentai" to "hentai",
    "Historical" to "historical",
    "Horror" to "horror",
    "Isekai" to "isekai",
    "Josei" to "josei",
    "Mahou Shoujo" to "mahou-shoujo",
    "Martial Arts" to "martial-arts",
    "Mature" to "mature",
    "Mystery" to "mystery",
    "Psychological" to "psychological",
    "Romance" to "romance",
    "School Life" to "school-life",
    "Sci-Fi" to "sci-fi",
    "Seinen" to "seinen",
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
