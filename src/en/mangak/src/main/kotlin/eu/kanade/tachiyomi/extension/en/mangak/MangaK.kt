package eu.kanade.tachiyomi.extension.en.mangak

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/*
 * MangaK (https://mangak.io) — "manga-reader" template (Next.js frontend +
 * a NestJS JSON API at https://api.mangak.io). Browsing, search, details and
 * the chapter list all come from the JSON API; chapter page images are read
 * from the server-rendered chapter page's __NEXT_DATA__ payload (the API's
 * images endpoint only returns a few thumbnails).
 *
 *     API   : GET {api}/titles/search?sort=popular&window=week&page=N
 *             GET {api}/titles/search?sort=latest&page=N
 *             GET {api}/titles/search?q=..&genres=..&status=..&page=N ...
 *             GET {api}/titles/{id}           (full details)
 *             GET {api}/titles/{id}/chapters  (all chapters, newest first)
 *     HTML  : /<slug>/               -> __NEXT_DATA__.pageProps.initialManga
 *             /<slug>/<chapter-slug>  -> __NEXT_DATA__.pageProps.initialChapter.images
 *
 * Browse results carry the site's short manga id in the URL fragment
 * ("/slug#<id>"), so details/chapters hit the API directly. A manga opened
 * from a bare URL falls back to its SSR page, which carries the same id.
 *
 * Image CDNs (rx.resmk.org covers, rx.qvzr*.org pages) are Cloudflare-gated:
 * requests must carry a browser User-Agent + mangak.io Referer. Datacenter
 * IPs are blocked outright; residential traffic passes.
 */
class MangaK : HttpSource() {

    override val name = "MangaK"
    override val baseUrl = "https://mangak.io"
    override val lang = "en"
    override val supportsLatest = true

    private val apiUrl = "https://api.mangak.io"

    private val gson = Gson()

    // Used for the synchronous HTML->API chapter re-fetch inside a parse
    // (plain OkHttpClient -> no Injekt graph dependency, Tachidesk-safe).
    private val directClient: OkHttpClient by lazy { OkHttpClient() }

    override fun headersBuilder(): Headers.Builder =
        super.headersBuilder()
            .set("User-Agent", BROWSER_UA)

    private fun apiHeaders(): Headers = headersBuilder().build()

    private fun imageHeaders(): Headers = headersBuilder()
        .set("Referer", "$baseUrl/")
        .build()

    // ========================== Browse & Search ===========================

    override fun getFilterList(): FilterList = FilterList(
        SortFilter(),
        ContentRatingFilter(),
        StatusFilter(),
        TypeFilter(),
        DemographicFilter(),
        Filter.Separator(),
        AuthorFilter(),
        MinChaptersFilter(),
        Filter.Separator(),
        GenreList(GENRES.map { Genre(it.first, it.second) }),
    )

    override fun popularMangaRequest(page: Int): Request =
        GET(
            "$apiUrl/titles/search?sort=popular&window=week&page=$page&limit=$PAGE_LIMIT",
            apiHeaders(),
        )

    override fun popularMangaParse(response: Response): MangasPage = searchMangaParse(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$apiUrl/titles/search?sort=latest&page=$page&limit=$PAGE_LIMIT", apiHeaders())

    override fun latestUpdatesParse(response: Response): MangasPage = searchMangaParse(response)

    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request {
        val url = "$apiUrl/titles/search".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            addQueryParameter("limit", PAGE_LIMIT.toString())

            if (query.isNotBlank()) {
                val q = query.filter { it.isLetterOrDigit() || it == ' ' }
                    .trim()
                    .take(QUERY_LIMIT)
                if (q.isNotBlank()) addQueryParameter("q", q)
            }

            filters.forEach { filter ->
                when (filter) {
                    is SortFilter -> filter.selected.takeIf { it.isNotBlank() }?.let { addQueryParameter("sort", it) }
                    is ContentRatingFilter -> filter.selected.takeIf { it.isNotBlank() }?.let { addQueryParameter("content_rating", it) }
                    is StatusFilter -> filter.selected.takeIf { it.isNotBlank() }?.let { addQueryParameter("status", it) }
                    is TypeFilter -> filter.selected.takeIf { it.isNotBlank() }?.let { addQueryParameter("type", it) }
                    is DemographicFilter -> filter.selected.takeIf { it.isNotBlank() }?.let { addQueryParameter("demographic", it) }
                    is GenreList -> {
                        val included = filter.state
                            .filter { it.state == Filter.TriState.STATE_INCLUDE }
                            .map { it.value }
                        if (included.isNotEmpty()) addQueryParameter("genres", included.joinToString(","))
                    }
                    is AuthorFilter -> filter.state.trim().takeIf { it.isNotBlank() }?.let { addQueryParameter("author", it) }
                    is MinChaptersFilter -> filter.state.trim().takeIf { it.isNotBlank() }?.let { addQueryParameter("min_ch", it) }
                    else -> {}
                }
            }
        }.build()
        return GET(url, apiHeaders())
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val root = runCatching { gson.fromJson(response.body?.string(), JsonObject::class.java) }
            .getOrNull() ?: return MangasPage(emptyList(), false)
        val data = root.data() ?: return MangasPage(emptyList(), false)
        val mangas = data.items()?.mapNotNull { el -> (el as? JsonObject)?.toSManga() } ?: emptyList()
        return MangasPage(mangas, hasNextPage = data.pagination()?.hasNext() ?: false)
    }

