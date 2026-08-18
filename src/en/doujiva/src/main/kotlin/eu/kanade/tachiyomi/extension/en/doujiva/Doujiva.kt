package eu.kanade.tachiyomi.extension.en.doujiva

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
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.net.InetAddress
import java.net.URLEncoder
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/*
 * Doujiva (https://doujiva.com) — a Next.js (App Router) reader. The manhwa
 * section ("/manhwa?sort=latest") is served from a public JSON API:
 *
 *     Catalog : GET /api/v1/manhwa?sort=<latest|popular>&page=N
 *               -> {"data":[{title, slug, coverUrl, mediaType:"MANHWA", ...}],
 *                   "meta":{page, limit, total, totalPages}} — 40 manhwa total,
 *                   sorted by the site's own latest/popular ordering.
 *     Search  : GET /api/v1/search?q=<query>&page=N  (all media types) — results
 *               are filtered to MANHWA in memory over the small catalog.
 *     Details : GET /api/v1/manga/<slug>
 *               -> {"data":{...manga fields..., "chapters":[{id, number, title,
 *                   pageCount, createdAt, ...}]}}
 *     Pages   : GET /api/v1/manga/<slug>/chapters/<chapterId>
 *               -> {"data":[{number, imageUrl, ...}]}  — one chapter's pages.
 *
 * Reader images live on cdn.doujiva.com (direct); a few legacy hosts
 * (kontol.online / manhwa18.net / cdn.pornwa*) are served through the site's
 * image proxy, which we replicate for those hosts.
 */
class Doujiva : HttpSource() {

    override val name = "Doujiva"
    override val baseUrl = "https://doujiva.com"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", BROWSER_UA)
        .set("Referer", "$baseUrl/")
        .set("Accept", "application/json, text/html, */*")

    private val apiUrl = "$baseUrl/api/v1"

    /**
     * doujiva.com is Cloudflare-hosted; some resolvers (ISP/ad-block DNS) refuse to
     * resolve it. Try the system DNS first, and fall back to the site's current
     * Cloudflare Anycast addresses if it returns "no address associated with hostname".
     */
    override val client: OkHttpClient = network.client.newBuilder()
        .dns(IpFallbackDns())
        .build()

    // =========================== Browse & Search =========================

    override fun popularMangaRequest(page: Int): Request =
        GET("$apiUrl/manhwa?sort=popular&page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseCatalog(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$apiUrl/manhwa?sort=latest&page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseCatalog(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET(
            "$apiUrl/manhwa?sort=latest&page=$page&q=${URLEncoder.encode(query, "utf-8")}",
            headers,
        )

    override fun searchMangaParse(response: Response): MangasPage {
        val query = response.request.url.queryParameter("q")?.trim().orEmpty()
        if (query.isBlank()) return MangasPage(emptyList(), false)
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1

        // The whole manhwa catalog is tiny (~40 titles): load it, filter in memory.
        val filtered = loadAllManhwa(response).asSequence()
            .filter { it.stringOrNull("title").orEmpty().contains(query, ignoreCase = true) }
            .toList()

        val from = (page - 1) * PAGE_SIZE
        val slice = filtered.drop(from).take(PAGE_SIZE)
        val mangas = slice.mapNotNull(::catalogToManga)
        return MangasPage(mangas, from + slice.size < filtered.size)
    }

    override fun getFilterList(): FilterList = FilterList()

    private fun parseCatalog(response: Response): MangasPage {
        val json = responseJson(response) ?: return MangasPage(emptyList(), false)
        val meta = json.get("meta")?.takeIf { it.isJsonObject }?.asJsonObject
        val page = meta?.intOrNull("page") ?: 1
        val totalPages = meta?.intOrNull("totalPages") ?: 1

        val mangas = dataArray(json).mapNotNull(::catalogToManga)
        return MangasPage(mangas, page < totalPages)
    }

    private fun catalogToManga(obj: JsonObject): SManga? {
        val slug = obj.stringOrNull("slug") ?: return null
        return SManga.create().apply {
            url = "/manga/$slug"
            title = obj.stringOrNull("title") ?: ""
            thumbnail_url = proxyIfNeeded(obj.stringOrNull("coverUrl").orEmpty())
        }
    }

