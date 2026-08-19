package eu.kanade.tachiyomi.extension.en.kagane

import android.content.Context
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/*
 * Kagane (https://kagane.to) is a Next.js manga reader with a clean JSON
 * API under https://kagane.to/api/v2.
 *
 *     Search    : POST /api/v2/search/series?page=&size=&sort=
 *     Details   : GET  /api/v2/series/<seriesId>
 *     Chapters  : GET  /api/v2/series/<seriesId>   (books embedded)
 *     Pages     : POST /api/v2/books/<bookId>?is_datasaver=  -> { access_token,
 *                 cache_url, manifest.pages[] } — the page images live on a
 *                 separate cache host (cache_url, e.g. https://kstatic.to) at
 *                 /api/v2/books/page[/datasaver]/<bookId>/<pageId>.<ext>?token=
 *                 and are signed with the short-lived access_token returned by
 *                 the books endpoint.
 *
 * Before the books endpoint will answer, the site demands an "integrity"
 * proof: POST /api/integrity (preceded by a plain GET of the homepage) which
 * returns a token that must be sent as the `x-integrity-token` header on the
 * books call. All requests carry a browser User-Agent + Referer so Cloudflare
 * serves them like a normal reader.
 */
class Kagane : HttpSource(), ConfigurableSource {

    override val name = "Kagane"
    override val baseUrl = "https://kagane.to"
    override val lang = "en"
    override val supportsLatest = true

    private val apiUrl = "https://kagane.to/api/v2"

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    // ============================== Settings ==============================

