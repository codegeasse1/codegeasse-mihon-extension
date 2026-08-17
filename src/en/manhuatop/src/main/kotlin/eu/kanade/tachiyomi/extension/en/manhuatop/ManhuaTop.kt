package eu.kanade.tachiyomi.extension.en.manhuatop

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

/*
 * ManhuaTop (https://manhuatop.org) — a "Madara" WordPress theme site
 * (wp-manga CPT, child theme "manhua"). Browse & search come from the
 * archive/search HTML pages; a manga's chapter list is loaded through the
 * site's AJAX endpoint (<mangaUrl>/ajax/chapters); reading images live on
 * the s3.manhuatop.org CDN and are plain <img> srcs inside
 * "div.page-break" containers (no encryption, no Cloudflare gate).
 *
 *     Browse : /manhua/page/N/?m_orderby=views   (popular)   -> .comic_post__item
 *              /manhua/page/N/?m_orderby=latest  (latest)
 *     Search : /?s=<q>&post_type=wp-manga        (page N -> /page/N/?s=..)
 *              -> div.c-tabs-item__content
 *     Manga  : /manhua/<slug>/                   -> .summary_image, .post-title,
 *              .author-content, .genres-content, .description-summary; chapters
 *              POST <mangaUrl>/ajax/chapters     -> li.wp-manga-chapter
 *     Chapter: /manhua/<slug>/chapter-<n>/?style=list
 *              -> div.page-break > img           (s3.manhuatop.org)
 *
 * The AJAX endpoints require an XHR header; the page requests carry a browser
 * User-Agent + Referer like keiyoushi's ManhuaTop source.
 */
class ManhuaTop : HttpSource() {

    override val name = "ManhuaTop"
    override val baseUrl = "https://manhuatop.org"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder =
        super.headersBuilder()
            .set("User-Agent", BROWSER_UA)
            .set("Referer", "$baseUrl/")

    private val xhrHeaders: Headers by lazy {
        headersBuilder()
            .set("X-Requested-With", "XMLHttpRequest")
            .build()
    }

    // ============================== Filters ==============================

    override fun getFilterList(): FilterList = FilterList(
        OrderByFilter(),
        StatusFilter(),
        Filter.Separator(),
        GenreList(GENRES.map { Genre(it.first, it.second) }),
    )

    // =========================== Browse & Search =========================

    private fun searchPage(page: Int): String = if (page == 1) "" else "page/$page/"

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/manhua/${searchPage(page)}?m_orderby=views", headers)

    override fun popularMangaParse(response: Response): MangasPage =
        parseArchive(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/manhua/${searchPage(page)}?m_orderby=latest", headers)

    override fun latestUpdatesParse(response: Response): MangasPage =
        parseArchive(response).let { MangasPage(it.mangas.distinctBy { m -> m.url }, it.hasNextPage) }

    private fun parseArchive(response: Response): MangasPage {
        val document = response.asDocument()
        val mangas = document.select(POPULAR_SELECTOR).mapNotNull(::mangaFromArchiveElement)
        val hasNextPage = document.selectFirst("a.nextpostslink") != null
        return MangasPage(mangas, hasNextPage)
    }

    private fun mangaFromArchiveElement(element: Element): SManga? {
        val urlElement = element.selectFirst(".comic_post__title a") ?: return null
        return SManga.create().apply {
            setUrlWithoutDomain(urlElement.attr("abs:href"))
            title = urlElement.ownText()
            element.selectFirst("img")?.let { thumbnail_url = imageFromElement(it) }
        }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/${searchPage(page)}".toHttpUrl().newBuilder().apply {
            addQueryParameter("s", query)
            addQueryParameter("post_type", "wp-manga")
            filters.forEach { filter ->
                when (filter) {
                    is OrderByFilter -> filter.selected.takeIf { it.isNotBlank() }?.let { addQueryParameter("m_orderby", it) }
                    is StatusFilter -> filter.state
                        .filter { it.state }
                        .forEach { addQueryParameter("status[]", it.id) }
                    is GenreList -> {
                        val included = filter.state.filter { it.state == Filter.TriState.STATE_INCLUDE }
                        val excluded = filter.state.filter { it.state == Filter.TriState.STATE_EXCLUDE }
                        if (included.isNotEmpty()) {
                            included.forEach { addQueryParameter("genre[]", it.id) }
                            addQueryParameter("op", "and")
                        } else if (excluded.isNotEmpty()) {
                            excluded.forEach { addQueryParameter("genre[]", it.id) }
                            addQueryParameter("op", "or")
                        }
                    }
                    else -> {}
                }
            }
        }.build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asDocument()
        val mangas = document.select(SEARCH_SELECTOR).mapNotNull(::mangaFromSearchElement)
        val hasNextPage = document.selectFirst("a.nextpostslink") != null
        return MangasPage(mangas, hasNextPage)
    }