    // =========================== Manga Details ===========================

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET("$apiUrl/manga/${slugOf(manga)}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val json = responseJson(response)
        val data = json?.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return SManga.create()

        return SManga.create().apply {
            val slug = data.stringOrNull("slug") ?: ""
            url = if (slug.isBlank()) response.request.url.toString() else "/manga/$slug"
            title = data.stringOrNull("title")?.trim().orEmpty()
            thumbnail_url = proxyIfNeeded(data.stringOrNull("coverUrl").orEmpty())
            data.stringOrNull("description")?.trim()?.takeIf { it.isNotBlank() }?.let {
                description = Jsoup.parse(it).text().trim()
            }
            genre = data.arrayOrNull("tags")?.mapNotNull { tag ->
                val t = tag.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val name = t.stringOrNull("name") ?: return@mapNotNull null
                if (t.stringOrNull("category") == "LANGUAGE") null else name
            }?.filter { it.isNotBlank() }?.joinToString() ?: ""
            status = when (data.stringOrNull("status")) {
                "COMPLETED" -> SManga.COMPLETED
                "ONGOING" -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
        }
    }

    // ============================== Chapters =============================

    override fun chapterListRequest(manga: SManga): Request =
        GET("$apiUrl/manga/${slugOf(manga)}", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val json = responseJson(response)
        val data = json?.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
        val slug = data?.stringOrNull("slug") ?: return emptyList()
        val chapters = data.get("chapters")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return emptyList()

        return chapters.mapNotNull { element ->
            val chapter = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = chapter.stringOrNull("id") ?: return@mapNotNull null
            val number = chapter.intOrNull("number") ?: return@mapNotNull null
            SChapter.create().apply {
                url = "$apiUrl/manga/$slug/chapters/$id"
                name = buildString {
                    append("Chapter ", number.toString())
                    chapter.stringOrNull("title")?.takeIf { it.isNotBlank() }?.let { append(": ", it) }
                }
                chapter_number = number.toFloat()
                date_upload = chapter.stringOrNull("createdAt")?.let(::parseDate) ?: 0L
            }
        }.sortedByDescending { it.chapter_number }
    }

    // =============================== Pages ===============================

    override fun getChapterUrl(chapter: SChapter): String {
        // chapter.url = "/api/v1/manga/<slug>/chapters/<chapterId>"
        val rest = chapter.url.removePrefix("/api/v1/manga/")
        val slug = rest.substringBefore("/chapters/")
        val id = rest.substringAfter("/chapters/")
        return "$baseUrl/manga/$slug/read/$id"
    }

    override fun pageListRequest(chapter: SChapter): Request =
        GET(
            if (chapter.url.startsWith("http")) chapter.url else "$baseUrl${chapter.url}",
            headers,
        )

    override fun pageListParse(response: Response): List<Page> {
        val json = responseJson(response)
        val pages = json?.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return emptyList()
        val chapterUrl = response.request.url.toString()
        return pages.mapNotNull { element ->
            val page = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val number = page.intOrNull("number") ?: return@mapNotNull null
            val imageUrl = page.stringOrNull("imageUrl") ?: return@mapNotNull null
            Page(number - 1, chapterUrl, imageUrl)
        }
    }

    override fun imageRequest(page: Page): Request {
        val url = proxyIfNeeded(page.imageUrl ?: page.url)
        return GET(if (url.startsWith("http")) url else "$baseUrl$url", headers)
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================= Utilities =============================

    /** Loads every manhwa from the API (page 1 + remaining pages) for in-memory search. */
    private fun loadAllManhwa(primary: Response): List<JsonObject> {
        val cached = manhwaCache
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.first < CACHE_TTL_MS) return cached.second

        val primaryJson = responseJson(primary)
        val myPage = primary.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val totalPages = primaryJson?.get("meta")?.takeIf { it.isJsonObject }
            ?.asJsonObject?.intOrNull("totalPages") ?: myPage

        val list = mutableListOf<JsonObject>()
        list.addAll(dataArray(primaryJson))
        for (page in 1..totalPages) {
            if (page == myPage) continue
            runCatching {
                client.newCall(GET("$apiUrl/manhwa?sort=latest&page=$page", headers))
                    .execute()
                    .use { r ->
                        if (r.isSuccessful) list.addAll(dataArray(responseJson(r)))
                    }
            }
        }
        manhwaCache = now to list
        return list
    }

    private fun dataArray(json: JsonObject?): List<JsonObject> =
        json?.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { if (it.isJsonObject) it.asJsonObject else null }
            ?: emptyList()

    private fun responseJson(response: Response): JsonObject? =
        runCatching {
            val body = response.body?.string() ?: return null
            JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject
        }.getOrNull()

    private fun slugOf(manga: SManga): String =
        manga.url.substringBefore("?").substringAfterLast("/")

    /** Legacy image hosts are served through the site's own image proxy. */
    private fun proxyIfNeeded(url: String): String {
        if (url.isBlank()) return url
        for (host in PROXIED_HOSTS) {
            if (url.startsWith("https://$host")) {
                val encoded = URLEncoder.encode(url, "utf-8").replace("+", "%20")
                return "$apiUrl/images/proxy?url=$encoded"
            }
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

    private fun JsonObject.intOrNull(key: String): Int? =
        if (has(key) && get(key).isJsonPrimitive) runCatching { get(key).asInt }.getOrNull() else null

    private fun JsonObject.arrayOrNull(key: String): JsonArray? =
        if (has(key) && get(key).isJsonArray) get(key).asJsonArray else null

    /** Resolves through the system DNS, falling back to hardcoded IPs when it fails. */
    private class IpFallbackDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return try {
                Dns.SYSTEM.lookup(hostname)
            } catch (e: UnknownHostException) {
                IP_FALLBACK[hostname.lowercase(Locale.US)] ?: throw e
            }
        }
    }

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        private const val PAGE_SIZE = 24
        private const val CACHE_TTL_MS = 5 * 60 * 1000L

        private val PROXIED_HOSTS = listOf(
            "kontol.online",
            "manhwa18.net",
            "cdn.pornwa.us",
            "cdn.pornwa.club",
        )

        /** Cloudflare Anycast addresses for doujiva.com (shared with cdn.doujiva.com). */
        private val IP_FALLBACK = mapOf(
            "doujiva.com" to listOf(
                InetAddress.getByName("104.21.15.93"),
                InetAddress.getByName("172.67.205.172"),
            ),
            "cdn.doujiva.com" to listOf(
                InetAddress.getByName("104.21.15.93"),
                InetAddress.getByName("172.67.205.172"),
            ),
        )

        private val DATE_FORMATS = listOf(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US),
        ).onEach { it.timeZone = TimeZone.getTimeZone("UTC") }

        @Volatile
        private var manhwaCache: Pair<Long, List<JsonObject>>? = null
    }
}
