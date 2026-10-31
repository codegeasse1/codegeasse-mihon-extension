package eu.kanade.tachiyomi.extension.en.manhuatop

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/*
 * ManhuaTop (https://manhuatop.org) — Next.js frontend over a JSON API.
 * (The old WordPress/Madara markup this source parsed before is gone, so
 * every HTML selector from the old build answered empty.)
 *
 *     Browse : /api/discovery?path=/series/            (page N: /series/?page=N)
 *              sort: trending-week (popular) / latest_update (latest)
 *     Search : /api/discovery?path=/search/<q>/        (page N: ?page=N)
 *     Manga  : /manhua/<slug>/                        -> h1#manga-title,
 *              .detail-cover img, .detail-stats, dl.metadata-list,
 *              .rich-content.synopsis, p.alternative-title
 *     Chapters: /api/reader?resource=chapters&path=<mangaPath>?tab=chapters&chapter_page=N
 *     Pages  : /api/reader?resource=content&path=<chapterPath>?content_page=N
 *              -> images[].url (s3.manhuatop.org, plain <img> URLs)
 *     Genres : /api/genres?page=N                     -> terms[]{id,name,count}
 */
class ManhuaTop : HttpSource() {

    override val name = "ManhuaTop"
    override val baseUrl = "https://manhuatop.org"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", BROWSER_UA)
        .set("Referer", "$baseUrl/")

    // =========================== Filters ===============================

    override fun getFilterList(): FilterList {
        val genres = runCatching { fetchGenres() }.getOrDefault(emptyList())
        return FilterList(
            buildList {
                add(SortFilter())
                add(TypeFilter())
                add(StatusFilter())
                if (genres.isNotEmpty()) add(GenreFilter(genres))
            },
        )
    }