    // ========================== Manga Details =============================

    override fun getMangaUrl(manga: SManga): String = baseUrl + pathOf(manga.url)

    override fun mangaDetailsRequest(manga: SManga): Request {
        val id = idOf(manga.url)
        return if (id != null) {
            GET("$apiUrl/titles/$id", apiHeaders())
        } else {
            GET(getMangaUrl(manga), apiHeaders())
        }
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val body = response.body?.string().orEmpty()
        if (body.trimStart().startsWith("{")) return parseDetailsApi(body)
        return parseDetailsHtml(body)
    }

    // ============================= Chapters ===============================

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    override fun chapterListRequest(manga: SManga): Request {
        val id = idOf(manga.url)
        return if (id != null) {
            GET("$apiUrl/titles/$id/chapters?cv=${System.currentTimeMillis()}", apiHeaders())
        } else {
            GET(getMangaUrl(manga), apiHeaders())
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val body = response.body?.string().orEmpty()
        if (body.trimStart().startsWith("{")) return parseChaptersJson(body)

        // Bare-URL fallback: grab the id from the SSR page, then re-fetch the
        // full chapter list through the API.
        val id = runCatching {
            Jsoup.parse(body)
                .nextJsData()?.obj("props")?.obj("pageProps")?.obj("initialManga")?.id()
        }.getOrNull() ?: return emptyList()

        val json = runCatching {
            directClient.newCall(
                GET("$apiUrl/titles/$id/chapters?cv=${System.currentTimeMillis()}", apiHeaders()),
            ).execute().use { it.body?.string() }
        }.getOrNull()

        return if (json == null) emptyList() else parseChaptersJson(json)
    }

    // =============================== Pages ================================

    override fun pageListRequest(chapter: SChapter): Request =
        GET(getChapterUrl(chapter), apiHeaders())

    override fun pageListParse(response: Response): List<Page> {
        val body = response.body?.string().orEmpty()
        val images = runCatching {
            Jsoup.parse(body)
                .nextJsData()?.obj("props")?.obj("pageProps")?.obj("initialChapter")?.arr("images")
        }.getOrNull() ?: return emptyList()
        return images.mapIndexedNotNull { index, el ->
            if (el.isJsonNull) null else Page(index, el.asString)
        }
    }

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl ?: page.url, imageHeaders())

    // ============================== Parsers ===============================

    private fun parseDetailsApi(body: String): SManga {
        val root = gson.fromJson(body, JsonObject::class.java)
        val title = root.data()?.obj("title")
            ?: throw Exception("Malformed details response")
        return title.toSMangaDetails()
    }

    private fun parseDetailsHtml(body: String): SManga {
        val initial = runCatching {
            Jsoup.parse(body).nextJsData()?.obj("props")?.obj("pageProps")?.obj("initialManga")
        }.getOrNull() ?: throw Exception("Could not extract manga details")
        return initial.toSMangaDetails()
    }

