package eu.kanade.tachiyomi.extension.en.toonspace

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/*
 * ToonSpace (https://toon.space) — a webtoon/manhwa SPA whose data comes from a
 * JSON API at https://master.toonspace.io/api. No Cloudflare, no auth required
 * (the app sends a JWT when available but every endpoint works anonymously).
 *
 *     Browse  : /toons?pagination[page]=N&pagination[pageSize]=30&sort[0]=<field>:desc
 *               -> { data: [ { id, documentId, title, urlSlug, description, author,
 *                              isComplete, releasedAt, updatedAt, image:{url},
 *                              genres:[{type}] } ], meta:{pagination:{page,pageCount}} }
 *     Search  : same + &filters[title][$containsi]=<q> and/or &filters[genres][type][$eq]=<g>
 *     Details : /toons?filters[urlSlug][$eq]=<slug>&populate[image]&populate[genres]
 *     Episodes: /episodes?filters[toon][urlSlug][$eq]=<slug>&sort[0]=index:desc&pagination...
 *               -> { data: [ { documentId, index, title, releasedAt } ], meta:{...} }
 *               (single request; the API caps pageSize at 128 = the 128 newest episodes)
 *     Pages   : /episodes?filters[documentId][$eq]=<episodeDocId>&populate=pages
 *               -> the episode's `pages` array with real CDN image URLs.
 */
class ToonSpace : HttpSource() {

    override val name = "ToonSpace"
    override val baseUrl = "https://toon.space"
    override val lang = "en"
    override val supportsLatest = true

    private val apiUrl = "https://master.toonspace.io/api"

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", BROWSER_UA)

    // =========================== Browse & Search =========================

    override fun popularMangaRequest(page: Int): Request =
        GET(toonsUrl(page, "viewsCount:desc"), headers)