    private fun fetchGenres(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var page = 1
        while (true) {
            val url = "$baseUrl/api/genres".toHttpUrl().newBuilder()
                .addQueryParameter("page", page.toString())
                .build()
            val body = client.newCall(GET(url, headers)).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                resp.body?.string().orEmpty()
            }
            val json = JSONObject(body)
            val terms = json.optJSONArray("terms") ?: break
            for (i in 0 until terms.length()) {
                val term = terms.optJSONObject(i) ?: continue
                if (term.optInt("count", 0) <= 0) continue
                val id = term.optString("id").ifBlank { continue }
                val name = term.optString("name").ifBlank { continue }
                out.add(name to id)
            }
            if (!json.optBoolean("hasMore", false)) break
            page++
        }
        return out.sortedBy { it.first.lowercase(Locale.US) }
    }

    // ======================= Browse & Search ===========================

    private fun discoveryUrl(listing: HttpUrl): HttpUrl =
        "$baseUrl/api/discovery".toHttpUrl().newBuilder()
            .addQueryParameter(
                "path",
                listing.encodedPath + (listing.encodedQuery?.let { "?$it" } ?: ""),
            )
            .build()

    private fun archiveListing(page: Int, sort: String): HttpUrl =
        baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("series")
            addPathSegment("")
            if (page > 1) addQueryParameter("page", page.toString())
            if (sort.isNotBlank()) addQueryParameter("sort", sort)
        }.build()

    override fun popularMangaRequest(page: Int): Request =
        GET(discoveryUrl(archiveListing(page, "trending-week")), headers)

    override fun popularMangaParse(response: Response): MangasPage =
        response.asDiscovery()

    override fun latestUpdatesRequest(page: Int): Request =
        GET(discoveryUrl(archiveListing(page, "latest_update")), headers)

    override fun latestUpdatesParse(response: Response): MangasPage =
        response.asDiscovery().let { MangasPage(it.mangas.distinctBy { m -> m.url }, it.hasNextPage) }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val listing = baseUrl.toHttpUrl().newBuilder().apply {
            val keyword = query.trim()
            if (keyword.isNotEmpty()) {
                addPathSegment("search")
                addPathSegment(keyword)
                addPathSegment("")
            } else {
                addPathSegment("series")
                addPathSegment("")
            }
            if (page > 1) addQueryParameter("page", page.toString())
            filters.forEach { filter ->
                when (filter) {
                    is SortFilter -> filter.selected.takeIf { it.isNotBlank() }
                        ?.let { addQueryParameter("sort", it) }
                    is TypeFilter -> filter.selected.takeIf { it.isNotBlank() }
                        ?.let { addQueryParameter("manga_type", it) }
                    is StatusFilter -> filter.selected.takeIf { it.isNotBlank() }
                        ?.let { addQueryParameter("status", it) }
                    is GenreFilter -> {
                        val ids = filter.state.filter { it.state }.map { it.id }.take(10)
                        if (ids.isNotEmpty()) addQueryParameter("genre", ids.joinToString(","))
                    }
                    else -> {}
                }
            }
        }.build()
        return GET(discoveryUrl(listing), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage =
        response.asDiscovery()

    private fun Response.asDiscovery(): MangasPage {
        val body = body?.string().orEmpty()
        val json = JSONObject(body)
        val items = json.optJSONArray("items") ?: JSONArray()
        val mangas = (0 until items.length()).mapNotNull { i ->
            items.optJSONObject(i)?.let(::cardToManga)
        }
        return MangasPage(mangas, json.optBoolean("hasMore", false))
    }

    private fun cardToManga(card: JSONObject): SManga? {
        val path = card.optString("path").ifBlank { return null }
        val title = card.optString("title").ifBlank { return null }
        return SManga.create().apply {
            url = path
            this.title = title
            thumbnail_url = card.optString("thumbnail").ifBlank { null }
            status = when (card.optString("status")) {
                "on-going" -> SManga.ONGOING
                "end" -> SManga.COMPLETED
                "on-hold" -> SManga.ON_HIATUS
                "canceled" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
        }
    }

    // =========================== Manga Details =========================

    override fun getMangaUrl(manga: SManga): String =
        if (manga.url.startsWith("http")) manga.url else baseUrl + manga.url

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asDocument()
        return SManga.create().apply {
            title = document.selectFirst("h1#manga-title")?.text().orEmpty()
            document.selectFirst(".detail-cover img")?.let {
                thumbnail_url = it.attr("abs:src")
            }
            author = document.metadata("Author").joinToString().ifBlank { null }
            artist = document.metadata("Artist").joinToString().ifBlank { null }
            genre = (document.metadata("Type") + document.select(".genre-chips a, .tags-chips a").eachText())
                .distinctBy { it.lowercase(Locale.US) }.joinToString().ifBlank { null }
            status = when (document.selectFirst(".detail-stats .status-badge")?.text()?.lowercase()) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                "on hold" -> SManga.ON_HIATUS
                "canceled" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
            val info = listOfNotNull(
                document.selectFirst(".detail-stats .card-rating")?.text()?.let { "★ $it" },
                document.selectFirst(".detail-stats span[title\$=views]")?.text(),
                document.metadata("Release").firstOrNull(),
            ).joinToString(" · ").ifEmpty { null }
            val synopsis = document.selectFirst(".rich-content.synopsis")?.wholeText()
                ?.lines()?.joinToString("\n") { it.trim() }?.replace(BLANK_LINES, "\n\n")
                ?.trim()?.ifEmpty { null }
            val altTitles = document.selectFirst("p.alternative-title")?.text()
                ?.split(" / ")?.joinToString("\n") { "- $it" }?.let { "**Alternative titles:**\n$it" }
            description = listOfNotNull(info, synopsis, altTitles).joinToString("\n\n").ifEmpty { null }
        }
    }

    // "Updating" is the site's placeholder value
    private fun Document.metadata(name: String): List<String> =
        select(".metadata-list dt:matchesOwn(^$name$) + dd a").eachText()
            .filterNot { it == "Updating" }

    // ============================== Chapters ===========================

    override fun getChapterUrl(chapter: SChapter): String =
        if (chapter.url.startsWith("http")) chapter.url else baseUrl + chapter.url

    override fun chapterListRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        // The manga path keeps the classic "/manhua/<slug>/" shape, so entries
        // saved by the old WordPress build resolve here unchanged.
        val mangaPath = response.request.url.encodedPath.let {
            if (it.endsWith("/")) it else "$it/"
        }
        val out = mutableListOf<SChapter>()
        var page = 1
        var total = -1
        while (true) {
            val series = (baseUrl + mangaPath).toHttpUrl().newBuilder()
                .addQueryParameter("tab", "chapters")
                .addQueryParameter("chapter_page", page.toString())
                .build()
            val url = "$baseUrl/api/reader".toHttpUrl().newBuilder()
                .addQueryParameter("resource", "chapters")
                .addQueryParameter("path", series.encodedPath + "?" + series.encodedQuery!!)
                .build()
            // A later page that refuses is the end of the list, not a reason
            // to throw the chapters already collected away.
            val body = try {
                client.newCall(GET(url, headers)).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        if (page > 1) return@use null
                        throw IOException("HTTP ${resp.code}")
                    }
                    resp.body?.string().orEmpty()
                }
            } catch (e: IOException) {
                if (page > 1) break else throw e
            } ?: break
            val json = JSONObject(body)
            if (total < 0) total = json.optInt("total", -1)
            val items = json.optJSONArray("items") ?: break
            if (items.length() == 0) break
            for (i in 0 until items.length()) {
                items.optJSONObject(i)?.let { chapterToSChapter(it, mangaPath) }?.let(out::add)
            }
            if (!json.optBoolean("hasMore", items.length() >= CHAPTERS_PER_PAGE)) break
            if (total >= 0 && out.size >= total) break
            page++
        }
        return out
    }

    private fun chapterToSChapter(chapter: JSONObject, mangaPath: String): SChapter? {
        val slug = chapter.optString("slug").ifBlank { return null }
        val title = chapter.optString("title")
        val extra = chapter.optString("extra")
        val path = chapter.optString("path")
        return SChapter.create().apply {
            // Full chapter path (no memo field on the classic model); entries
            // saved by the old build carry full URLs and keep working through
            // [getChapterUrl], with the stale query stripped in [pageListParse].
            url = if (path.isNotBlank()) path else mangaPath + slug + "/"
            name = listOfNotNull(title.ifBlank { null }, extra.ifBlank { null })
                .joinToString(" - ").ifBlank { slug }
            date_upload = parseApiDate(chapter.optString("date"))
        }
    }

    // =============================== Pages =============================

    override fun pageListRequest(chapter: SChapter): Request =
        GET(getChapterUrl(chapter), headers)

    override fun pageListParse(response: Response): List<Page> {
        // Old saved chapters carry a "?style=list" query the reader API does
        // not take — the chapter path alone is the resource.
        val chapterUrl = response.request.url.toString().substringBefore("?")
        val out = mutableListOf<Page>()
        var page = 1
        while (true) {
            val content = chapterUrl.toHttpUrl().newBuilder().apply {
                if (page > 1) addQueryParameter("content_page", page.toString())
            }.build()
            val url = "$baseUrl/api/reader".toHttpUrl().newBuilder()
                .addQueryParameter("resource", "content")
                .addQueryParameter(
                    "path",
                    content.encodedPath + (content.encodedQuery?.let { "?$it" } ?: ""),
                )
                .build()
            // A later page that refuses is the end of the chapter, not a
            // reason to throw the pages already collected away.
            val body = try {
                client.newCall(GET(url, headers)).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        if (page > 1) return@use null
                        throw IOException("HTTP ${resp.code}")
                    }
                    resp.body?.string().orEmpty()
                }
            } catch (e: IOException) {
                if (page > 1) break else throw e
            } ?: break
            val images = JSONObject(body).optJSONArray("images") ?: break
            if (images.length() == 0) break
            for (i in 0 until images.length()) {
                val imageUrl = images.optJSONObject(i)?.optString("url").orEmpty()
                if (imageUrl.isNotBlank()) out.add(Page(out.size, chapterUrl, imageUrl))
            }
            if (!JSONObject(body).optBoolean("hasMore", true)) break
            page++
        }
        return out
    }

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl ?: page.url, headers.newBuilder().set("Referer", page.url).build())

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================ Date helpers =========================

    private fun parseApiDate(date: String?): Long {
        if (date.isNullOrBlank()) return 0L
        for (pattern in API_DATE_PATTERNS) {
            try {
                val format = SimpleDateFormat(pattern, Locale.US)
                format.timeZone = TimeZone.getTimeZone("UTC")
                format.parse(date.trim())?.time?.let { return it }
            } catch (_: ParseException) {
            }
        }
        return 0L
    }

    private fun Response.asDocument(): Document {
        val body = body?.string().orEmpty()
        return Jsoup.parse(body, request.url.toString())
    }

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        private const val CHAPTERS_PER_PAGE = 30
        private val BLANK_LINES = Regex("""\n{3,}""")

        private val API_DATE_PATTERNS = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ssX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
        )
    }
}