    private fun parseChaptersJson(body: String): List<SChapter> {
        val root = runCatching { gson.fromJson(body, JsonObject::class.java) }.getOrNull()
            ?: return emptyList()
        val chapters = root.data()?.arr("chapters") ?: return emptyList()
        return chapters.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            SChapter.create().apply {
                url = o.str("url").orEmpty()
                name = o.str("name").orEmpty()
                date_upload = parseDate(o.str("updated_at"))
                chapter_number = o.num("number") ?: 0f
            }
        }.sortedByDescending { it.chapter_number }
    }

    // ========================== JSON helpers ==============================

    private fun JsonObject.opt(key: String): JsonElement? = get(key)?.takeIf { !it.isJsonNull }
    private fun JsonObject.str(key: String): String? = opt(key)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.num(key: String): Float? = opt(key)?.takeIf { it.isJsonPrimitive }?.asFloat
    private fun JsonObject.obj(key: String): JsonObject? = opt(key)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.arr(key: String): JsonArray? = opt(key)?.takeIf { it.isJsonArray }?.asJsonArray
    private fun JsonObject.data(): JsonObject? = obj("data")
    private fun JsonObject.items(): JsonArray? = arr("items")
    private fun JsonObject.pagination(): JsonObject? = obj("pagination")
    private fun JsonObject.id(): String? = str("id")
    private fun JsonObject.hasNext(): Boolean = opt("has_next")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false

    private fun JsonObject.toSManga(): SManga? {
        val title = str("name") ?: return null
        val path = str("url") ?: return null
        val id = str("id") ?: return null
        return SManga.create().apply {
            this.title = title
            url = "$path#$id"
            thumbnail_url = str("cover")
        }
    }

    private fun JsonObject.toSMangaDetails(): SManga {
        val path = str("url") ?: str("slug")?.let { "/$it" } ?: ""
        val id = str("id").orEmpty()
        return SManga.create().apply {
            title = str("name").orEmpty()
            url = "$path#$id"
            thumbnail_url = str("cover")
            author = joinNames(this@toSMangaDetails, "authors")
                .ifEmpty { joinNames(this@toSMangaDetails, "artists") }
            artist = joinNames(this@toSMangaDetails, "artists")
            this.status = statusOf(str("status"))
            genre = joinNames(this@toSMangaDetails, "genres")
            description = str("summary").orEmpty()
        }
    }

    private fun joinNames(o: JsonObject, key: String): String =
        o.arr(key)?.mapNotNull { el ->
            when {
                el.isJsonObject -> (el as JsonObject).str("name")
                el.isJsonPrimitive -> el.asString
                else -> null
            }
        }?.distinct()?.joinToString() ?: ""

    private fun statusOf(s: String?): Int = when (s?.lowercase()) {
        "ongoing" -> SManga.ONGOING
        "completed" -> SManga.COMPLETED
        "hiatus" -> SManga.ON_HIATUS
        "cancelled" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    private fun Document.nextJsData(): JsonObject? {
        val script = selectFirst("script#__NEXT_DATA__") ?: return null
        return runCatching { gson.fromJson(script.data(), JsonObject::class.java) }.getOrNull()
    }

    // ========================== Date helpers ==============================

    private fun parseDate(s: String?): Long {
        if (s.isNullOrBlank()) return 0L
        for (f in dateFormats) {
            val t = runCatching { f.parse(s).time }.getOrNull()
            if (t != null) return t
        }
        return 0L
    }

    companion object {
        private const val PAGE_LIMIT = 24
        private const val QUERY_LIMIT = 50

        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        private val dateFormats = listOf(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US),
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US),
        ).map { it.apply { timeZone = TimeZone.getTimeZone("UTC") } }
    }
}

// ============================== Filters ==================================

private open class SelectFilter(name: String, private val vals: Array<Pair<String, String>>) :
    Filter.Select<String>(name, vals.map { it.first }.toTypedArray()) {
    val selected: String
        get() = vals.getOrNull(state ?: 0)?.second ?: ""
}

private class SortFilter : SelectFilter(
    "Sort By",
    arrayOf(
        "Best Match" to "",
        "Most Followed" to "popular",
        "Latest Updated" to "latest",
        "Recently Added" to "newest",
        "Highest Rating" to "rating",
        "Most Viewed: Today" to "views_today",
        "Most Viewed: 7 Days" to "views_7days",
        "Most Viewed: 30 Days" to "views_30days",
        "Most Viewed: All Time" to "views",
        "Most Chapters" to "chapters",
        "A-Z" to "alphabetical",
    ),
)

