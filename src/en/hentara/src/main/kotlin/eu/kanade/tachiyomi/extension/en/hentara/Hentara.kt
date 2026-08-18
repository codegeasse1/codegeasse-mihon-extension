package eu.kanade.tachiyomi.extension.en.hentara

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
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/*
 * Hentara (https://hentara.com) — a Vite/React SPA, but all data lives in static
 * JSON files on the CDN (the SSR pages render the same data). Adult manhwa (isNsfw).
 *
 *     Catalog : https://cdn.hentara.com/data/index.json
 *               -> {"comics":[{title, slug, thumbnail_url, latest_episode,
 *                   latest_episode_date, view_count, ...}]} — the WHOLE catalog
 *                   (1394 titles); browse/search/sort/paginate it in memory.
 *     Details : https://cdn.hentara.com/data/comics/<slug>.json
 *               -> {"comic":{title, slug, description, thumbnail_url, genres},
 *                   "episodes":[{episode_number, title, created_at, ...}]}
 *     Chapters: same <slug>.json episodes; route = /manhwa/<slug>/chapter-<NN>
 *               (zero-padded to 2 digits).
 *     Pages   : /manhwa/<slug>/chapter-<NN> -> <main class="reader-images">
 *               <img src="https://cdn.hentara.com/<slug>/chapter-<NNN>/NNN.jpg">
 *               (page directory is zero-padded to 3 digits). Reader images are
 *               listed directly in the SSR HTML.
 *
 * The catalog JSON is cached in memory for 5 minutes across requests.
 */
class Hentara : HttpSource() {

    override val name = "Hentara"
    override val baseUrl = "https://hentara.com"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", BROWSER_UA)
        .set("Referer", "$baseUrl/")
        .set("Accept", "application/json, text/html, */*")

    private val dataUrl = "https://cdn.hentara.com/data"

    // =========================== Browse & Search =========================

    override fun popularMangaRequest(page: Int): Request =
        GET("$dataUrl/index.json?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage =
        parseCatalog(response, Sort.POPULAR, "")

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$dataUrl/index.json?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage =
        parseCatalog(response, Sort.LATEST, "")

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET(
            if (query.isBlank()) "$dataUrl/index.json?page=$page"
            else "$dataUrl/index.json?page=$page&q=${java.net.URLEncoder.encode(query, "utf-8")}",
            headers,
        )

    override fun searchMangaParse(response: Response): MangasPage {
        val query = response.request.url.queryParameter("q").orEmpty()
        return parseCatalog(response, Sort.NONE, query)
    }

    override fun getFilterList(): FilterList = FilterList()

    private enum class Sort { LATEST, POPULAR, NONE }

    private fun parseCatalog(response: Response, sort: Sort, query: String): MangasPage {
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val comics = loadCatalog(response)

        val filtered = comics.asSequence()
            .filter { it.stringOrNull("title").isNullOrBlank().not() && it.stringOrNull("slug").isNullOrBlank().not() }
            .filter { series ->
                query.isBlank() ||
                    series.stringOrNull("title").orEmpty().contains(query, ignoreCase = true) ||
                    series.stringOrNull("alternative_names").orEmpty().contains(query, ignoreCase = true)
            }
            .let { seq ->
                when (sort) {
                    Sort.LATEST -> seq.sortedByDescending {
                        it.stringOrNull("latest_episode_date")?.let(::parseDate) ?: 0L
                    }
                    Sort.POPULAR -> seq.sortedByDescending {
                        it.longOrNull("view_count") ?: 0L
                    }
                    Sort.NONE -> seq
                }
            }
            .toList()

        val from = (page - 1) * PAGE_SIZE
        val slice = filtered.drop(from).take(PAGE_SIZE)
        val mangas = slice.mapNotNull(::catalogToManga)
        return MangasPage(mangas, from + PAGE_SIZE < filtered.size)
    }

    private fun catalogToManga(obj: JsonObject): SManga? {
        val slug = obj.stringOrNull("slug") ?: return null
        return SManga.create().apply {
            url = "/manhwa/$slug"
            title = obj.stringOrNull("title") ?: ""
            thumbnail_url = normalizeImageUrl(obj.stringOrNull("thumbnail_url").orEmpty())
        }
    }

    // =========================== Manga Details ===========================

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(dataComicUrl(slugOf(manga)), headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val json = responseBody(response)
        val comic = json?.get("comic")?.takeIf { it.isJsonObject }?.asJsonObject ?: return SManga.create()

        return SManga.create().apply {
            val slug = comic.stringOrNull("slug") ?: ""
            url = if (slug.isBlank()) response.request.url.toString() else "/manhwa/$slug"
            title = comic.stringOrNull("title")?.trim().orEmpty()
            thumbnail_url = normalizeImageUrl(comic.stringOrNull("thumbnail_url").orEmpty())
            comic.stringOrNull("description")?.trim()?.takeIf { it.isNotBlank() }?.let {
                description = Jsoup.parse(it).text().trim()
            }
            genre = comic.arrayOrNull("genres")?.mapNotNull { (it as? JsonObject)?.stringOrNull("name") }
                ?.filter { it.isNotBlank() }
                ?.joinToString()
                ?: ""
        }
    }

    // ============================== Chapters =============================

    override fun chapterListRequest(manga: SManga): Request =
        GET(dataComicUrl(slugOf(manga)), headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val json = responseBody(response) ?: return emptyList()
        val comic = json.get("comic")?.takeIf { it.isJsonObject }?.asJsonObject
        val slug = comic?.stringOrNull("slug") ?: return emptyList()
        val episodes = json.get("episodes")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()

        return episodes.mapNotNull { element ->
            val episode = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val number = episode.intOrNull("episode_number") ?: return@mapNotNull null
            SChapter.create().apply {
                url = "/manhwa/$slug/chapter-${chapterRoute(number)}"
                name = buildString {
                    append("Chapter ", number.toString())
                    episode.stringOrNull("title")?.takeIf { it.isNotBlank() }?.let { append(": ", it) }
                }
                chapter_number = number.toFloat()
                date_upload = episode.stringOrNull("created_at")?.let(::parseDate) ?: 0L
            }
        }
    }

    // =============================== Pages ===============================

    override fun getChapterUrl(chapter: SChapter): String =
        if (chapter.url.startsWith("http")) chapter.url else baseUrl + chapter.url

    override fun pageListRequest(chapter: SChapter): Request =
        GET(getChapterUrl(chapter), headers)

    override fun pageListParse(response: Response): List<Page> {
        val doc = Jsoup.parse(
            response.body?.string() ?: throw IOException("Empty response body"),
            baseUrl,
        )
        val chapterUrl = response.request.url.toString()
        return doc.select(PAGE_SELECTOR).mapIndexedNotNull { index, element ->
            val imageUrl = element.absUrl("src")
            if (imageUrl.isBlank() || !imageUrl.startsWith("http")) null
            else Page(index, chapterUrl, imageUrl)
        }
    }

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl ?: page.url, headers)

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================= Utilities =============================

