package eu.kanade.tachiyomi.extension.en.mangahub

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.CacheControl
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random

/*
 * MangaHub (https://mangahub.io) — a React SPA whose content comes from a
 * GraphQL API at api.mghcdn.com. Every page response sets an `mhub_access`
 * cookie; the API refuses requests ("Cannot POST /graphql") unless that cookie
 * value is echoed back in the `x-mhub-access` header. On a missing/expired key
 * we re-fetch a page (bypassing the HTTP cache) to obtain a fresh cookie and
 * retry once. Thumbnails are served from thumb.mghcdn.com; reading images from
 * imgx.mghcdn.com.
 *
 *     API    : POST https://api.mghcdn.com/graphql   body {"query": "..."}
 *              header x-mhub-access: <mhub_access cookie value>
 *     Browse : search(x: m01, mod: POPULAR|LATEST|..., offset: n){rows{...}}
 *     Manga  : manga(x: m01, slug: "..."){title,status,image,author,...,
 *              chapters{number,title,date}}
 *     Pages  : chapter(x: m01, slug: "...", number: n){pages,mangaID,number}
 *              -> pages is a JSON string {"p":"<path>","i":["<img>",...]}
 *              -> image url = https://imgx.mghcdn.com/<path><img>
 */
class MangaHub : HttpSource() {

    override val name = "MangaHub"
    override val baseUrl = "https://mangahub.io"
    override val lang = "en"
    override val supportsLatest = true

    private val baseApiUrl = "https://api.mghcdn.com"
    private val baseCdnUrl = "https://imgx.mghcdn.com"
    private val baseThumbCdnUrl = "https://thumb.mghcdn.com"
    private val apiRegex = Regex("mhub_access=([^;]+)")
    private val spaceRegex = Regex("\\s+")

    override fun headersBuilder(): Headers.Builder =
        super.headersBuilder()
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9")
            .set("Accept-Language", "en-US,en;q=0.5")
            .set("DNT", "1")
            .set("Sec-Fetch-Dest", "document")
            .set("Sec-Fetch-Mode", "navigate")
            .set("Sec-Fetch-Site", "same-origin")
            .set("Upgrade-Insecure-Requests", "1")

    private val apiHeaders: Headers by lazy {
        headersBuilder()
            .set("Accept", "application/json")
            .set("Sec-Fetch-Dest", "empty")
            .set("Sec-Fetch-Mode", "cors")
            .set("Sec-Fetch-Site", "cross-site")
            .removeAll("Upgrade-Insecure-Requests")
            .build()
    }

    private fun accessCookie(): Cookie? = client.cookieJar
        .loadForRequest(baseUrl.toHttpUrl())
        .firstOrNull { it.name == "mhub_access" && it.value.isNotEmpty() }

    private class MangaHubCookieNotFound : IOException("mhub_access cookie not found")

    private var lastRefresh = 0L

    private fun <T> fetchGraphQL(query: String, refreshUrl: String? = null, parser: (JSONObject) -> T): T {
        return try {
            apiRequest(query, parser)
        } catch (e: Exception) {
            if (e !is IOException) throw e

            refreshApiKey(refreshUrl)
            apiRequest(query, parser)
        }
    }