private class ContentRatingFilter : SelectFilter(
    "Content Rating",
    arrayOf(
        "Any" to "",
        "Safe" to "safe",
        "Suggestive" to "suggestive",
        "Erotica" to "erotica",
        "Pornographic" to "pornographic",
    ),
)

private class StatusFilter : SelectFilter(
    "Status",
    arrayOf(
        "Any" to "",
        "Ongoing" to "ongoing",
        "Completed" to "completed",
        "Hiatus" to "hiatus",
        "Cancelled" to "cancelled",
    ),
)

private class TypeFilter : SelectFilter(
    "Type",
    arrayOf(
        "Any" to "",
        "Manga" to "manga",
        "Manhwa" to "manhwa",
        "Manhua" to "manhua",
    ),
)

private class DemographicFilter : SelectFilter(
    "Demographics",
    arrayOf(
        "Any" to "",
        "Boy (Shounen + Seinen)" to "shounen,seinen",
        "Girl (Shoujo + Josei)" to "shoujo,josei",
        "Shounen" to "shounen",
        "Shoujo" to "shoujo",
        "Seinen" to "seinen",
        "Josei" to "josei",
    ),
)

private class AuthorFilter : Filter.Text("Author")

private class MinChaptersFilter : Filter.Text("Min Chapters")

private class Genre(name: String, val value: String) : Filter.TriState(name)

private class GenreList(genres: List<Genre>) : Filter.Group<Genre>("Genres", genres)

// ============================== Genres ===================================
// Mirrors the site's /genres taxonomy (name -> slug).

private val GENRES = listOf(
    "Action" to "action",
    "Adaptation" to "adaptation",
    "Adult" to "adult",
    "Adventure" to "adventure",
    "Anthology" to "anthology",
    "Boys Love" to "boys-love",
    "Comedy" to "comedy",
    "Comic" to "comic",
    "Cooking" to "cooking",
    "Demons" to "demons",
    "Doujinshi" to "doujinshi",
    "Drama" to "drama",
    "Ecchi" to "ecchi",
    "Fantasy" to "fantasy",
    "Full Color" to "full-color",
    "Game" to "game",
    "Gender bender" to "gender-bender",
    "Ghosts" to "ghosts",
    "Harem" to "harem",
    "Hentai" to "hentai",
    "Historical" to "historical",
    "Horror" to "horror",
    "Isekai" to "isekai",
    "Josei" to "josei",
    "Long strip" to "long-strip",
    "Magic" to "magic",
    "Manga" to "manga",
    "Manhua" to "manhua",
    "Manhwa" to "manhwa",
    "Martial arts" to "martial-arts",
    "Mature" to "mature",
    "Mecha" to "mecha",
    "Medical" to "medical",
    "Military" to "military",
    "Monster" to "monster",
    "Monster girls" to "monster-girls",
    "Monsters" to "monsters",
    "Music" to "music",
    "Mystery" to "mystery",
    "Office workers" to "office-workers",
    "One shot" to "one-shot",
    "Police" to "police",
    "Psychological" to "psychological",
    "Reincarnation" to "reincarnation",
    "Romance" to "romance",
    "School life" to "school-life",
    "Sci fi" to "sci-fi",
    "Science fiction" to "science-fiction",
    "Shoujo" to "shoujo",
    "Shoujo ai" to "shoujo-ai",
    "Shounen" to "shounen",
    "Shounen ai" to "shounen-ai",
    "Slice of life" to "slice-of-life",
    "Smut" to "smut",
    "Soft Yaoi" to "soft-yaoi",
    "Sports" to "sports",
    "Super Power" to "super-power",
    "Superhero" to "superhero",
    "Supernatural" to "supernatural",
    "Thriller" to "thriller",
    "Time travel" to "time-travel",
    "Tragedy" to "tragedy",
    "Vampire" to "vampire",
    "Vampires" to "vampires",
    "Video games" to "video-games",
    "Villainess" to "villainess",
    "Web comic" to "web-comic",
    "Webtoons" to "webtoons",
    "Worth the read" to "worth-the-read",
    "Yaoi" to "yaoi",
    "Yuri" to "yuri",
    "Zombies" to "zombies",
)