    private fun loadCatalog(response: Response): List<JsonObject> {
        val cached = catalogCache
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.first < CACHE_TTL_MS) return cached.second

        val json = JsonParser.parseString(
            response.body?.string() ?: throw IOException("Empty response body"),
        )
        val comics = json.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.get("comics")
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?: JsonArray()

        val list = comics.mapNotNull { if (it.isJsonObject) it.asJsonObject else null }
        catalogCache = now to list
        return list
    }

    private fun responseBody(response: Response): JsonObject? =
        runCatching {
            val body = response.body?.string() ?: return null
            JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject
        }.getOrNull()

    private fun dataComicUrl(slug: String): String = "$dataUrl/comics/$slug.json"

    private fun slugOf(manga: SManga): String =
        manga.url.substringBefore("?").substringAfterLast("/")

    private fun chapterRoute(number: Int): String =
        if (number < 10) "0$number" else number.toString()

    /** The reader normalizes every CDN host to cdn.hentara.com. */
    private fun normalizeImageUrl(url: String): String {
        if (url.isBlank()) return url
        for (host in ALTERNATE_CDN_HOSTS) {
            if (url.startsWith("https://$host/")) return url.replace("https://$host/", "$cdnBase/")
        }
        return url
    }

    private fun parseDate(value: String): Long {
        for (format in DATE_FORMATS) {
            runCatching { return format.parse(value)?.time ?: 0L }
        }
        return 0L
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        if (has(key) && get(key).isJsonPrimitive) get(key).asString else null

    private fun JsonObject.longOrNull(key: String): Long? =
        if (has(key) && get(key).isJsonPrimitive) runCatching { get(key).asLong }.getOrNull() else null

    private fun JsonObject.intOrNull(key: String): Int? =
        if (has(key) && get(key).isJsonPrimitive) runCatching { get(key).asInt }.getOrNull() else null

    private fun JsonObject.arrayOrNull(key: String): JsonArray? =
        if (has(key) && get(key).isJsonArray) get(key).asJsonArray else null

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        private const val PAGE_SIZE = 24
        private const val CACHE_TTL_MS = 5 * 60 * 1000L
        private const val cdnBase = "https://cdn.hentara.com"
        private const val PAGE_SELECTOR = "main.reader-images img[src]"

        private val ALTERNATE_CDN_HOSTS = listOf(
            "cdn.manhwaepisodes.com",
            "cdn.manhwakool.com",
        )

        private val DATE_FORMATS = listOf(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US),
        ).onEach { it.timeZone = TimeZone.getTimeZone("UTC") }

        @Volatile
        private var catalogCache: Pair<Long, List<JsonObject>>? = null
    }
}