    private var appContext: Context? = null

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        appContext = screen.context.applicationContext
        SwitchPreferenceCompat(screen.context).apply {
            key = DATA_SAVER_PREF
            title = "Data saver"
            summary = "Use compressed pages when available."
            setDefaultValue(false)
        }.let(screen::addPreference)
    }

    private val dataSaver: Boolean
        get() {
            val ctx = appContext ?: return false
            return ctx.getSharedPreferences("${ctx.packageName}_preferences", Context.MODE_PRIVATE)
                .getBoolean(DATA_SAVER_PREF, false)
        }

    override fun headersBuilder(): Headers.Builder =
        super.headersBuilder()
            .set("User-Agent", BROWSER_UA)
            .set("Referer", "$baseUrl/")

    private fun apiHeaders(): Headers = headersBuilder().build()

    // ========================== Search & Browse ===========================

    override fun getFilterList(): FilterList = FilterList(
        SortFilter(),
        ContentRatingFilter(),
        FormatFilter(),
        StatusFilter(),
    )

    override fun popularMangaRequest(page: Int): Request =
        searchMangaRequest(page, "", FilterList(
            SortFilter(Filter.Sort.Selection(1, false)),
            ContentRatingFilter(),
            FormatFilter(),
            StatusFilter(),
        ))

    override fun popularMangaParse(response: Response): MangasPage = searchMangaParse(response)

    override fun latestUpdatesRequest(page: Int): Request =
        searchMangaRequest(page, "", FilterList(
            SortFilter(Filter.Sort.Selection(6, false)),
            ContentRatingFilter(),
            FormatFilter(),
            StatusFilter(),
        ))

    override fun latestUpdatesParse(response: Response): MangasPage = searchMangaParse(response)

    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request {

        var sort = ""
        val ratings = mutableListOf<String>()
        val formats = mutableListOf<String>()
        val statuses = mutableListOf<String>()

        for (f in filters) {
            when (f) {
                is SortFilter -> sort = f.param
                is ContentRatingFilter -> f.state.filter { it.state }.forEach { ratings.add(it.name) }
                is FormatFilter -> f.state.filter { it.state }.forEach { formats.add(it.name) }
                is StatusFilter -> f.state.filter { it.state }.forEach { statuses.add(it.name) }
                else -> {}
            }
        }

        val body = buildJsonObject {
            if (query.isNotBlank()) put("title", query.trim())
            putJsonArray("source_type") {
                listOf("Official", "Unofficial", "Mixed").forEach { add(it) }
            }
            putJsonArray("content_rating") {
                (if (ratings.isEmpty()) CONTENT_RATINGS.toList() else ratings).forEach { add(it) }
            }
            putJsonArray("content_lang") {
                add("en")
            }
            if (formats.isNotEmpty()) {
                putJsonArray("format") { formats.forEach { add(it) } }
            }
            if (statuses.isNotEmpty()) {
                putJsonArray("upload_status") { statuses.forEach { add(it) } }
            }
        }

        val url = "$apiUrl/search/series?page=${page - 1}&size=35" +
            if (sort.isNotEmpty()) "&sort=$sort" else ""

        return Request.Builder()
            .url(url)
            .headers(apiHeaders())
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    override fun searchMangaParse(response: Response): MangasPage {
        ensureOk(response, "search")
        val dto = json.decodeFromString<SearchDto>(response.body!!.string())
        return MangasPage(dto.content.map { it.toSManga(apiUrl) }, hasNextPage = !dto.last)
    }

    // ========================== Manga Details =============================

    override fun getMangaUrl(manga: SManga): String =
        "$baseUrl/series/${manga.url}"

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET("$apiUrl/series/${manga.url}", apiHeaders())

    override fun mangaDetailsParse(response: Response): SManga {
        ensureOk(response, "details")
        val dto = json.decodeFromString<DetailsDto>(response.body!!.string())
        return dto.toSManga(apiUrl)
    }

    // ============================= Chapters ===============================

    override fun chapterListRequest(manga: SManga): Request =
        GET("$apiUrl/series/${manga.url}", apiHeaders())

    override fun chapterListParse(response: Response): List<SChapter> {
        ensureOk(response, "chapters")
        val dto = json.decodeFromString<DetailsDto>(response.body!!.string())
        val seriesId = response.request.url.encodedPath.trimEnd('/').substringAfterLast('/')
        return dto.seriesBooks.map { it.toSChapter(seriesId) }.reversed()
    }

    override fun getChapterUrl(chapter: SChapter): String =
        "$baseUrl${chapter.url}"

    // =============================== Pages ================================

    private var lastBookId: String = ""

    override fun pageListRequest(chapter: SChapter): Request {
        val bookId = chapter.url.substringAfterLast("/").substringBefore("?")
        lastBookId = bookId

        // The site expects a warm page hit before minting an integrity token.
        // All requests go through `client` (Mihon's network client): it carries the
        // WebView cookie jar and Mihon's Cloudflare interceptor, which auto-solves
        // kagane.to's bot-check and retries with a cf_clearance cookie.
        runCatching { client.newCall(GET("$baseUrl/", apiHeaders())).execute().close() }

        val integrity = integrityToken()

        return Request.Builder()
            .url("$apiUrl/books/$bookId?is_datasaver=$dataSaver")
            .headers(apiHeaders().newBuilder().set("x-integrity-token", integrity).build())
            .post("{}".toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private var integrityCache: String? = null
    private var integrityExp: Long = 0L

    private fun integrityToken(): String {
        val now = System.currentTimeMillis()
        integrityCache?.let { if (integrityExp > now) return it }

        val resp = client.newCall(
            Request.Builder()
                .url("$baseUrl/api/integrity")
                .headers(apiHeaders())
                .post("".toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        ).execute()
        resp.use {
            if (!it.isSuccessful) {
                throw IOException(
                    "Kagane integrity proof failed: HTTP ${it.code} ${it.body?.string()?.take(200)}",
                )
            }
            val dto = json.decodeFromString<IntegrityDto>(it.body!!.string())
            integrityCache = dto.token
            integrityExp = dto.exp * 1000
            return dto.token
        }
    }

    override fun pageListParse(response: Response): List<Page> {
        ensureOk(response, "reader")
        val dto = json.decodeFromString<ChallengeDto>(response.body!!.string())
        val cacheUrl = dto.cacheUrl
        val token = dto.accessToken
        val ds = if (dataSaver) "/datasaver" else ""
        return (dto.manifest?.pages ?: emptyList()).map { page ->
            val url = "$cacheUrl/api/v2/books/page$ds/$lastBookId/" +
                "${page.pageUuid}.${page.ext ?: "jxl"}?token=$token"
            Page(page.pageNumber, url = url, imageUrl = url)
        }
    }

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl!!, apiHeaders())

    // ============================= Utilities ==============================

    private fun ensureOk(response: Response, what: String) {
        if (!response.isSuccessful) {
            throw IOException("Kagane $what failed: HTTP ${response.code} ${response.body?.string()?.take(200)}")
        }
    }

    // ============================== Filters ===============================

    private class SortFilter(
        selection: Filter.Sort.Selection = Filter.Sort.Selection(0, false),
    ) : Filter.Sort("Sort By", SORT_OPTIONS.map { it.first }.toTypedArray(), selection) {
        val param: String
            get() {
                val value = SORT_OPTIONS[state?.index ?: 0].second
                if (value.isEmpty()) return ""
                val order = if (state?.ascending == true) "" else ",desc"
                return "$value$order"
            }
    }

    private class CheckBoxOption(name: String, checked: Boolean = false) :
        Filter.CheckBox(name, checked)

    private class ContentRatingFilter : Filter.Group<Filter.CheckBox>(
        "Content Rating",
        CONTENT_RATINGS.map { CheckBoxOption(it, true) },
    )

    private class FormatFilter : Filter.Group<Filter.CheckBox>(
        "Format",
        FORMATS.map { CheckBoxOption(it) },
    )

    private class StatusFilter : Filter.Group<Filter.CheckBox>(
        "Status",
        STATUSES.map { CheckBoxOption(it) },
    )

    companion object {

        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        private const val DATA_SAVER_PREF = "pref_data_saver"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        internal val CONTENT_RATINGS = arrayOf("Safe", "Suggestive", "Erotica", "Pornographic")
        private val FORMATS = listOf("Manga", "Manhwa", "Manhua", "Comic", "Other")
        private val STATUSES = listOf("Ongoing", "Completed", "Hiatus", "Abandoned")

        private val SORT_OPTIONS = listOf(
            "Relevance" to "",
            "Popular (Total Views)" to "total_views",
            "Popular (Average Views)" to "avg_views",
            "Popular (Today)" to "avg_views_today",
            "Popular (Week)" to "avg_views_week",
            "Popular (Month)" to "avg_views_month",
            "Latest" to "updated_at",
            "By Name" to "series_name",
            "Books count" to "books_count",
            "Created at" to "created_at",
        )
    }

    // =============================== DTOs =================================

    @Serializable
    private class SearchDto(
        val content: List<BookDto> = emptyList(),
        val last: Boolean = true,
    )

    @Serializable
    private class BookDto(
        @SerialName("series_id") val id: String,
        val title: String,
        @SerialName("cover_image_id") val coverImage: String? = null,
    ) {
        fun toSManga(apiUrl: String): SManga = SManga.create().apply {
            title = this@BookDto.title.trim()
            url = id
            thumbnail_url = coverImage?.let { "$apiUrl/image/$it" }
        }
    }

    @Serializable
    private class DetailsDto(
        val title: String,
        val description: String? = null,
        @SerialName("publication_status") val publicationStatus: String? = null,
        @SerialName("upload_status") val uploadStatus: String? = null,
        val format: String? = null,
        val genres: List<GenreDto> = emptyList(),
        @SerialName("series_books") val seriesBooks: List<ChapterDto> = emptyList(),
        @SerialName("series_covers") val covers: List<CoverDto> = emptyList(),
    ) {
        @Serializable
        class GenreDto(@SerialName("genre_name") val name: String)

        @Serializable
        class CoverDto(@SerialName("image_id") val imageId: String)

        fun toSManga(apiUrl: String): SManga = SManga.create().apply {
            title = this@DetailsDto.title.trim()
            thumbnail_url = covers.firstOrNull()?.imageId?.let { "$apiUrl/image/$it" }
            description = this@DetailsDto.description?.let(::cleanDescription).orEmpty()
            genre = buildList {
                format?.takeIf { it.isNotBlank() }?.let { add(it) }
                addAll(genres.map { it.name })
            }.joinToString()
            status = statusFrom(uploadStatus ?: publicationStatus)
        }
    }

    @Serializable
    private class ChapterDto(
        @SerialName("book_id") val id: String,
        val title: String,
        @SerialName("chapter_no") val chapterNo: String? = null,
        @SerialName("volume_no") val volumeNo: String? = null,
        @SerialName("sort_no") val sortNo: Float = 0f,
        @SerialName("created_at") val createdAt: String? = null,
        val groups: List<GroupDto> = emptyList(),
    ) {
        @Serializable
        class GroupDto(val title: String)

        fun toSChapter(seriesId: String): SChapter = SChapter.create().apply {
            url = "/series/$seriesId/reader/$id"
            name = title.trim().ifBlank {
                when {
                    !chapterNo.isNullOrBlank() -> "Ch.$chapterNo"
                    !volumeNo.isNullOrBlank() -> "Vol.$volumeNo"
                    else -> "Chapter ${sortNo.toInt()}"
                }
            }
            date_upload = parseDate(createdAt)
            scanlator = groups.joinToString(", ") { it.title }
            chapter_number = sortNo
        }
    }

    @Serializable
    private class ChallengeDto(
        @SerialName("access_token") val accessToken: String,
        @SerialName("cache_url") val cacheUrl: String,
        val manifest: ManifestDto? = null,
    )

    @Serializable
    private class ManifestDto(
        val pages: List<PageDto> = emptyList(),
    )

    @Serializable
    private class PageDto(
        @SerialName("page_no") val pageNumber: Int,
        @SerialName("page_id") val pageUuid: String,
        val ext: String? = null,
    )

    @Serializable
    private class IntegrityDto(
        val token: String,
        val exp: Long,
    )
}

// ========================= Top-level helpers ==========================

private fun cleanDescription(desc: String): String =
    desc
        .replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
        .replace(Regex("\\*\\*|__|~~"), "")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

private fun statusFrom(status: String?): Int = when (status?.uppercase()) {
    "ONGOING" -> SManga.ONGOING
    "COMPLETED" -> SManga.COMPLETED
    "HIATUS" -> SManga.ON_HIATUS
    "ABANDONED", "CANCELLED" -> SManga.CANCELLED
    else -> SManga.UNKNOWN
}

private val dateFormats = listOf(
    "yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'",
    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
    "yyyy-MM-dd'T'HH:mm:ss'Z'",
    "yyyy-MM-dd'T'HH:mm:ss",
).map { SimpleDateFormat(it, Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") } }

private fun parseDate(raw: String?): Long {
    if (raw.isNullOrBlank()) return 0L
    return dateFormats.firstNotNullOfOrNull { fmt ->
        runCatching { fmt.parse(raw)?.time }.getOrNull()
    } ?: 0L
}
