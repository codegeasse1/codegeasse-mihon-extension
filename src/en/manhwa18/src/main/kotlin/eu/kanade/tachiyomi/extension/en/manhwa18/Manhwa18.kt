package eu.kanade.tachiyomi.extension.en.manhwa18

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
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/*
 * Manhwa18 (https://manhwa18.net) — an 18+ manhwa/manhua site built on a
 * Laravel + Inertia.js app. Every page is a static shell whose full content
 * lives in a JSON payload on the `#app` element's `data-page` attribute.
 *
 *     LIST    : GET /manga-list?sort=update|new|top|like|az|za&page=N
 *               GET /genre/<slug>?sort=..&page=N
 *     SEARCH  : GET /tim-kiem?q=..&page=N
 *     DETAILS : GET /manga/<slug>              -> props.manga + props.chapters
 *     CHAPTER : GET /manga/<slug>/<ch-slug>    -> props.chapterImages[].src
 *
 * Chapter images and covers are served from an open CDN (min.manhwa18.net)
 * with no hotlink protection, so plain requests with a browser UA + referer
 * are enough.
 */
class Manhwa18 : HttpSource() {

    override val name = "Manhwa18"
    override val baseUrl = "https://manhwa18.net"
    override val lang = "en"
    override val supportsLatest = true

    private val gson = Gson()

    override fun headersBuilder(): Headers.Builder =
        super.headersBuilder()
            .set("User-Agent", BROWSER_UA)
            .set("Referer", "$baseUrl/")

    private fun apiHeaders(): Headers = headersBuilder().build()

    // ========================== Browse & Search ===========================

    override fun getFilterList(): FilterList = FilterList(
        SortFilter(),
        Filter.Separator(),
        GenreFilter(GENRES),
    )

    override fun popularMangaRequest(page: Int): Request =
        listRequest("$baseUrl/manga-list", "top", null, page)

    override fun popularMangaParse(response: Response): MangasPage = listParse(response)

    override fun latestUpdatesRequest(page: Int): Request =
        listRequest("$baseUrl/manga-list", "update", null, page)

    override fun latestUpdatesParse(response: Response): MangasPage = listParse(response)

    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request {
        val sort = filters.filterIsInstance<SortFilter>().firstOrNull()?.selected ?: ""
        val genre = filters.filterIsInstance<GenreFilter>().firstOrNull()?.selected ?: ""

        if (query.isNotBlank()) {
            val q = query.filter { it.isLetterOrDigit() || it == ' ' }
                .trim()
                .take(QUERY_LIMIT)
            if (q.isNotBlank()) {
                return GET("$baseUrl/tim-kiem".toHttpUrl().newBuilder()
                    .addQueryParameter("q", q)
                    .addQueryParameter("page", page.toString())
                    .build(), apiHeaders())
            }
        }

        if (genre.isNotBlank()) {
            return listRequest("$baseUrl/genre/$genre", sort, null, page)
        }
        return listRequest("$baseUrl/manga-list", sort.ifBlank { "update" }, null, page)
    }

    override fun searchMangaParse(response: Response): MangasPage = listParse(response)

    private fun listRequest(path: String, sort: String, q: String?, page: Int): Request {
        val builder = path.toHttpUrl().newBuilder()
        if (sort.isNotBlank()) builder.addQueryParameter("sort", sort)
        if (!q.isNullOrBlank()) builder.addQueryParameter("q", q)
        builder.addQueryParameter("page", page.toString())
        return GET(builder.build(), apiHeaders())
    }

