package eu.kanade.tachiyomi.extension.en.yurivan

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone


/*
 * Yurivan (https://www.yurivan.com) is a yuri manga / manhwa / manhua /
 * GL webtoon and anime-video site. Its entire data layer is a public
 * Supabase PostgREST API, so this source talks JSON directly to Supabase
 * and bypasses both the 18+ age gate and the Cloudflare-protected site.
 *
 *     API base : https://wmnzjmrysnzjthldgffh.supabase.co/rest/v1
 *     Image CDN: https://img.yurivan.com
 *
 * Endpoints used:
 *     stories        - list & details (chapters embedded in details)
 *     story_pages    - gallery chapter images (object_key -> img CDN)
 *     story_chapters - prose body (text stories) & video thumbnails
 *
 * Story URL  : /story/<uuid>
 * Chapter URL: /story/<uuid>/chapter/<index>?type=<gallery|text|video>
 */
class Yurivan : HttpSource() {

    override val name = "Yurivan"
    override val baseUrl = "https://www.yurivan.com"
    override val lang = "en"
    override val supportsLatest = true

    private val apiUrl = "https://wmnzjmrysnzjthldgffh.supabase.co/rest/v1"
    private val imageCdn = "https://img.yurivan.com"
    private val apiKey = "sb_publishable_4yI6VdjidXNGtE29hI5k3A_w4TxKHix"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // Supabase tables live in the "yurivan" schema, so every API call
    // needs the anon key plus Accept-Profile. Image CDN needs nothing.
    private val apiHeaders: Headers = headersBuilder()
        .set("apikey", apiKey)
        .set("Authorization", "Bearer $apiKey")
        .set("Accept-Profile", "yurivan")
        .set("Accept", "application/json")
        .build()

    private fun supabaseGet(
        table: String,
        params: Map<String, String>,
    ): Request {

        val url = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegment(table)
            params.forEach { (k, v) ->
                addQueryParameter(k, v)
            }
        }.build()

