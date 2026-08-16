package eu.kanade.tachiyomi.extension.en.mangaball

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone


/*
 * MangaBall (https://mangaball.net) is a manga / manhwa / manhua / comic
 * reader. Most data is served by CSRF-protected POST endpoints, so this
 * source follows the same flow as the site: bootstrap a session + CSRF
 * token from the homepage, then POST with X-CSRF-TOKEN.
 *
 *     Lists     : POST /api/v1/title/search/            (search_type)
 *     Search    : POST /api/v1/title/search-advanced/
 *     Chapters  : POST /api/v1/chapter/chapter-listing-by-title-id/
 *     Detail    : GET  /title-detail/<slug>-<titleId>/
 *     Reader    : GET  /chapter-detail/<chapterId>/
 *
 * Reader pages embed the page image URLs in a server-rendered script:
 *     const chapterImages = JSON.parse(`["https://.../001.png", ...]`)
 */
class MangaBall : HttpSource() {

    override val name = "MangaBall"
    override val baseUrl = "https://mangaball.net"
    override val lang = "en"
    override val supportsLatest = true

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private var csrfToken: String? = null


    // ============================== CSRF / Session ==============================

    /*
     * Laravel binds the CSRF token to the session cookie, so we first hit
     * the homepage with the shared OkHttp client (its cookie jar stores the
     * laravel_session cookie) and read the token from the meta tag.
     */
    @Synchronized
    private fun ensureToken() {

        if (csrfToken != null) {
            return
        }

        val doc = client.newCall(GET(baseUrl, headers))
            .execute()
            .use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("HTTP Error ${resp.code}")
                }
                resp.asJsoup()
            }

        csrfToken = doc
            .selectFirst("meta[name=csrf-token]")
            ?.attr("content")
            ?: throw IOException("Could not extract CSRF token")
    }

    private fun apiPost(
        path: String,
        params: Map<String, String>,
    ): Request {

        ensureToken()

        val form = FormBody.Builder().apply {
            params.forEach { (k, v) ->
                add(k, v)
            }
        }.build()

        val apiHeaders = headersBuilder()
            .set("X-CSRF-TOKEN", csrfToken!!)
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Referer", "$baseUrl/")
            .build()

        return POST("$baseUrl$path", apiHeaders, form)
    }


    // ============================== Search & Browse ==============================

    private val sortOptions = listOf(
        "Latest Updated Chapters" to "updated_chapters_desc",
        "Oldest Updated Chapters" to "updated_chapters_asc",
        "Newest Created" to "created_at_desc",
        "Oldest Created" to "created_at_asc",
        "Title A-Z" to "name_asc",
        "Title Z-A" to "name_desc",
        "Views High to Low" to "views_desc",
        "Views Low to High" to "views_asc",
    )

    private val popularFilters = FilterList(
        SortFilter("Sort", sortOptions, Filter.Sort.Selection(0, false)),
    )

    private val latestFilters = FilterList(
        SortFilter("Sort", sortOptions, Filter.Sort.Selection(0, false)),
    )

    override fun popularMangaRequest(page: Int): Request =
        apiPost(
            "/api/v1/title/search/",
            linkedMapOf(
                "search_type" to "getPopular",
                "search_limit" to PAGE_SIZE.toString(),
                "page" to page.toString(),
            ),
        )

    override fun popularMangaParse(response: Response): MangasPage =
        searchMangaParse(response)

    override fun latestUpdatesRequest(page: Int): Request =
        apiPost(
            "/api/v1/title/search/",
            linkedMapOf(
                "search_type" to "getRecentlyUpdatedChapter",
                "page" to page.toString(),
            ),
        )

    override fun latestUpdatesParse(response: Response): MangasPage =
        searchMangaParse(response)

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Text search matches titles. Empty search browses latest updates."),
        Filter.Separator(),
        SortFilter("Sort", sortOptions),
    )

    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request {

        val sort = filters
            .filterIsInstance<SortFilter>()
            .firstOrNull()
            ?.value
            ?: sortOptions.first().second

        if (query.isNotBlank()) {
            return apiPost(
                "/api/v1/title/search-advanced/",
                linkedMapOf(
                    "search_input" to query.trim(),
                    "filters[sort]" to sort,
                    "filters[page]" to page.toString(),
                    "filters[contentRating]" to "any",
                    "filters[demographic]" to "any",
                    "filters[person]" to "any",
                    "filters[originalLanguages]" to "any",
                    "filters[publicationYear]" to "",
                    "filters[publicationStatus]" to "",
                ),
            )
        }

        return apiPost(
            "/api/v1/title/search/",
            linkedMapOf(
                "search_type" to "getRecentlyUpdatedChapter",
                "page" to page.toString(),
            ),
        )
    }

    override fun searchMangaParse(response: Response): MangasPage {

        val data = json.decodeFromString<SearchResponse>(response.body!!.string())

        val pagination = data.pagination

        return MangasPage(
            mangas = data.data.map { it.toSManga() },
            hasNextPage = pagination != null &&
                pagination.current_page < pagination.last_page,
        )
    }


    // ============================== Manga Details ==============================

    override fun getMangaUrl(manga: SManga): String =
        "$baseUrl${manga.url}"

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET("$baseUrl${manga.url}", headers)

    override fun mangaDetailsParse(response: Response): SManga {

        val doc = response.asJsoup()

        val detail = doc.selectFirst("#comicDetail")
            ?: throw IOException("Could not parse title details")

        return SManga.create().apply {

            url = this@MangaBall.currentUrl(doc, response)

            title = detail.selectFirst("h6")?.text()?.trim()
                ?: ""

            thumbnail_url = doc
                .selectFirst("img.featured-cover")
                ?.attr("abs:src")

            description = buildString {

                val descText = doc
                    .selectFirst(".description-text")
                    ?.let { el ->
                        el.clone()
                            .select(".description-highlights")
                            .remove()
                        el.text().trim()
                    }

                if (!descText.isNullOrBlank()) {
                    append(descText, "\n\n")
                }

                doc
                    .selectFirst(".alternate-name-container")
                    ?.text()
                    ?.takeIf { it.isNotBlank() }
                    ?.also { append("Alternative titles: ", it) }
            }.trim()

            genre = detail
                .select(".badge[onclick*='/detail/tag-']")
                .joinToString(", ") { it.text().trim() }

            status = when (
                detail
                    .selectFirst(".badge-status")
                    ?.text()
                    ?.trim()
                    ?.lowercase(Locale.ROOT)
            ) {
                "completed" -> SManga.COMPLETED
                "ongoing", "releasing" -> SManga.ONGOING
                "hiatus", "on hiatus" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
        }
    }

    /*
     * Keeps the resolved title-detail path (manga.url) as the canonical id.
     */
    private fun currentUrl(
        doc: Document,
        response: Response,
    ): String {
        val canonical = doc.selectFirst("link[rel=canonical]")?.attr("href")
            ?: response.request.url.toString()
        return canonical
            .substringAfter("mangaball.net")
            .substringBefore("?")
            .let { if (it.isBlank()) "/" else it }
    }


    // ============================== Chapters ==============================

    override fun chapterListRequest(manga: SManga): Request =
        apiPost(
            "/api/v1/chapter/chapter-listing-by-title-id/",
            linkedMapOf("title_id" to titleIdFrom(manga.url)),
        )

    override fun chapterListParse(response: Response): List<SChapter> {

        val data = json.decodeFromString<ChaptersResponse>(response.body!!.string())

        return data.ALL_CHAPTERS
            .flatMap { block ->
                // Prefer an English translation, else the first available.
                val translation = block.translations
                    .firstOrNull { it.language.equals("en", true) }
                    ?: block.translations.firstOrNull()
                    ?: return@flatMap emptyList<SChapter>()

                listOf(
                    SChapter.create().apply {
                        url = translation.url.takeIf { it.startsWith("/") }
                            ?: "/chapter-detail/${translation.id}/"
                        name = translation.name?.takeIf { it.isNotBlank() }
                            ?: block.number?.takeIf { it.isNotBlank() }
                            ?: "Chapter ${block.number_float}"
                        date_upload = translation.date
                            .substringBefore(".")
                            .let {
                                runCatching { chapterDateFormat.parse(it)?.time }
                                    .getOrNull()
                                    ?: 0L
                            }
                    },
                )
            }
            .sortedBy { chapterNumberFrom(it.name) }
            .reversed()
    }

    private fun chapterNumberFrom(name: String): Double =
        Regex("\\d+(?:\\.\\d+)?")
            .find(name)
            ?.value
            ?.toDoubleOrNull()
            ?: 0.0


    // ============================== Pages ==============================

    override fun pageListRequest(chapter: SChapter): Request =
        GET("$baseUrl${chapter.url}", headers)

    override fun pageListParse(response: Response): List<Page> {

        val doc = response.asJsoup()

        val urls = chapterImagesFrom(doc)

        return urls.mapIndexed { i, url ->
            Page(i, url = url, imageUrl = url)
        }
    }

    override fun imageUrlParse(response: Response): String =
        response.request.url.toString()


    // ============================== Helpers ==============================

    private fun MangaItemDto.toSManga(): SManga = SManga.create().apply {
        url = this@toSManga.url
        title = this@toSManga.name
        thumbnail_url = this@toSManga.cover
    }

    private fun titleIdFrom(url: String): String =
        url.trimEnd('/')
            .substringAfterLast('/')
            .substringAfterLast('-')

    /*
     * The reader page embeds the page list as a JSON array inside a script:
     *     const chapterImages = JSON.parse(`["https://..."]`);
     * Falls back to the lazy-loaded <img data-src> elements if the script
     * is missing.
     */
    private fun chapterImagesFrom(doc: Document): List<String> {

        val script = doc
            .select("script")
            .mapNotNull { it.data() }
            .firstOrNull { it.contains("chapterImages") }
            ?: return lazyImagesFrom(doc)

        val match = Regex(
            "chapterImages\\s*=\\s*JSON\\.parse\\(`(\\[.*?\\])`\\)",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).find(script)

        match?.groupValues?.getOrNull(1)?.let { raw ->
            runCatching { json.decodeFromString<List<String>>(raw) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
        }

        return lazyImagesFrom(doc)
    }

    private fun lazyImagesFrom(doc: Document): List<String> =
        doc
            .select("#mangaPages .manga-page img")
            .mapNotNull { it.attr("abs:data-src").takeIf { s -> s.isNotBlank() } }
            .ifEmpty {
                doc
                    .select("#mangaPages .manga-page img")
                    .mapNotNull { it.attr("abs:src").takeIf { s -> s.isNotBlank() && !s.startsWith("data:") } }
            }


    // ============================== Filters ==============================

    private class SortFilter(
        name: String,
        private val options: List<Pair<String, String>>,
        selection: Selection = Selection(0, false),
    ) : Filter.Sort(
        name = name,
        values = options.map { it.first }.toTypedArray(),
        state = selection,
    ) {

        val value: String
            get() = options[state?.index ?: 0].second
    }


    // ============================== DTOs ==============================

    @Serializable
    private data class SearchResponse(
        val code: Int = 200,
        val data: List<MangaItemDto> = emptyList(),
        val pagination: PaginationDto? = null,
    )

    @Serializable
    private data class PaginationDto(
        val current_page: Int = 1,
        val last_page: Int = 1,
    )

    @Serializable
    private data class MangaItemDto(
        val _id: String = "",
        val name: String = "",
        val url: String = "",
        val cover: String? = null,
        val isAdult: Boolean = false,
        val status: String? = null,
        val updated_at: String? = null,
        val last_chapter: String? = null,
    )

    @Serializable
    private data class ChaptersResponse(
        val code: Int = 200,
        val ALL_CHAPTERS: List<ChapterBlockDto> = emptyList(),
    )

    @Serializable
    private data class ChapterBlockDto(
        val number: String? = null,
        val number_float: Double = 0.0,
        val translations: List<ChapterTranslationDto> = emptyList(),
    )

    @Serializable
    private data class ChapterTranslationDto(
        val id: String = "",
        val name: String? = null,
        val language: String? = null,
        val volume: String? = null,
        val date: String = "",
        val views: Int = 0,
        val likes: Int = 0,
        val comments: Int = 0,
        val url: String = "",
    )


    companion object {

        private const val PAGE_SIZE = 20

        private val chapterDateFormat = SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss",
            Locale.ROOT,
        ).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}