    private fun listParse(response: Response): MangasPage {
        val dataPage = response.body?.string().orEmpty().dataPage() ?: return MangasPage(emptyList(), false)
        val props = dataPage.obj("props") ?: return MangasPage(emptyList(), false)
        val paginator = props.obj("paginate") ?: props.obj("mangas") ?: return MangasPage(emptyList(), false)
        val items = paginator.arr("data") ?: return MangasPage(emptyList(), false)
        val mangas = items.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = o.str("name") ?: return@mapNotNull null
            val slug = o.str("slug") ?: return@mapNotNull null
            SManga.create().apply {
                this.title = name
                url = "/manga/$slug"
                thumbnail_url = o.str("cover_url")
            }
        }
        return MangasPage(mangas, hasNextPage = paginator.str("next_page_url") != null)
    }

    // ========================== Manga Details =============================

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), apiHeaders())

    override fun mangaDetailsParse(response: Response): SManga {
        val body = response.body?.string().orEmpty()
        val dataPage = body.dataPage()
            ?: throw Exception("Could not find page data")
        val manga = dataPage.obj("props")?.obj("manga")
            ?: throw Exception("Could not find manga data")

        val slug = manga.str("slug").orEmpty()
        return SManga.create().apply {
            title = manga.str("name").orEmpty()
            url = "/manga/$slug"
            thumbnail_url = manga.str("cover_url")
            author = joinNames(manga, "artists")
            artist = joinNames(manga, "artists")
            this.status = statusOf(manga.int("status_id"))
            genre = joinNames(manga, "genres")
            description = manga.str("pilot")?.let { Jsoup.parse(it).text() }
        }
    }

    // ============================= Chapters ===============================

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    override fun chapterListRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), apiHeaders())

    override fun chapterListParse(response: Response): List<SChapter> {
        val body = response.body?.string().orEmpty()
        val dataPage = body.dataPage() ?: return emptyList()
        val props = dataPage.obj("props") ?: return emptyList()
        val manga = props.obj("manga") ?: return emptyList()
        val slug = manga.str("slug").orEmpty()
        val chapters = props.arr("chapters") ?: return emptyList()

        return chapters.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val chapterSlug = o.str("slug") ?: return@mapNotNull null
            SChapter.create().apply {
                url = "/manga/$slug/$chapterSlug"
                name = o.str("name").orEmpty()
                date_upload = parseDate(o.str("created_at"))
                chapter_number = o.num("order") ?: 0f
            }
        }.sortedByDescending { it.chapter_number }
    }

    // =============================== Pages ================================

    override fun pageListRequest(chapter: SChapter): Request =
        GET(getChapterUrl(chapter), apiHeaders())

    override fun pageListParse(response: Response): List<Page> {
        val body = response.body?.string().orEmpty()
        val dataPage = body.dataPage() ?: return emptyList()
        val images = dataPage.obj("props")?.arr("chapterImages") ?: return emptyList()
        return images.mapIndexedNotNull { index, el ->
            if (el.isJsonNull) return@mapIndexedNotNull null
            val src = (el as? JsonObject)?.str("src") ?: return@mapIndexedNotNull null
            Page(index, imageUrl = src)
        }
    }

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl ?: page.url, apiHeaders())

    // ============================== Parsers ===============================

    private fun String.dataPage(): JsonObject? {
        val match = DATA_PAGE_REGEX.find(this) ?: return null
        val json = runCatching { Parser.unescapeEntities(match.groupValues[1], false) }.getOrNull() ?: return null
        return runCatching { gson.fromJson(json, JsonObject::class.java) }.getOrNull()
    }

    private fun joinNames(o: JsonObject, key: String): String =
        o.arr(key)?.mapNotNull { el ->
            if (el.isJsonObject) (el as JsonObject).str("name") else null
        }?.distinct()?.joinToString() ?: ""

    private fun statusOf(statusId: Int?): Int = when (statusId) {
        1 -> SManga.COMPLETED
        2 -> SManga.CANCELLED
        else -> SManga.ONGOING
    }

    // ========================== JSON helpers ==============================

    private fun JsonObject.opt(key: String): JsonElement? = get(key)?.takeIf { !it.isJsonNull }
    private fun JsonObject.str(key: String): String? = opt(key)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.num(key: String): Float? = opt(key)?.takeIf { it.isJsonPrimitive }?.asFloat
    private fun JsonObject.int(key: String): Int? = opt(key)?.takeIf { it.isJsonPrimitive }?.asInt
    private fun JsonObject.obj(key: String): JsonObject? = opt(key)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.arr(key: String): JsonArray? = opt(key)?.takeIf { it.isJsonArray }?.asJsonArray

    // ========================== Date helpers ==============================

    private fun parseDate(s: String?): Long {
        if (s.isNullOrBlank()) return 0L
        // Laravel emits microseconds (.000000) which SimpleDateFormat can't
        // handle reliably, so strip the fractional part first.
        val clean = s.replace(MICROSECONDS_REGEX, "")
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(clean).time
        }.getOrNull() ?: 0L
    }

    companion object {
        private const val QUERY_LIMIT = 50

        private val DATA_PAGE_REGEX = Regex("""data-page="([^"]*)"""")
        private val MICROSECONDS_REGEX = Regex("""\.\d{6}(?=Z|$)""")

        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        private val GENRES = listOf(
            "Action", "Adult", "Adventure", "AI Art", "Animal Characteristics", "Art",
            "Based on Another Work", "Borderline H", "Cohabitation", "Collection of Stories",
            "Comedy", "Coworkers", "Crime", "Delinquents", "Demons", "Doujinshi", "Drama",
            "Ecchi", "Explicit Sex", "Fantasy", "Fetish", "Full Color", "Ghosts", "GL",
            "Gyaru", "Harem", "Historical", "Horror", "Incest", "Isekai", "Japanese Webtoons",
            "M18Scan", "Magic", "Magical Girl", "Manga", "Manhwa", "Mature", "Medical",
            "Monster Girls", "Monsters", "Mystery", "NTR", "Nudity", "Psychological", "Raw",
            "Reincarnation", "Revenge", "Reverse Harem", "Romance", "Salaryman", "School Life",
            "Sci Fi", "Seinen", "Sexual Abuse", "Sexual Content", "Siblings", "Slice of Life",
            "Smut", "Sports", "Summoned Into Another World", "Supernatural", "Survival",
            "Thriller", "Time Travel", "Uncensored", "Violence", "Webtoon", "Webtoons",
            "Work Life", "Yuri",
        )
    }
}

// ============================== Filters ==================================

private class SortFilter : Filter.Select<String>(
    "Sort By",
    arrayOf(
        "Latest Update" to "update",
        "Newest" to "new",
        "Most Views" to "top",
        "Most Liked" to "like",
        "A-Z" to "az",
        "Z-A" to "za",
    ).map { it.first }.toTypedArray(),
) {
    val selected: String
        get() = arrayOf(
            "update", "new", "top", "like", "az", "za",
        ).getOrNull(state ?: 0) ?: ""
}

private class GenreFilter(genres: List<String>) : Filter.Select<String>(
    "Genre",
    arrayOf("All") + genres.map { it.replaceFirstChar { c -> c.uppercase() } },
) {
    private val slugs = arrayOf("") + genres.map { it.lowercase().replace(' ', '-') }
    val selected: String
        get() = slugs.getOrNull(state ?: 0) ?: ""
}