    private fun <T> apiRequest(query: String, parser: (JSONObject) -> T): T {
        val cookie = accessCookie() ?: throw MangaHubCookieNotFound()

        val requestHeaders = apiHeaders.newBuilder()
            .set("x-mhub-access", cookie.value)
            .build()
        val body = JSONObject().put("query", query).toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("$baseApiUrl/graphql")
            .headers(requestHeaders)
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: ${response.body?.string().orEmpty().take(200)}")
            }
            val json = JSONObject(response.body?.string().orEmpty())
            val errors = json.optJSONArray("errors")
            if (errors != null && errors.length() > 0) {
                val messages = (0 until errors.length())
                    .map { errors.getJSONObject(it).optString("message") }
                    .joinToString("\n")
                throw IOException(messages)
            }
            return parser(json.getJSONObject("data"))
        }
    }

    private fun refreshApiKey(refreshUrl: String? = null) {
        if (System.currentTimeMillis() - lastRefresh < 10_000) return

        val url = refreshUrl?.toHttpUrl()
            ?: "$baseUrl/chapter/martial-peak/chapter-${Random.nextInt(1000, 3000)}".toHttpUrl()
        val oldKey = accessCookie()?.value

        val refreshHeaders = headersBuilder()
            .set("Referer", "$baseUrl/manga/${url.pathSegments[1]}")
            .build()

        for (i in 1..2) {
            val cookie = Cookie.parse(url, "mhub_access=; Max-Age=0; Path=/")
            if (cookie != null) {
                client.cookieJar.saveFromResponse(url, listOf(cookie))
            }

            val query = if (i == 2) "?reloadKey=1" else ""
            val response = try {
                client.newCall(
                    Request.Builder()
                        .url("$url$query")
                        .headers(refreshHeaders)
                        .cacheControl(CacheControl.FORCE_NETWORK)
                        .build(),
                ).execute()
            } catch (e: Exception) {
                throw IOException("An error occurred while obtaining a new API key")
            }
            val returnedKey = response.headers["set-cookie"]
                ?.let { apiRegex.find(it)?.groupValues?.get(1) }
            response.close()

            if (returnedKey != oldKey) break
        }

        lastRefresh = System.currentTimeMillis()
    }

    // =========================== Browse & Search =========================

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/?page=$page&order=POPULAR", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        response.close()
        return getMangaList(page, "POPULAR")
    }

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/?page=$page&order=LATEST", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        response.close()
        return getMangaList(page, "LATEST")
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var order = "POPULAR"
        var genres = "all"

        filters.forEach { filter ->
            when (filter) {
                is OrderByFilter -> order = filter.selected
                is GenreList -> genres = filter.state
                    .filter { it.isIncluded() }
                    .joinToString(",") { it.key }
                    .takeIf { it.isNotBlank() } ?: "all"
                else -> {}
            }
        }

        return GET(
            "$baseUrl/search?page=$page&q=${URLEncoder.encode(query, "utf-8")}&genre=$genres&order=$order",
            headers,
        )
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val url = response.request.url
        val page = url.queryParameter("page")?.toIntOrNull() ?: 1
        val order = url.queryParameter("order") ?: "POPULAR"
        val query = url.queryParameter("q").orEmpty()
        val genres = url.queryParameter("genre") ?: "all"
        response.close()
        return getMangaList(page, order, query, genres)
    }

    private fun getMangaList(page: Int, order: String, query: String = "", genres: String = "all"): MangasPage {
        val data = fetchGraphQL(searchQuery(query, genres, order, page)) { json -> json.getJSONObject("search") }
        val rows = data.getJSONArray("rows")
        val mangas = (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            SManga.create().apply {
                url = "/manga/${row.getString("slug")}"
                title = row.getString("title")
                row.optString("image").takeIf { it.isNotBlank() }?.let { thumbnail_url = "$baseThumbCdnUrl/$it" }
            }
        }
        return MangasPage(mangas, rows.length() == 30)
    }

    // =========================== Manga Details ===========================

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val slug = response.request.url.toString().substringAfter("/manga/")
        response.close()
        return fetchGraphQL(mangaQuery(slug), refreshUrl = "$baseUrl/manga/$slug") { json ->
            json.getJSONObject("manga").toSManga(slug)
        }
    }

    private fun JSONObject.toSManga(slug: String): SManga = SManga.create().apply {
        url = "/manga/$slug"
        title = this@toSManga.optString("title").takeIf { it.isNotBlank() } ?: slug
        author = this@toSManga.optString("author").takeIf { it.isNotBlank() }
        artist = this@toSManga.optString("artist").takeIf { it.isNotBlank() }
        genre = this@toSManga.optString("genres").takeIf { it.isNotBlank() }
        this@toSManga.optString("image").takeIf { it.isNotBlank() }?.let { thumbnail_url = "$baseThumbCdnUrl/$it" }
        status = when (this@toSManga.optString("status")) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        description = buildString {
            this@toSManga.optString("description").takeIf { it.isNotBlank() }?.let(::append)
            val altTitles = this@toSManga.optString("alternativeTitle")
                .split(";")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (altTitles.isNotEmpty()) {
                if (isNotBlank()) append("\n\n")
                append("Alternative Names:\n")
                append(altTitles.joinToString("\n") { "- $it" })
            }
        }
    }

    // ============================== Chapters =============================

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/chapter${chapter.url}"

    override fun chapterListRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val slug = response.request.url.toString().substringAfter("/manga/")
        val chaptersUrl = response.request.url.toString()
        response.close()
        val chapters = fetchGraphQL(mangaQuery(slug), refreshUrl = chaptersUrl) { json ->
            json.getJSONObject("manga").getJSONArray("chapters")
        }
        return (0 until chapters.length()).map { i ->
            val chapter = chapters.getJSONObject(i)
            val numberString = chapter.optDouble("number").toString().removeSuffix(".0")
            SChapter.create().apply {
                name = generateChapterName(
                    chapter.optString("title").trim().replace(spaceRegex, " "),
                    numberString,
                )
                url = "/$slug/chapter-$numberString"
                chapter_number = numberString.toFloat()
                date_upload = parseIsoDate(chapter.optString("date"))
            }
        }.asReversed()
    }

    private fun generateChapterName(title: String, number: String): String = when {
        title.contains(number) -> title
        title.isNotBlank() -> "Chapter $number - $title"
        else -> "Chapter $number"
    }

    // =============================== Pages ===============================

    override fun pageListRequest(chapter: SChapter): Request =
        GET(getChapterUrl(chapter), headers)

    override fun pageListParse(response: Response): List<Page> {
        val url = response.request.url
        val chapterUrl = url.toString()
        val slug = url.pathSegments.getOrNull(1).orEmpty()
        val number = url.pathSegments.getOrNull(2)?.substringAfter("chapter-")?.toFloatOrNull()
        response.close()

        val chapterObject = fetchGraphQL(pagesQuery(slug, number ?: 0f), refreshUrl = chapterUrl) { json ->
            json.getJSONObject("chapter")
        }
        val pages = JSONObject(chapterObject.getString("pages"))
        val images = pages.getJSONArray("i")
        val imagePrefix = pages.getString("p")
        val mangaID = chapterObject.optInt("mangaID")

        // Best-effort: mimic the browser's "recently" cookie + chapter-view log
        // to increase the chance of receiving a valid API key on refresh.
        try {
            val now = System.currentTimeMillis()
            val recently = JSONObject()
                .put(now.toString(), JSONObject().put("mangaID", mangaID).put("number", number ?: 0f))
                .toString()
            val recentlyCookie = Cookie.Builder()
                .domain(baseUrl.toHttpUrl().host)
                .name("recently")
                .value(URLEncoder.encode(recently, "utf-8"))
                .expiresAt(now + 60L * 24 * 60 * 60 * 1000)
                .build()
            client.cookieJar.saveFromResponse(baseUrl.toHttpUrl(), listOf(recentlyCookie))
        } catch (_: Exception) {}

        if (slug.isNotEmpty() && number != null) {
            logChapterView(slug, number)
        }

        return (0 until images.length()).map { i ->
            Page(i, chapterUrl, "$baseCdnUrl/$imagePrefix${images.getString(i)}")
        }
    }

    private fun logChapterView(slug: String, chapterNumber: Float) {
        Thread {
            try {
                val ipResponse = client.newCall(GET("https://api.ipify.org?format=json")).execute()
                val ip = JSONObject(ipResponse.body?.string().orEmpty()).optString("ip")
                ipResponse.close()
                if (ip.isNotBlank()) {
                    client.newCall(
                        GET("$baseUrl/action/logHistory2/$slug/$chapterNumber?browserID=$ip", headers),
                    ).execute().close()
                }
            } catch (_: Exception) {}
        }.start()
    }

    // ============================== Images ===============================

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl ?: page.url, headers.newBuilder().set("Referer", page.url).build())

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // =============================== Queries =============================

    private fun searchQuery(query: String, genre: String, order: String, page: Int): String =
        """
        {
            search(x: m01, q: ${JSONObject.quote(query)}, genre: ${JSONObject.quote(genre)}, mod: $order, offset: ${(page - 1) * 30}) {
                rows {
                    title,
                    slug,
                    image
                }
            }
        }
        """.trimIndent()

    private fun mangaQuery(slug: String): String =
        """
        {
            manga(x: m01, slug: ${JSONObject.quote(slug)}) {
                    title,
                    slug,
                    status,
                    image,
                    author,
                    artist,
                    genres,
                    description,
                    alternativeTitle,
                    chapters {
                        number,
                        title,
                        date
                    }
            }
        }
        """.trimIndent()

    private fun pagesQuery(slug: String, number: Float): String =
        """
        {
            chapter(x: m01, slug: ${JSONObject.quote(slug)}, number: $number) {
                    pages,
                    mangaID,
                    number
                }
        }
        """.trimIndent()

    // ========================== Date helpers =============================

    private fun parseIsoDate(date: String?): Long {
        date ?: return 0L
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(date.substring(0, minOf(date.length, 19)))?.time ?: 0L
        } catch (_: ParseException) {
            0L
        }
    }

    // ============================== Filters ==============================

    override fun getFilterList(): FilterList = FilterList(
        GenreList(GENRES.map { Genre(it.first, it.second) }),
        OrderByFilter(),
    )

    private class Genre(name: String, val key: String) : Filter.TriState(name)

    private class GenreList(genres: List<Genre>) : Filter.Group<Genre>("Genres", genres)

    private class OrderByFilter : Filter.Select<String>(
        "Sort By",
        arrayOf("Popular", "Updates", "A-Z", "New", "Completed"),
    ) {
        private val vals = arrayOf(
            "Popular" to "POPULAR",
            "Updates" to "LATEST",
            "A-Z" to "ALPHABET",
            "New" to "NEW",
            "Completed" to "COMPLETED",
        )

        val selected: String
            get() = vals.getOrNull(state)?.second ?: "POPULAR"
    }
}