// ============================== Filters ==================================

private class SortFilter : Filter.Select<String>(
    "Sort by",
    arrayOf(
        "Default",
        "Latest Update",
        "Newest",
        "Title",
        "Highest Rated",
        "Most Viewed",
        "Trending This Week",
        "Trending This Month",
        "Trending This Year",
    ),
) {
    private val vals = arrayOf(
        "",
        "latest_update",
        "newest",
        "title",
        "rating",
        "views",
        "trending-week",
        "trending-month",
        "trending-year",
    )

    val selected: String
        get() = vals.getOrNull(state ?: 0) ?: ""
}

private class TypeFilter : Filter.Select<String>(
    "Type",
    arrayOf("All types", "Manhwa", "Manga", "Manhua", "Webtoons"),
) {
    private val vals = arrayOf("", "Manhwa", "Manga", "Manhua", "Webtoons")

    val selected: String
        get() = vals.getOrNull(state ?: 0) ?: ""
}

private class StatusFilter : Filter.Select<String>(
    "Status",
    arrayOf("All statuses", "Ongoing", "Completed", "On hold", "Canceled"),
) {
    private val vals = arrayOf("", "on-going", "end", "on-hold", "canceled")

    val selected: String
        get() = vals.getOrNull(state ?: 0) ?: ""
}

private class GenreOption(name: String, val id: String) : Filter.CheckBox(name)

private class GenreFilter(genres: List<Pair<String, String>>) :
    Filter.Group<GenreOption>("Genres (any of, up to 10)", genres.map { GenreOption(it.first, it.second) })