    private fun mangaFromSearchElement(element: Element): SManga? {
        val urlElement = element.selectFirst("div.post-title a") ?: return null
        return SManga.create().apply {
            setUrlWithoutDomain(urlElement.attr("abs:href"))
            title = urlElement.ownText()
            element.selectFirst("img")?.let { thumbnail_url = imageFromElement(it) }
        }
    }

    // =========================== Manga Details ===========================

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asDocument()
        val manga = SManga.create()
        with(document) {
            manga.title = selectFirst(TITLE_SELECTOR)?.ownText().orEmpty()
            select(AUTHOR_SELECTOR).eachText().joinToString().takeIf { it.isNotBlank() }?.let { manga.author = it }
            select(ARTIST_SELECTOR).eachText().joinToString().takeIf { it.isNotBlank() }?.let { manga.artist = it }
            selectFirst(DESCRIPTION_SELECTOR)?.let { el ->
                if (el.select("p").text().isNotEmpty()) {
                    manga.description = el.select("p").joinToString("\n\n") { p -> p.text() }
                } else {
                    manga.description = el.text()
                }
            }
            selectFirst(THUMBNAIL_SELECTOR)?.let { manga.thumbnail_url = imageFromElement(it) }
            selectFirst(STATUS_SELECTOR)?.let { el ->
                val text = el.text().filter { it.isLetterOrDigit() || it.isWhitespace() }.trim()
                manga.status = when {
                    COMPLETED_STATUS.any { text.contains(it, ignoreCase = true) } -> SManga.COMPLETED
                    ONGOING_STATUS.any { text.contains(it, ignoreCase = true) } -> SManga.ONGOING
                    HIATUS_STATUS.any { text.contains(it, ignoreCase = true) } -> SManga.ON_HIATUS
                    CANCELED_STATUS.any { text.contains(it, ignoreCase = true) } -> SManga.CANCELLED
                    else -> SManga.UNKNOWN
                }
            }
            val genres = select(GENRE_SELECTOR).map { it.text() }.toMutableList()
            selectFirst(TYPE_SELECTOR)?.ownText()?.let { type ->
                if (type.isNotBlank() && type != "-") genres.add(type)
            }
            manga.genre = genres.distinctBy { it.lowercase(Locale.US) }.joinToString()
            selectFirst(ALT_NAME_SELECTOR)?.ownText()?.let { alt ->
                if (alt.isNotBlank() && "updating" !in alt.lowercase()) {
                    manga.description = if (manga.description.isNullOrBlank()) {
                        "Alt: $alt"
                    } else {
                        "${manga.description}\n\nAlt: $alt"
                    }
                }
            }
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
        val inline = document.select("li.wp-manga-chapter")
        if (inline.isNotEmpty()) return inline.mapNotNull(::chapterFromElement)

        // Chapters are loaded via AJAX. Prefer the newer /ajax/chapters
        // endpoint and fall back to admin-ajax.php for older Madara builds.
        val mangaId = document.selectFirst("div[id^=manga-chapters-holder]")?.attr("data-id")
            ?: return emptyList()
        val mangaUrl = document.location().removeSuffix("/")

        val chapters: List<SChapter> = try {
            val newRequest = Request.Builder()
                .url("$mangaUrl/ajax/chapters")
                .headers(xhrHeaders)
                .post(FormBody.Builder().build())
                .build()
            client.newCall(newRequest).execute().use { resp ->
                if (resp.code == 400 || resp.code == 404) {
                    // Old endpoint fallback
                    val oldBody = FormBody.Builder()
                        .add("action", "manga_get_chapters")
                        .add("manga", mangaId)
                        .build()
                    val oldRequest = Request.Builder()
                        .url("$baseUrl/wp-admin/admin-ajax.php")
                        .headers(xhrHeaders)
                        .post(oldBody)
                        .build()
                    client.newCall(oldRequest).execute().use { oldResp ->
                        oldResp.asDocument().select("li.wp-manga-chapter").mapNotNull(::chapterFromElement)
                    }
                } else {
                    resp.asDocument().select("li.wp-manga-chapter").mapNotNull(::chapterFromElement)
                }
            }
        } catch (e: IOException) {
            emptyList()
        }
        return chapters
    }