        return GET(url, apiHeaders)
    }


    // ============================== Search & Browse ==============================

    private val sortOptions = listOf(
        "Popularity" to "view_count.desc",
        "Newest" to "created_at.desc",
        "Recently updated" to "updated_at.desc",
        "Top rated" to "rating_avg.desc.nullslast",
    )

    private val typeOptions = listOf(
        "All" to "",
        "Gallery" to "gallery",
        "Text" to "text",
        "Video" to "video",
    )

    private val tagOptions = listOf(
        "Explicit" to "explicit",
        "Safe" to "safe",
        "Manga" to "manga",
        "Manhwa" to "manhwa",
        "Manhua" to "manhua",
        "Pornhwa" to "pornhwa",
        "Romance" to "romance",
        "Comedy" to "comedy",
        "Dramatic" to "dramatic",
        "Emotional" to "emotional",
        "School" to "school",
        "Fantasy" to "fantasy",
        "Supernatural" to "supernatural",
        "Wholesome" to "wholesome",
        "Lesbian anime" to "lesbian-anime",
        "Catfight" to "catfight",
        "Sexfight" to "sexfight",
        "Video" to "video",
        "Oneshot" to "oneshot",
        "Series" to "series",
        "Slow burn" to "slow-burn",
        "Enemies to lovers" to "enemies-to-lovers",
        "Forbidden" to "forbidden",
        "Age gap" to "age-gap",
        "Established relationship" to "established-relationship",
    )

    private val popularFilters = FilterList(
        SortFilter("Sort", sortOptions, Filter.Sort.Selection(0, false)),
    )

    private val latestFilters = FilterList(
        SortFilter("Sort", sortOptions, Filter.Sort.Selection(2, false)),
    )

    override fun popularMangaRequest(page: Int): Request =
        searchMangaRequest(page, "", popularFilters)

    override fun popularMangaParse(response: Response): MangasPage =
        searchMangaParse(response)

    override fun latestUpdatesRequest(page: Int): Request =
        searchMangaRequest(page, "", latestFilters)

    override fun latestUpdatesParse(response: Response): MangasPage =
        searchMangaParse(response)

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Text search matches titles. Filters apply in every mode."),
        Filter.Separator(),
        SortFilter("Sort", sortOptions),
        TypeFilter(typeOptions),
        TagFilter("Tags", tagOptions),
    )

    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request {

        val params = linkedMapOf<String, String>()

        params["select"] = "id,title,cover_url,type,chapter_count"
        params["status"] = "eq.ACTIVE"
        params["media_status"] = "eq.MEDIA_VERIFIED"

        val sort = filters
            .filterIsInstance<SortFilter>()
            .firstOrNull()

        params["order"] = sort?.sort ?: sortOptions.first().second

        params["limit"] = FETCH_SIZE.toString()
        params["offset"] = ((page - 1) * PAGE_SIZE).toString()

        if (query.isNotEmpty()) {
            params["title"] = "ilike.*${query.trim()}*"
        }

        filters
            .filterIsInstance<TypeFilter>()
            .firstOrNull()
            ?.state
            ?.takeIf { it > 0 }
            ?.also { index ->
                params["type"] = "eq.${typeOptions[index].second}"
            }

        filters
            .filterIsInstance<TagFilter>()
            .firstOrNull()
            ?.also { group ->
                group.included
                    .takeIf { it.isNotEmpty() }
                    ?.also { include ->
                        params["tags"] = "cs.{${include.joinToString(",")}}"
                    }
                group.excluded
                    .takeIf { it.isNotEmpty() }
                    ?.also { exclude ->
                        params["tags"] = "not.cs.{${exclude.joinToString(",")}}"
                    }
            }

        return supabaseGet("stories", params)
    }

    override fun searchMangaParse(response: Response): MangasPage {

        val list = json
            .decodeFromString<List<StoryListDto>>(response.body!!.string())

        return MangasPage(
            mangas = list.take(PAGE_SIZE).map { it.toSManga() },
            hasNextPage = list.size > PAGE_SIZE,
        )
    }


    // ============================== Manga Details ==============================

    override fun getMangaUrl(manga: SManga): String =
        "$baseUrl/story/${manga.url}"

    override fun mangaDetailsRequest(manga: SManga): Request =
        supabaseGet(
            "stories",
            linkedMapOf(
                "id" to "eq.${manga.url}",
                "status" to "eq.ACTIVE",
                "media_status" to "eq.MEDIA_VERIFIED",
                "select" to DETAIL_SELECT,
                "chapters.order" to "chapter_index.asc",
                "limit" to "1",
            ),
        )

    override fun mangaDetailsParse(response: Response): SManga {

        val data = json
            .decodeFromString<List<StoryDto>>(response.body!!.string())
            .firstOrNull()
            ?: throw IOException("Story not found")

        return data.toSManga().apply {

            author = data.users?.handle?.takeIf { it.isNotBlank() }
                ?: data.originalCreatorString()
                ?: "Unknown"

            artist = author

            description = data.buildDescription()

            genre = (data.tags + data.pairings)
                .distinct()
                .joinToString(", ")
        }
    }


    // ============================== Chapters ==============================

    override fun chapterListRequest(manga: SManga): Request =
        mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {

        val data = json
            .decodeFromString<List<StoryDto>>(response.body!!.string())
            .firstOrNull()
            ?: return emptyList()

        return data.chapters
            .sortedBy { it.chapter_index }
            .map { chapter ->
                SChapter.create().apply {

                    url = chapterUrl(
                        data.id,
                        chapter.chapter_index,
                        data.type,
                    )

                    name = chapter.title?.takeIf { it.isNotBlank() }
                        ?: "Chapter ${chapter.chapter_index + 1}"

                    date_upload = chapter
                        .created_at
                        .chapterEpochMillis()
                }
            }
    }


    // ============================== Pages ==============================

    override fun pageListRequest(chapter: SChapter): Request {

        val (storyId, index, type) = parseChapterUrl(chapter.url)

        return when (type) {
            "text" -> supabaseGet(
                "story_chapters",
                linkedMapOf(
                    "story_id" to "eq.$storyId",
                    "chapter_index" to "eq.$index",
                    "select" to "text_body,inline_images",
                    "type" to "text",
                    "limit" to "1",
                ),
            )

            "video" -> supabaseGet(
                "story_chapters",
                linkedMapOf(
                    "story_id" to "eq.$storyId",
                    "chapter_index" to "eq.$index",
                    "select" to "bunny_thumbnail_url,cover_object_key,duration_seconds",
                    "type" to "video",
                    "limit" to "1",
                ),
            )

            else -> supabaseGet(
                "story_pages",
                linkedMapOf(
                    "story_id" to "eq.$storyId",
                    "chapter_index" to "eq.$index",
                    "select" to "global_page_index,chapter_index,local_page_index,object_key,width,height,bytes",
                    "order" to "local_page_index.asc",
                    "type" to "gallery",
                    "limit" to "5000",
                ),
            )
        }
    }

    override fun pageListParse(response: Response): List<Page> {

        val type = response.request.url.queryParameter("type") ?: "gallery"
        val body = response.body!!.string()

        return when (type) {
            "text" -> {
                val data = json
                    .decodeFromString<List<TextChapterDto>>(body)
                    .firstOrNull()

                val prose = data?.text_body
                    ?.takeIf { it.isNotBlank() }
                    ?: return emptyList()

                renderProse(prose)
            }

            "video" -> {
                val data = json
                    .decodeFromString<List<VideoChapterDto>>(body)
                    .firstOrNull()

                val thumb = data?.bunny_thumbnail_url
                    ?: data?.cover_object_key?.let { absoluteImageUrl(it) }
                    ?: return emptyList()

                listOf(Page(0, url = thumb, imageUrl = thumb))
            }

            else -> {
                json
                    .decodeFromString<List<StoryPageDto>>(body)
                    .mapIndexed { i, page ->
                        Page(
                            index = i,
                            url = page.object_key,
                            imageUrl = absoluteImageUrl(page.object_key),
                        )
                    }
            }
        }
    }

    override fun imageUrlParse(response: Response): String =
        response.request.url.toString()


    // ============================== Helpers ==============================

    private fun StoryListDto.toSManga() = SManga.create().apply {
        url = id
        title = title
        thumbnail_url = cover_url
    }

    private fun StoryDto.originalCreatorString(): String? {

        val el = original_creator ?: return null

        if (el is JsonPrimitive) {
            return el.contentOrNull
        }

        if (el is JsonObject) {
            val name = el["name"]
            if (name is JsonPrimitive) {
                return name.contentOrNull
            }
        }

        return null
    }

    private fun StoryDto.buildDescription(): String = buildString {

        val enSynopsis = descriptions
            ?.get("en")
            ?.jsonObject
            ?.get("text")
            ?.jsonPrimitive
            ?.contentOrNull

        val synopsis = enSynopsis?.trim()
            ?: caption?.trim()

        if (!synopsis.isNullOrBlank()) {
            append(synopsis, "\n\n")
        }

        originalCreatorString()?.also {
            append("Creator: ", it, "\n\n")
        }

        append(
            "Type: ", type,
            " • Chapters: ", chapter_count,
            " • Views: ", view_count,
        )

        if (rating_avg != null) {
            append(
                " • Rating: ",
                "%.2f".format(rating_avg),
                " (",
                rating_count,
                ")",
            )
        }

        append("\n\n")

        if (pairings.isNotEmpty()) {
            append("Pairings: ", pairings.joinToString(", "), "\n\n")
        }

        if (tags.isNotEmpty()) {
            append("Tags: ", tags.joinToString(", "))
        }
    }

    private fun String.chapterEpochMillis(): Long =
        substringBefore(".")
            .let {
                runCatching { chapterDateFormat.parse(it)?.time }
                    .getOrNull()
                    ?: 0L
            }

    private fun chapterUrl(
        storyId: String,
        index: Int,
        type: String,
    ): String = "/story/$storyId/chapter/$index?type=$type"

    private fun parseChapterUrl(url: String): Triple<String, Int, String> {

        val httpUrl = "$baseUrl$url".toHttpUrl()
        val segments = httpUrl.pathSegments
        // path is /story/<uuid>/chapter/<index>
        val storyId = segments.getOrNull(1) ?: ""
        val index = segments.getOrNull(3)?.toIntOrNull() ?: 0
        val type = httpUrl.queryParameter("type") ?: "gallery"

        return Triple(storyId, index, type)
    }

    private fun absoluteImageUrl(objectKey: String): String =
        if (objectKey.startsWith("http")) {
            objectKey
        } else {
            "$imageCdn/$objectKey"
        }


    // ============================== Text & Video Rendering ==============================

    /*
     * Text-type stories have no page images, so their prose is rendered
     * as vector SVG pages (Mihon's image loader decodes SVG). Video-type
     * chapters show the video thumbnail as a single page.
     */
    private fun renderProse(body: String): List<Page> {

        val paragraphs = body
            .replace("\r", "")
            .split(Regex("\\n{2,}"))
            .flatMap { it.split('\n') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val pages = mutableListOf<Page>()
        val buffer = mutableListOf<String>()
        var charCount = 0

        fun flush() {
            if (buffer.isNotEmpty()) {
                pages += Page(
                    index = pages.size,
                    url = "text-${pages.size}",
                    imageUrl = svgImage(buffer.toList()),
                )
                buffer.clear()
                charCount = 0
            }
        }

        for (paragraph in paragraphs) {
            for (line in wrapParagraph(paragraph)) {
                if (charCount + line.length > MAX_PROSE_CHARS) {
                    flush()
                }
                buffer += line
                charCount += line.length
            }
        }

        flush()

        return pages
    }

    private fun wrapParagraph(paragraph: String): List<String> {

        val lines = mutableListOf<String>()
        var current = ""

        for (token in paragraph.split(' ')) {
            if (current.isEmpty()) {
                current = token
            } else if (current.length + 1 + token.length <= PROSE_LINE_WIDTH) {
                current = "$current $token"
            } else {
                lines += current

                // Handles space-less CJK runs too.
                var rest = token
                while (rest.length > PROSE_LINE_WIDTH) {
                    lines += rest.take(PROSE_LINE_WIDTH)
                    rest = rest.drop(PROSE_LINE_WIDTH)
                }
                current = rest
            }
        }

        if (current.isNotEmpty()) {
            lines += current
        }

        return lines
    }

    private fun svgImage(lines: List<String>): String {

        val width = 900
        val fontSize = 30
        val lineHeight = 44
        val padding = 48
        val height = padding * 2 + lines.size * lineHeight

        val sb = StringBuilder()
        sb.append(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" " +
                "width=\"$width\" height=\"$height\" " +
                "viewBox=\"0 0 $width $height\">",
        )
        sb.append("<rect width=\"$width\" height=\"$height\" fill=\"#ffffff\"/>")
        sb.append(
            "<text x=\"$padding\" y=\"${padding + fontSize}\" " +
                "font-family=\"sans-serif\" font-size=\"$fontSize\" fill=\"#111111\">",
        )
        lines.forEachIndexed { i, line ->
            val y = padding + fontSize + i * lineHeight
            sb.append("<tspan x=\"$padding\" y=\"$y\">")
            sb.append(xmlEscape(line.ifEmpty { " " }))
            sb.append("</tspan>")
        }
        sb.append("</text></svg>")

        return "data:image/svg+xml;utf8," +
            URLEncoder.encode(sb.toString(), "UTF-8")
    }

    private fun xmlEscape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")


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

        val sort: String
            get() = options[state?.index ?: 0].second
    }

    private class TypeFilter(
        options: List<Pair<String, String>>,
    ) : Filter.Select(
        "Type",
        options.map { it.first }.toTypedArray(),
    )

    private class TriStateFilter(
        name: String,
        val value: String,
    ) : Filter.TriState(name)

    private abstract class TriStateGroupFilter(
        name: String,
        options: List<Pair<String, String>>,
    ) : Filter.Group<TriStateFilter>(
        name,
        options.map {
            TriStateFilter(it.first, it.second)
        },
    ) {

        val included: List<String>
            get() = state
                .filter { it.isIncluded() }
                .map { it.value }

        val excluded: List<String>
            get() = state
                .filter { it.isExcluded() }
                .map { it.value }
    }

    private class TagFilter(
        name: String,
        options: List<Pair<String, String>>,
    ) : TriStateGroupFilter(name, options)


    // ============================== DTOs ==============================

    @Serializable
    private data class StoryListDto(
        val id: String = "",
        val title: String = "",
        val cover_url: String? = null,
        val type: String = "gallery",
        val chapter_count: Int = 0,
    )

    @Serializable
    private data class StoryDto(
        val id: String = "",
        val title: String = "",
        val caption: String? = null,
        val type: String = "gallery",
        val cover_url: String? = null,
        val tags: List<String> = emptyList(),
        val pairings: List<String> = emptyList(),
        val chapter_count: Int = 0,
        val total_page_count: Int? = null,
        val view_count: Long = 0,
        val rating_avg: Double? = null,
        val rating_count: Int = 0,
        val like_count: Int = 0,
        val published_at: String? = null,
        val created_at: String = "",
        val updated_at: String = "",
        val original_creator: JsonElement? = null,
        val source_type: String? = null,
        val descriptions: JsonObject? = null,
        val alt_titles: JsonElement? = null,
        val users: UserDto? = null,
        val chapters: List<ChapterDto> = emptyList(),
    )

    @Serializable
    private data class UserDto(
        val handle: String? = null,
    )

    @Serializable
    private data class ChapterDto(
        val id: String? = null,
        val chapter_index: Int = 0,
        val title: String? = null,
        val view_count: Int = 0,
        val created_at: String = "",
        val characters: List<String> = emptyList(),
        val has_explicit_scene: Boolean? = null,
        val cover_object_key: String? = null,
        val start_page: Int = 0,
        val end_page: Int = 0,
        val text_char_count: Int = 0,
        val bunny_video_guid: String? = null,
        val bunny_thumbnail_url: String? = null,
        val duration_seconds: Double? = null,
        val width: Int? = null,
        val height: Int? = null,
        val has_mp4_fallback: Boolean? = null,
        val mp4_fallback_max_resolution: String? = null,
    )

    @Serializable
    private data class StoryPageDto(
        val global_page_index: Int = 0,
        val chapter_index: Int = 0,
        val local_page_index: Int = 0,
        val object_key: String = "",
        val width: Int? = null,
        val height: Int? = null,
        val bytes: Int? = null,
    )

    @Serializable
    private data class TextChapterDto(
        val text_body: String? = null,
        val inline_images: JsonElement? = null,
    )

    @Serializable
    private data class VideoChapterDto(
        val bunny_thumbnail_url: String? = null,
        val cover_object_key: String? = null,
        val duration_seconds: Double? = null,
    )


    companion object {

        private const val PAGE_SIZE = 20
        private const val FETCH_SIZE = 21

        private const val PROSE_LINE_WIDTH = 40
        private const val MAX_PROSE_CHARS = 3000

        private const val DETAIL_SELECT =
            "id,title,caption,type,status,cover_url,tags,pairings," +
                "chapter_count,total_page_count,view_count,rating_avg," +
                "rating_count,like_count,published_at,created_at,updated_at," +
                "original_creator,source_type,descriptions,alt_titles," +
                "users!stories_user_id_fkey(handle)," +
                "chapters:story_chapters(id,chapter_index,title,view_count," +
                "created_at,characters,has_explicit_scene,cover_object_key," +
                "start_page,end_page,text_char_count,bunny_video_guid," +
                "bunny_thumbnail_url,duration_seconds,width,height," +
                "has_mp4_fallback,mp4_fallback_max_resolution)"
    }
}