    override fun popularMangaParse(response: Response): MangasPage = parseToons(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET(toonsUrl(page, "updatedAt:desc"), headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseToons(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = StringBuilder(toonsUrl(page, "updatedAt:desc"))
        if (query.isNotBlank()) {
            url.append("&filters[title][\$containsi]=")
                .append(URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20"))
        }
        filters.filterIsInstance<GenreFilter>().firstOrNull()?.selected?.let {
            url.append("&filters[genres][type][\$eq]=").append(it)
        }
        return GET(url.toString(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseToons(response)

    override fun getFilterList(): FilterList = FilterList(GenreFilter())

    private fun toonsUrl(page: Int, sort: String): String =
        "$apiUrl/toons?pagination[page]=$page&pagination[pageSize]=$PAGE_SIZE" +
            "&sort[0]=$sort&populate[image][fields][0]=url"

    private fun parseToons(response: Response): MangasPage {
        val root = response.parseJson()
        val pagination = root.objOrNull("meta")?.objOrNull("pagination")
        val page = pagination?.intOrNull("page") ?: 1
        val pageCount = pagination?.intOrNull("pageCount") ?: 1
        val mangas = root.dataArray().mapNotNull { it as? JsonObject }.mapNotNull { obj ->
            val slug = obj.stringOrNull("urlSlug") ?: return@mapNotNull null
            val title = obj.stringOrNull("title")?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SManga.create().apply {
                url = "/t/$slug"
                this.title = title
                thumbnail_url = obj.objOrNull("image")?.stringOrNull("url")
            }
        }
        return MangasPage(mangas, page < pageCount)
    }

    // =========================== Manga Details ===========================

    override fun getMangaUrl(manga: SManga): String = manga.url

    override fun mangaDetailsRequest(manga: SManga): Request {
        val slug = manga.url.substringAfterLast("/")
        return GET(
            "$apiUrl/toons?filters[urlSlug][\$eq]=$slug" +
                "&populate[image][fields][0]=url&populate[genres][fields][0]=type",
            headers,
        )
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val obj = response.parseJson().dataArray().firstOrNull() as? JsonObject ?: return SManga.create()
        return SManga.create().apply {
            url = "/t/${obj.stringOrNull("urlSlug").orEmpty()}"
            title = obj.stringOrNull("title")?.trim().orEmpty()
            thumbnail_url = obj.objOrNull("image")?.stringOrNull("url")
            author = obj.stringOrNull("author")
            status = if (obj.boolOrNull("isComplete") == true) SManga.COMPLETED else SManga.ONGOING
            genre = obj.arrOrNull("genres")
                ?.mapNotNull { (it as? JsonObject)?.stringOrNull("type") }
                ?.joinToString()
            description = obj.stringOrNull("description")
        }
    }

    // ============================== Chapters =============================

    override fun chapterListRequest(manga: SManga): Request {
        val slug = manga.url.substringAfterLast("/")
        return GET(
            "$apiUrl/episodes?filters[toon][urlSlug][\$eq]=$slug" +
                "&sort[0]=index:desc&pagination[page]=1&pagination[pageSize]=$EPISODES_PER_PAGE",
            headers,
        )
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val root = response.parseJson()
        return root.dataArray().mapNotNull { element ->
            val episode = element as? JsonObject ?: return@mapNotNull null
            val docId = episode.stringOrNull("documentId") ?: return@mapNotNull null
            val index = episode.intOrNull("index") ?: return@mapNotNull null
            SChapter.create().apply {
                url = docId
                name = episode.stringOrNull("title")?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: "Episode $index"
                chapter_number = index.toFloat()
                date_upload = episode.stringOrNull("releasedAt")?.let(::parseDate) ?: 0L
            }
        }
    }

    // =============================== Pages ===============================

    override fun getChapterUrl(chapter: SChapter): String =
        "$apiUrl/episodes?filters[documentId][\$eq]=${chapter.url}&populate=pages"

    override fun pageListRequest(chapter: SChapter): Request =
        GET(getChapterUrl(chapter), headers)

    override fun pageListParse(response: Response): List<Page> {
        val episode = response.parseJson().dataArray().firstOrNull() as? JsonObject ?: return emptyList()
        val pages = episode.arrOrNull("pages") ?: return emptyList()
        return pages.mapIndexedNotNull { index, element ->
            val url = (element as? JsonObject)?.stringOrNull("url")
                ?.takeIf { it.isNotBlank() }
                ?: return@mapIndexedNotNull null
            Page(index, url, url)
        }
    }

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl ?: page.url, headers)

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException()

    // =============================== Helpers =============================

    private fun Response.parseJson(): JsonObject {
        val body = body?.string() ?: throw IOException("Empty response body")
        if (!isSuccessful) throw IOException("Unexpected response: $body")
        return JsonParser.parseString(body).asJsonObject
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        if (has(key) && get(key).isJsonPrimitive) get(key).asString else null

    private fun JsonObject.intOrNull(key: String): Int? =
        if (has(key) && get(key).isJsonPrimitive) runCatching { get(key).asInt }.getOrNull() else null

    private fun JsonObject.boolOrNull(key: String): Boolean? =
        if (has(key) && get(key).isJsonPrimitive) runCatching { get(key).asBoolean }.getOrNull() else null

    private fun JsonObject.objOrNull(key: String): JsonObject? =
        if (has(key) && get(key).isJsonObject) get(key).asJsonObject else null

    private fun JsonObject.arrOrNull(key: String): JsonArray? =
        if (has(key) && get(key).isJsonArray) get(key).asJsonArray else null

    private fun JsonObject.dataArray(): JsonArray = arrOrNull("data") ?: JsonArray()

    private fun parseDate(value: String): Long? {
        for (format in DATE_FORMATS) {
            runCatching { return format.parse(value)?.time }
        }
        return null
    }

    // =============================== Filters =============================

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

        private const val PAGE_SIZE = 30
        // Server caps pageSize at 128; series with more episodes show their 128 newest.
        private const val EPISODES_PER_PAGE = 128

        private val GENRES = listOf(
            "Action" to "action",
            "Boys Love" to "boys-love",
            "Comedy" to "comedy",
            "Drama" to "drama",
            "Fantasy" to "fantasy",
            "Girls Love" to "girls-love",
            "Horror" to "horror",
            "Mystery" to "mystery",
            "Romance" to "romance",
            "Sci-Fi" to "sci-fi",
            "Superhero" to "superhero",
            "Supernatural" to "supernatural",
            "Thriller" to "thriller",
        )

        private val DATE_FORMATS = listOf(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US),
        ).onEach { it.timeZone = TimeZone.getTimeZone("UTC") }
    }
}