    private fun chapterFromElement(element: Element): SChapter? {
        val urlElement = element.selectFirst("a") ?: return null
        return SChapter.create().apply {
            url = urlElement.attr("abs:href").let {
                it.substringBefore("?style=paged") + if (!it.endsWith("?style=list")) "?style=list" else ""
            }
            name = urlElement.text()
            date_upload = element.selectFirst("span.chapter-release-date span[title]")?.attr("title")
                ?.let { parseRelativeDate(it) }
                ?: element.selectFirst("span.chapter-release-date")?.text()
                    ?.let { parseChapterDate(it) }
                    ?: 0L
        }
    }

    // =============================== Pages ===============================

    override fun pageListRequest(chapter: SChapter): Request =
        GET(getChapterUrl(chapter), headers)

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asDocument()
        return document.select(PAGE_BREAK_SELECTOR).mapIndexedNotNull { index, element ->
            val imageUrl = element.selectFirst("img")?.let { imageFromElement(it) }
            if (imageUrl.isNullOrBlank()) null else Page(index, document.location(), imageUrl)
        }
    }

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl ?: page.url, headers.newBuilder().set("Referer", page.url).build())

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ========================== Image helpers ============================

    private fun imageFromElement(element: Element): String? = when {
        element.hasAttr("data-src") -> element.attr("abs:data-src")
        element.hasAttr("data-lazy-src") -> element.attr("abs:data-lazy-src")
        element.hasAttr("srcset") -> element.attr("abs:srcset").getSrcSetImage()
        element.hasAttr("data-cfsrc") -> element.attr("abs:data-cfsrc")
        element.hasAttr("data-manga-src") -> element.attr("abs:data-manga-src")
        else -> element.attr("abs:src")
    }

    private fun String.getSrcSetImage(): String? {
        val images = split(",")
            .map { it.trim().split(WHITESPACE_REGEX, limit = 2) }
            .filter { it.isNotEmpty() && it[0].startsWith("http") }
        val withDescriptor = images
            .filter { it.size == 2 }
            .mapNotNull { candidate ->
                IMAGE_DESCRIPTOR_REGEX.find(candidate[1])?.let { match ->
                    candidate[0] to match.groupValues[1].toFloat()
                }
            }
        if (withDescriptor.isNotEmpty()) {
            return withDescriptor.maxByOrNull { it.second }?.first
        }
        return images.maxOfOrNull { it.first() }
    }

    // =========================== Date helpers ============================

    private fun parseRelativeDate(date: String?): Long {
        val match = RELATIVE_DATE_REGEX.find(date ?: return 0L) ?: return 0L
        val number = match.groupValues[1].toIntOrNull() ?: return 0L
        val unit = match.groupValues[2].lowercase()
        val now = System.currentTimeMillis()
        return when {
            "minute" in unit -> now - number * 60_000L
            "hour" in unit -> now - number * 3_600_000L
            "day" in unit -> now - number * 86_400_000L
            "week" in unit -> now - number * 7L * 86_400_000L
            "month" in unit -> now - number * 30L * 86_400_000L
            "year" in unit -> now - number * 365L * 86_400_000L
            else -> 0L
        }
    }

    private fun parseChapterDate(date: String?): Long {
        date ?: return 0L
        return try {
            SimpleDateFormat("MM/dd/yyyy", Locale.US).parse(date.trim())?.time ?: 0L
        } catch (_: ParseException) {
            0L
        }
    }

    private fun Response.asDocument(): Document {
        val body = body?.string().orEmpty()
        return Jsoup.parse(body, responseUrl())
    }

    private fun Response.responseUrl(): String = request.url.toString()

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        private const val POPULAR_SELECTOR = ".comic_post__item"
        private const val SEARCH_SELECTOR = "div.c-tabs-item__content"
        private const val PAGE_BREAK_SELECTOR = "div.page-break, li.blocks-gallery-item"

        private const val TITLE_SELECTOR = "div.post-title h3, div.post-title h1, #manga-title > h1"
        private const val AUTHOR_SELECTOR = "div.author-content > a"
        private const val ARTIST_SELECTOR = "div.artist-content > a"
        private const val STATUS_SELECTOR = ".post-content_item.manga_status .summary-content"
        private const val DESCRIPTION_SELECTOR = "div.description-summary div.summary__content"
        private const val THUMBNAIL_SELECTOR = "div.summary_image img"
        private const val GENRE_SELECTOR = "div.genres-content a"
        private const val TYPE_SELECTOR = ".post-content_item.manga_type .summary-content"
        private const val ALT_NAME_SELECTOR = ".post-content_item:contains(Alt) .summary-content"

        private val WHITESPACE_REGEX = Regex("\\s+")
        private val IMAGE_DESCRIPTOR_REGEX = Regex("""(\d+(?:\.\d+)?)w|(\d+(?:\.\d+)?)x""")
        private val RELATIVE_DATE_REGEX = Regex("""(\d+)\s*(minute|hour|day|week|month|year)s?\s+ago""", RegexOption.IGNORE_CASE)

        private val COMPLETED_STATUS = listOf("Completed", "Completo", "Concluído", "Finalizado", "已完结")
        private val ONGOING_STATUS = listOf("OnGoing", "Ongoing", "Updating", "连载中", "En curso", "Em lançamento")
        private val HIATUS_STATUS = listOf("On Hold", "Pausado", "En espera", "Đang chờ")
        private val CANCELED_STATUS = listOf("Canceled", "Cancelado", "İptal Edildi")
    }
}

