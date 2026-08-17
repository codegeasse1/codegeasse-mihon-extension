package eu.kanade.tachiyomi.extension.en.toontop

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/*
 * ToonTop (https://toontop.io) — a Next.js "flux" theme reader (mangak.io
 * family). All data is served through two channels:
 *
 *   API  : https://api.toontop.io/titles/search        (browse + search)
 *          https://api.toontop.io/titles/<id>/chapters  (full chapter list)
 *   SSR  : every page embeds its payload in a <script id="__NEXT_DATA__">
 *          JSON blob — manga detail (initialManga) and chapter pages
 *          (initialChapter.images), which is what we parse here.
 *
 *     Browse : titles/search?sort=latest|popular&page=<N>&limit=24
 *     Search : titles/search?q=<query>[&genres=<slug>]&page=<N>&limit=24
 *     Manga  : /<slug>  -> __NEXT_DATA__.props.pageProps.initialManga
 *     Chapter: /<slug>/chapter-<n> -> initialChapter.images[] (rx.toontop.io
 *              CDN; image requests carry a toontop.io Referer)
 *
 * The chapter list lives on the API keyed by the manga's alphanumeric id,
 * which we read out of the detail page's Next.js blob (the SSR detail page
 * itself only renders the newest 50 chapters). Adult/uncensored webtoons.
 */
class ToonTop : HttpSource() {

    override val name = "ToonTop"
    override val baseUrl = "https://toontop.io"
    override val lang = "en"
    override val supportsLatest = true

    private val apiUrl = "https://api.toontop.io"

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
        GET(
            searchUrlBuilder(page)
                .addQueryParameter("sort", SORT_POPULAR)
                .addQueryParameter("window", WINDOW_WEEK)
                .build(),
            headers,
        )

    override fun popularMangaParse(response: Response): MangasPage =
        parseList(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET(
            searchUrlBuilder(page)
                .addQueryParameter("sort", SORT_LATEST)
                .build(),
            headers,
        )

    override fun latestUpdatesParse(response: Response): MangasPage =
        parseList(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val genreFilter = filters.filterIsInstance<GenreList>().firstOrNull()
        val genres = genreFilter
            ?.state
            ?.filter { it.isIncluded() }
            ?.joinToString(",") { it.key }
            ?.takeIf { it.isNotBlank() }

        return GET(
            searchUrlBuilder(page).apply {
                if (query.isNotBlank()) {
                    addQueryParameter("q", URLEncoder.encode(query, "utf-8").replace("+", "%20"))
                }
                if (genres != null) {
                    addQueryParameter("genres", genres)
                }
            }.build(),
            headers,
        )
    }

    override fun searchMangaParse(response: Response): MangasPage =
        parseList(response)

    private fun searchUrlBuilder(page: Int): HttpUrl.Builder =
        "$apiUrl/titles/search".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PAGE_LIMIT)

    private fun parseList(response: Response): MangasPage {
        val data = response.asJson().optJSONObject("data") ?: JSONObject()
        val items = data.optJSONArray("items") ?: JSONArray()
        val mangas = (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val title = item.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SManga.create().apply {
                setUrlWithoutDomain(item.optString("url"))
                this.title = title
                thumbnail_url = item.optString("cover")
            }
        }
        val pagination = data.optJSONObject("pagination")
        val hasNextPage = pagination?.optBoolean("has_next", false) ?: false
        return MangasPage(mangas, hasNextPage)
    }

    // =========================== Manga Details ===========================

    override fun getMangaUrl(manga: SManga): String =
        if (manga.url.startsWith("http")) manga.url else baseUrl + manga.url

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asDocument()
        val manga = document.nextDataPageProps().optJSONObject("initialManga") ?: JSONObject()
        return SManga.create().apply {
            title = manga.optString("name")
            setUrlWithoutDomain(response.request.url.toString())
            manga.optJSONArray("authors")?.let { authors ->
                (0 until authors.length())
                    .mapNotNull { authors.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
                    .joinToString(", ")
                    .takeIf { it.isNotBlank() }
                    ?.let { author = it }
            }
            manga.optJSONArray("genres")?.let { genres ->
                (0 until genres.length())
                    .mapNotNull { genres.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
                    .joinToString()
                    .takeIf { it.isNotBlank() }
                    ?.let { genre = it }
            }
            manga.optString("status").lowercase(Locale.US).let { text ->
                status = when {
                    text.contains("ongoing") -> SManga.ONGOING
                    text.contains("hiatus") || text.contains("on-hold") || text.contains("on_hold") -> SManga.ON_HIATUS
                    text.contains("cancel") || text.contains("drop") -> SManga.CANCELLED
                    text.contains("completed") || text.contains("end") -> SManga.COMPLETED
                    else -> SManga.UNKNOWN
                }
            }
            manga.optString("summary")
                .takeIf { it.isNotBlank() }
                ?.let { description = it }
            manga.optString("cover")
                .takeIf { it.isNotBlank() }
                ?.let { thumbnail_url = it }
        }
    }

    // ============================== Chapters =============================

    override fun getChapterUrl(chapter: SChapter): String =
        if (chapter.url.startsWith("http")) chapter.url else baseUrl + chapter.url

    override fun chapterListRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asDocument()
        val mangaId = document.nextDataPageProps()
            .optJSONObject("initialManga")
            ?.optString("id")
            ?.takeIf { it.isNotBlank() }
            ?: throw IOException("Could not find manga id in Next.js data")

        val chaptersBody = client.newCall(
            GET("$apiUrl/titles/$mangaId/chapters?cv=${System.currentTimeMillis()}", headers),
        ).execute().use { it.body?.string() ?: throw IOException("Empty chapter list response") }

        val chapters = JSONObject(chaptersBody)
            .optJSONObject("data")
            ?.optJSONArray("chapters")
            ?: JSONArray()

        return (0 until chapters.length()).mapNotNull { index ->
            val chapter = chapters.optJSONObject(index) ?: return@mapNotNull null
            val chapterName = chapter.optString("name").takeIf { it.isNotBlank() }
                ?: chapter.optString("slug")
            SChapter.create().apply {
                url = chapter.optString("url")
                name = chapterName
                date_upload = parseDate(chapter.optString("updated_at"))
                chapter_number = CHAPTER_NUMBER_REGEX.find(chapterName)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
            }
        }.sortedByDescending { it.chapter_number }
    }

