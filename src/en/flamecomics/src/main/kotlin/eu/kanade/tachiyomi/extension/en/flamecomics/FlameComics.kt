package eu.kanade.tachiyomi.extension.en.flamecomics

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URLEncoder

/*
 * Flame Comics (https://flamecomics.xyz) — Next.js (pages router) site.
 * It exposes a JSON data API under /_next/data/<buildId>/ that mirrors its
 * pages:
 *
 *     Browse  : .../browse.json                 -> pageProps.series (whole catalog)
 *     Latest  : .../index.json                  -> pageProps.latestEntries.blocks[0].series
 *     Details : .../series/<id>.json?id=<id>    -> pageProps.series + pageProps.chapters
 *     Chapter : .../series/<id>/<token>.json?...-> pageProps.chapter.images {idx:{name}}
 *
 * Images live on cdn.flamecomics.xyz: uploads/images/series/<id>/<cover> and
 * uploads/images/series/<id>/<token>/<name>, with a numeric cache-busting
 * query (last_edit / edit_time).
 *
 * The buildId changes on every deploy; when a _next/data request 404s we
 * re-read the buildId from the fresh homepage and retry once.
 */
class FlameComics : HttpSource() {

    override val name = "Flame Comics"
    override val baseUrl = "https://flamecomics.xyz"
    override val lang = "en"
    override val supportsLatest = true

    private val cdn = "https://cdn.flamecomics.xyz"

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", BROWSER_UA)
        .set("Referer", "$baseUrl/")

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(::buildIdOutdatedInterceptor)
        .build()

    private var buildId: String? = null

    // =========================== Browse & Search =========================