// ============================== Filters ==================================

private class OrderByFilter : Filter.Select<String>(
    "Sort By",
    arrayOf("Relevance", "Latest", "A-Z", "Rating", "Trending", "Most Views", "New"),
) {
    private val vals = arrayOf(
        "Relevance" to "",
        "Latest" to "latest",
        "A-Z" to "alphabet",
        "Rating" to "rating",
        "Trending" to "trending",
        "Most Views" to "views",
        "New" to "new-manga",
    )

    val selected: String
        get() = vals.getOrNull(state ?: 0)?.second ?: ""
}

private class Status(name: String, val id: String) : Filter.CheckBox(name)

private class StatusFilter : Filter.Group<Status>(
    "Status",
    listOf(
        Status("Ongoing", "ongoing"),
        Status("Completed", "completed"),
        Status("Hiatus", "hiatus"),
        Status("Canceled", "canceled"),
    ),
)

private class Genre(name: String, val id: String) : Filter.TriState(name)

private class GenreList(genres: List<Genre>) : Filter.Group<Genre>("Genres", genres)

// ============================== Genres ===================================
// Mirrors the site's /manhua-genre/ taxonomy (name -> slug).

private val GENRES = listOf(
    "Action" to "action",
    "Adult" to "adult",
    "Adventure" to "adventure",
    "Childcare" to "childcare",
    "Comedy" to "comedy",
    "Crime" to "crime",
    "Demons" to "demons",
    "Drama" to "drama",
    "Dungeons" to "dungeons",
    "Ecchi" to "ecchi",
    "Fantasy" to "fantasy",
    "Game" to "game",
    "Harem" to "harem",
    "Historical" to "historical",
    "Horror" to "horror",
    "Isekai" to "isekai",
    "Josei" to "josei",
    "Magic" to "magic",
    "Magical" to "magical",
    "Manhua" to "manhua",
    "Manhwa" to "manhwa",
    "Martial arts" to "martial-arts",
    "Mature" to "mature",
    "Mecha" to "mecha",
    "Music" to "music",
    "Mystery" to "mystery",
    "Psychological" to "psychological",
    "Reincarnation" to "reincarnation",
    "Reunion" to "reunion",
    "Romance" to "romance",
    "School life" to "school-life",
    "Sci-fi" to "sci-fi",
    "Seinen" to "seinen",
    "Shoujo" to "shoujo",
    "Shoujo ai" to "shoujo-ai",
    "Shounen" to "shounen",
    "Shounen ai" to "shounen-ai",
    "Slice of life" to "slice-of-life",
    "Smut" to "smut",
    "Sports" to "sports",
    "Super Power" to "super-power",
    "Supernatural" to "supernatural",
    "Thriller" to "thriller",
    "Time travel" to "time-travel",
    "Tragedy" to "tragedy",
    "Webtoons" to "webtoons",
    "Yaoi" to "yaoi",
    "Yuri" to "yuri",
)