    // =============================== Pages ===============================

    override fun pageListRequest(chapter: SChapter): Request =
        GET(getChapterUrl(chapter), headers)

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asDocument()
        val images = document.nextDataPageProps()
            .optJSONObject("initialChapter")
            ?.optJSONArray("images")
            ?: return emptyList()
        val chapterUrl = response.request.url.toString()
        return (0 until images.length()).mapNotNull { index ->
            val imageUrl = images.optString(index).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Page(index, chapterUrl, imageUrl)
        }
    }

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl ?: page.url, headers.newBuilder().set("Referer", "$baseUrl/").build())

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================== Helpers ==============================

    private fun Response.asJson(): JSONObject {
        val body = body?.string() ?: throw IOException("Empty response body")
        return JSONObject(body)
    }

    private fun Response.asDocument(): Document {
        val body = body?.string() ?: throw IOException("Empty response body")
        return Jsoup.parse(body, request.url.toString())
    }

    private fun Document.nextDataPageProps(): JSONObject {
        val script = selectFirst("script#__NEXT_DATA__")
            ?: throw IOException("No Next.js data on page")
        return JSONObject(script.data())
            .optJSONObject("props")
            ?.optJSONObject("pageProps")
            ?: JSONObject()
    }

    private fun parseDate(date: String): Long {
        if (date.isBlank()) return 0L
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(date)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        private const val PAGE_LIMIT = "24"
        private const val SORT_POPULAR = "popular"
        private const val SORT_LATEST = "latest"
        private const val WINDOW_WEEK = "week"

        private val CHAPTER_NUMBER_REGEX = Regex("""(\d+(?:\.\d+)?)""")
    }
}

// Mirrors the genre taxonomy on the site's /genres page.
private val GENRES = listOf(
    "Action" to "action",
    "Adult" to "adult",
    "Adventure" to "adventure",
    "Age Gap" to "age-gap",
    "All Ages" to "all-ages",
    "BDSM" to "bdsm",
    "BL" to "bl",
    "Campus" to "campus",
    "Comedy" to "comedy",
    "Comics" to "comics",
    "Cooking" to "cooking",
    "Crime" to "crime",
    "Demons" to "demons",
    "Doujins- Original Series" to "doujins-original-series",
    "Doujinshi" to "doujinshi",
    "Drama" to "drama",
    "Ecchi" to "ecchi",
    "Family" to "family",
    "Fantasy" to "fantasy",
    "Female Friend" to "female-friend",
    "Fetish" to "fetish",
    "Gender Bender" to "gender-bender",
    "Girls Lacrosse Club" to "girls-lacrosse-club",
    "GL" to "gl",
    "Gossip" to "gossip",
    "Harem" to "harem",
    "Hentai" to "hentai",
    "Hentai Manga" to "hentai-manga",
    "Historical" to "historical",
    "Horror" to "horror",
    "Incest" to "incest",
    "Isekai" to "isekai",
    "Josei" to "josei",
    "Kimi no na wa" to "kimi-no-na-wa",
    "Magic" to "magic",
    "Manga" to "manga",
    "Manhwa" to "manhwa",
    "Manhwa Hentai" to "manhwa-hentai",
    "Martial Arts" to "martial-arts",
    "Mature" to "mature",
    "Milf" to "milf",
    "Military" to "military",
    "Monster Girls" to "monster-girls",
    "Mystery" to "mystery",
    "NTR" to "ntr",
    "Office" to "office",
    "Office Workers" to "office-workers",
    "Original Work" to "original-work",
    "Psychological" to "psychological",
    "Rape" to "rape",
    "Raw" to "raw",
    "Reincarnation" to "reincarnation",
    "Revenge" to "revenge",
    "Romance" to "romance",
    "School life" to "school-life",
    "Sci-Fi" to "sci-fi",
    "Secret Relationship" to "secret-relationship",
    "Seinen" to "seinen",
    "Shoujo" to "shoujo",
    "Shounen" to "shounen",
    "Slice of Life" to "slice-of-life",
    "Smut" to "smut",
    "Sports" to "sports",
    "Supernatural" to "supernatural",
    "Thriller" to "thriller",
    "Tragedy" to "tragedy",
    "Uncensored" to "uncensored",
    "Vanilla" to "vanilla",
    "Webtoon" to "webtoon",
    "Yaoi" to "yaoi",
    "Yuri" to "yuri",
)