    override fun popularMangaRequest(page: Int): Request =
        GET("${dataApiUrl("browse.json")}?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage =
        parseBrowse(response) { list -> list.sortedBy { it.intOrNull("popularityRank") ?: Int.MAX_VALUE } }

    override fun latestUpdatesRequest(page: Int): Request =
        GET(dataApiUrl("index.json"), headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val json = parseJson(response) ?: return MangasPage(emptyList(), false)
        val blocks = json.at("pageProps", "latestEntries", "blocks")?.asJsonArray ?: return MangasPage(emptyList(), false)
        val series = blocks.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("series")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return MangasPage(emptyList(), false)
        return MangasPage(series.mapNotNull { if (it.isJsonObject) seriesToManga(it.asJsonObject) else null }, false)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("${dataApiUrl("browse.json")}?page=$page&q=${URLEncoder.encode(query, "utf-8")}", headers)

    override fun searchMangaParse(response: Response): MangasPage = parseBrowse(response) { it }

    private fun parseBrowse(
        response: Response,
        transform: (List<JsonObject>) -> List<JsonObject>,
    ): MangasPage {
        val json = parseJson(response) ?: return MangasPage(emptyList(), false)
        val series = json.at("pageProps", "series")?.asJsonArray ?: return MangasPage(emptyList(), false)
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val query = response.request.url.queryParameter("q").orEmpty()

        var list = series.mapNotNull { if (it.isJsonObject) it.asJsonObject else null }
        if (query.isNotBlank()) {
            list = list.filter { item ->
                item.stringOrNull("title").orEmpty().contains(query, ignoreCase = true) ||
                    item.get("altTitles")?.takeIf { it.isJsonArray }?.asJsonArray
                        ?.any { it.isJsonPrimitive && it.asString.contains(query, ignoreCase = true) } == true
            }
        }
        list = transform(list)

        val from = (page - 1) * PAGE_SIZE
        val slice = list.drop(from).take(PAGE_SIZE)
        return MangasPage(slice.mapNotNull(::seriesToManga), from + PAGE_SIZE < list.size)
    }

    private fun seriesToManga(obj: JsonObject): SManga? {
        val id = obj.intOrNull("series_id") ?: return null
        return SManga.create().apply {
            url = "/series/$id"
            title = obj.stringOrNull("title") ?: ""
            thumbnail_url = coverUrl(id, obj.stringOrNull("cover") ?: "thumbnail.webp", obj.longOrNull("last_edit") ?: 0L)
        }
    }

    private fun coverUrl(id: Int, cover: String, lastEdit: Long): String =
        "$cdn/uploads/images/series/$id/$cover?$lastEdit"

    // =========================== Manga Details ============================

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    override fun mangaDetailsRequest(manga: SManga): Request {
        val id = manga.url.trim('/').substringAfterLast('/')
        return GET("${dataApiUrl("series/$id.json")}?id=$id", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val id = response.request.url.queryParameter("id") ?: return SManga.create()
        val json = parseJson(response) ?: return SManga.create()
        val series = json.at("pageProps", "series")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return SManga.create()

        return SManga.create().apply {
            url = "/series/$id"
            title = series.stringOrNull("title")?.trim().orEmpty()
            thumbnail_url = coverUrl(
                id.toIntOrNull() ?: 0,
                series.stringOrNull("cover") ?: "thumbnail.webp",
                series.longOrNull("last_edit") ?: 0L,
            )
            author = series.stringArrayOrNull("author")?.joinToString()
            artist = series.stringArrayOrNull("artist")?.joinToString()
            val synopsis = series.stringOrNull("description")
                ?.let { Jsoup.parseBodyFragment(it).wholeText() }
                .orEmpty()
            val altNames = series.stringArrayOrNull("altTitles")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            description = buildString {
                append(synopsis)
                if (altNames.isNotEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    append("Alternative Names:\n")
                    altNames.forEach { append("- $it\n") }
                }
            }.trim().takeIf { it.isNotEmpty() }

            val type = series.stringOrNull("type")
            val tags = series.stringArrayOrNull("tags")
            genre = if (tags != null) (listOfNotNull(type) + tags).joinToString() else type

            status = when (series.stringOrNull("status")?.lowercase()) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                "hiatus" -> SManga.ON_HIATUS
                "dropped" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
        }
    }

    // ============================== Chapters ==============================

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val json = parseJson(response) ?: return emptyList()
        val id = response.request.url.queryParameter("id") ?: return emptyList()
        val chapters = json.at("pageProps", "chapters")?.asJsonArray ?: return emptyList()

        return chapters.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val token = obj.stringOrNull("token") ?: return@mapNotNull null
            val number = obj.stringOrNull("chapter")?.toFloatOrNull() ?: return@mapNotNull null
            SChapter.create().apply {
                url = "/series/$id/$token"
                chapter_number = number
                date_upload = (obj.longOrNull("release_date") ?: 0L) * 1000
                name = buildString {
                    append("Chapter ${number.toString().removeSuffix(".0")}")
                    obj.stringOrNull("title")?.takeIf { it.isNotBlank() }?.let { append(" - $it") }
                }
            }
        }
    }

    // =============================== Pages ================================

    override fun pageListRequest(chapter: SChapter): Request {
        val segments = chapter.url.trim('/').split('/')
        val id = segments.getOrNull(1) ?: return GET(getChapterUrl(chapter), headers)
        val token = segments.getOrNull(2) ?: return GET(getChapterUrl(chapter), headers)
        return GET("${dataApiUrl("series/$id/$token.json")}?id=$id&token=$token", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val json = parseJson(response) ?: return emptyList()
        val chapter = json.at("pageProps", "chapter")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return emptyList()
        val seriesId = chapter.intOrNull("series_id") ?: return emptyList()
        val token = chapter.stringOrNull("token") ?: return emptyList()
        val editTime = chapter.longOrNull("edit_time") ?: 0L
        val images = chapter.get("images")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return emptyList()

        val pageUrl = response.request.url.toString()
        return images.entrySet().mapIndexedNotNull { index, (_, value) ->
            val obj = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapIndexedNotNull null
            val name = obj.stringOrNull("name") ?: return@mapIndexedNotNull null

            val builder = "$cdn/uploads/images/series".toHttpUrl().newBuilder()
                .addPathSegment(seriesId.toString())
                .addPathSegment(token)
                .addPathSegment(name)
                .addQueryParameter(editTime.toString(), null)
            val imageUrl = builder.build().toString()

            Page(index, pageUrl, imageUrl)
        }
    }

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl ?: page.url, headers)

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ======================= BuildId management ==========================

    private fun dataApiUrl(path: String): String = "$baseUrl/_next/data/${currentBuildId()}/$path"

    private fun currentBuildId(): String {
        buildId?.let { return it }
        val document = client.newCall(GET(baseUrl, headers)).execute().use { it.asDocument() }
        buildId = fetchBuildId(document)
            ?: throw IOException("Failed to find buildId in __NEXT_DATA__")
        return buildId!!
    }

    private fun fetchBuildId(document: Document): String? =
        document.selectFirst("script#__NEXT_DATA__")?.data()
            ?.let { runCatching { JsonParser.parseString(it).asJsonObject.get("buildId").asString }.getOrNull() }

    private fun buildIdOutdatedInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val url = request.url
        if (
            response.code == 404 &&
            url.host == "flamecomics.xyz" &&
            url.pathSegments.getOrNull(0) == "_next" &&
            url.pathSegments.getOrNull(1) == "data" &&
            url.fragment != DO_NOT_RETRY
        ) {
            val document = response.use { Jsoup.parse(it.body?.string().orEmpty(), it.request.url.toString()) }
            buildId = fetchBuildId(document)
            if (buildId != null) {
                val newUrl = url.newBuilder()
                    .setPathSegment(2, buildId!!)
                    .fragment(DO_NOT_RETRY)
                    .build()
                return chain.proceed(request.newBuilder().url(newUrl).build())
            }
        }
        return response
    }

    // =============================== Helpers ==============================

    private fun Response.asDocument(): Document {
        val body = body?.string() ?: throw IOException("Empty response body")
        return Jsoup.parse(body, request.url.toString())
    }

    private fun parseJson(response: Response): JsonObject? =
        runCatching { JsonParser.parseString(response.body?.string() ?: "").asJsonObject }.getOrNull()

    private fun JsonObject.at(vararg path: String): com.google.gson.JsonElement? {
        var current: com.google.gson.JsonElement = this
        for (key in path) {
            val obj = current.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            current = obj.get(key) ?: return null
        }
        return current
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        if (has(key) && get(key).isJsonPrimitive) get(key).asString else null

    private fun JsonObject.longOrNull(key: String): Long? =
        if (has(key) && get(key).isJsonPrimitive) runCatching { get(key).asLong }.getOrNull() else null

    private fun JsonObject.intOrNull(key: String): Int? =
        if (has(key) && get(key).isJsonPrimitive) runCatching { get(key).asInt }.getOrNull() else null

    private fun JsonObject.stringArrayOrNull(key: String): List<String>? =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }
            ?.takeIf { it.isNotEmpty() }

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        private const val PAGE_SIZE = 20
        private const val DO_NOT_RETRY = "DO_NOT_RETRY"
    }
}