// Mirrors the genre list on https://mangahub.io/search (a.genre-label links).
private val GENRES = listOf(
    "Action" to "action",
    "Adaptation" to "adaptation",
    "Adult" to "adult",
    "Adventure" to "adventure",
    "Animals" to "animals",
    "Award winning" to "award-winning",
    "Comedy" to "comedy",
    "Crime" to "crime",
    "Delinquents" to "delinquents",
    "Demons" to "demons",
    "Drama" to "drama",
    "Fantasy" to "fantasy",
    "Full color" to "full-color",
    "Ghosts" to "ghosts",
    "Gore" to "gore",
    "Harem" to "harem",
    "Historical" to "historical",
    "Horror" to "horror",
    "Incest" to "incest",
    "Isekai" to "isekai",
    "Long strip" to "long-strip",
    "Magic" to "magic",
    "Manhua" to "manhua",
    "Manhwa" to "manhwa",
    "Martial arts" to "martial-arts",
    "Mature" to "mature",
    "Military" to "military",
    "Monster girls" to "monster-girls",
    "Monsters" to "monsters",
    "Mystery" to "mystery",
    "Pornographic" to "pornographic",
    "Post-apocalyptic" to "post-apocalyptic",
    "Psychological" to "psychological",
    "Reincarnation" to "reincarnation",
    "Romance" to "romance",
    "Safe" to "safe",
    "School life" to "school-life",
    "Sci-fi" to "sci-fi",
    "Seinen" to "seinen",
    "Sexual violence" to "sexual-violence",
    "Shota" to "shota",
    "Shounen" to "shounen",
    "Slice of life" to "slice-of-life",
    "Suggestive" to "suggestive",
    "Superhero" to "superhero",
    "Supernatural" to "supernatural",
    "Survival" to "survival",
    "Thriller" to "thriller",
    "Time travel" to "time-travel",
    "Tragedy" to "tragedy",
    "Web comic" to "web-comic",
    "Webtoons" to "webtoons",
    "Wuxia" to "wuxia",
    "Zombies" to "zombies",
)
