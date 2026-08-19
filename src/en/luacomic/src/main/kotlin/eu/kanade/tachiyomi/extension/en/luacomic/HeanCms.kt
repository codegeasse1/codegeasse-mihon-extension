package eu.kanade.tachiyomi.extension.en.luacomic

import com.google.gson.Gson
import eu.kanade.tachiyomi.network.GET
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
import java.io.IOException

/**
 * Lua Comic (https://luacomic.org) — HeanCms-backed API source.
 *
 *     Browse   : GET {api}/query?page=&perPage=&series_type=Comic&orderBy=total_views&adult=true
 *     Latest   : GET {api}/query?...&orderBy=latest (this site's API returns newest-first with order=asc)
 *     Search   : same query endpoint with query_string=<q>
 *     Manga    : GET {api}/series/<slug>          (details; url carries "#<seriesId>" for chapters)
 *     Chapters : GET {api}/chapter/query?page=&perPage=1000&series_id=<id>   (paid chapters are skipped)
 *     Pages    : GET {api}/chapter/<slug>/<chapter_slug> -> {chapter:{chapter_data:{images:[...]}}}
 *
 * The API host (api.luacomic.org) is Cloudflare-protected against datacenter IPs but is the same
 * public API the site's own frontend uses, so requests from real devices work.
 */
abstract class HeanCms : HttpSource() {

    override val lang = "en"
    override val supportsLatest = true

    protected open val apiUrl: String
        get() = "https://api.luacomic.org"

    protected open val latestSortBy = "asc"

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", BROWSER_UA)
        .set("Accept-Language", "en-US,en;q=0.9")
        .set("Origin", baseUrl)
        .set("Referer", "$baseUrl/")
        // api.luacomic.org sits behind a Cloudflare WAF that blocks plain OkHttp requests
        // (the extension showed "HTTP 403") while passing real browsers. Mimic a browser.
        .set("Sec-Fetch-Mode", "cors")
        .set("Sec-Fetch-Site", "same-site")
        .set("Sec-Fetch-Dest", "empty")

    @Volatile
    private var warmedUp = false

    /** Visit the site once so Cloudflare issues its cookies (e.g. __cf_bm) before the API calls. */
    private fun ensureWarmedUp() {
        if (warmedUp) return
        synchronized(this) {
            if (warmedUp) return
            runCatching { client.newCall(GET(baseUrl, headers)).execute().use { } }
            warmedUp = true
        }
    }

    // ============================== Popular ==============================

    override fun popularMangaRequest(page: Int): Request {
        ensureWarmedUp()
        val url = queryUrlBuilder(page, "", "All", "desc", "total_views", "[]")
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage = parseSearchMangaList(response)

    // ============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        ensureWarmedUp()
        val url = queryUrlBuilder(page, "", "All", latestSortBy, "latest", "[]")
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = parseSearchMangaList(response)

    // ============================== Search ===============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        ensureWarmedUp()
        val url = queryUrlBuilder(page, query, "All", "desc", "total_views", "[]")
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseSearchMangaList(response)

    private fun queryUrlBuilder(
        page: Int,
        query: String,
        status: String,
        order: String,
        orderBy: String,
        tagIds: String,
    ): HttpUrl = "$apiUrl/query".toHttpUrl().newBuilder()
        .addQueryParameter("query_string", query)
        .addQueryParameter("status", status)
        .addQueryParameter("order", order)
        .addQueryParameter("orderBy", orderBy)
        .addQueryParameter("series_type", "Comic")
        .addQueryParameter("page", page.toString())
        .addQueryParameter("perPage", "12")
        .addQueryParameter("tags_ids", tagIds)
        .addQueryParameter("adult", "true")
        .build()

    private fun parseSearchMangaList(response: Response): MangasPage {
        val body = response.body?.string() ?: throw IOException("Empty response body")
        checkNotBlocked(body)
        val result = gson.fromJson(body, QuerySearchDto::class.java)
        val mangas = result.data.map { it.toSManga() }
        return MangasPage(mangas, result.meta?.hasNextPage() ?: false)
    }

    // ============================== Details ==============================

    override fun getMangaUrl(manga: SManga): String =
        if (manga.url.startsWith("http")) manga.url else baseUrl + manga.url

    override fun mangaDetailsRequest(manga: SManga): Request {
        ensureWarmedUp()
        val slug = manga.url.substringAfterLast("/").substringBefore("#")
        return GET("$apiUrl/series/$slug", jsonHeaders())
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val body = response.body?.string() ?: throw IOException("Empty response body")
        checkNotBlocked(body)
        return gson.fromJson(body, SeriesDto::class.java).toSManga()
    }

    // ============================== Chapters =============================

    override fun getChapterUrl(chapter: SChapter): String =
        baseUrl + chapter.url.substringBeforeLast("#")

    override fun chapterListRequest(manga: SManga): Request {
        ensureWarmedUp()
        val seriesId = manga.url.substringAfterLast("#")
        val seriesSlug = manga.url.substringAfterLast("/").substringBefore("#")
        val url = "$apiUrl/chapter/query".toHttpUrl().newBuilder()
            .addQueryParameter("page", "1")
            .addQueryParameter("perPage", PER_PAGE_CHAPTERS.toString())
            .addQueryParameter("series_id", seriesId)
            // Carried to chapterListParse so chapter URLs can be rebuilt; the API ignores it.
            .addQueryParameter("series_slug", seriesSlug)
            .build()
        return GET(url, jsonHeaders())
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val seriesSlug = response.request.url.queryParameter("series_slug")
            ?: throw IOException("Missing series slug")
        val baseUrlBuilder = response.request.url.newBuilder()
        val headers = response.request.headers

        val chapters = mutableListOf<ChapterDto>()
        var page = 1
        var hasNext = true
        var current: Response? = response
        while (hasNext) {
            val res = current
                ?: client.newCall(GET(baseUrlBuilder.setQueryParameter("page", page.toString()).build(), headers)).execute()
            try {
                val body = res.body?.string() ?: throw IOException("Empty response body")
                checkNotBlocked(body)
                val result = gson.fromJson(body, ChapterPayloadDto::class.java)
                chapters.addAll(result.data)
                hasNext = result.meta?.hasNextPage() ?: false
                page++
            } finally {
                res.close()
            }
            current = null
        }

        val now = System.currentTimeMillis()
        return chapters
            .filter { it.price == 0 }
            .map { it.toSChapter(seriesSlug) }
            .filter { it.date_upload != 0L && it.date_upload <= now }
    }

    // =============================== Pages ===============================

    override fun pageListRequest(chapter: SChapter): Request {
        ensureWarmedUp()
        return GET(apiUrl + chapter.url.replace("/series/", "/chapter/").substringBefore("#"), jsonHeaders())
    }

    override fun pageListParse(response: Response): List<Page> {
        val body = response.body?.string() ?: throw IOException("Empty response body")
        checkNotBlocked(body)
        val result = gson.fromJson(body, PagePayloadDto::class.java)

        if (result.isPaywalled() || result.chapter?.chapterData?.images == null) {
            throw IOException("Paid chapter unavailable.")
        }

        return result.chapter.chapterData.images.mapIndexed { i, img ->
            Page(i, imageUrl = img.toAbsoluteUrl())
        }
    }

    override fun imageRequest(page: Page): Request {
        val imageHeaders = headers.newBuilder()
            .removeAll("Origin")
            .add("Accept", ACCEPT_IMAGE)
            .build()
        return Request.Builder().url(page.imageUrl ?: page.url).headers(imageHeaders).build()
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================= Utilities =============================

    private fun checkNotBlocked(body: String) {
        if (CLOUDFLARE_BLOCK_MARKERS.any { body.contains(it) }) {
            throw IOException("LuaComic's API is behind a Cloudflare firewall (HTTP 403) that blocks this app. Open the site in a browser to verify.")
        }
    }

    private fun jsonHeaders() = headers.newBuilder().add("Accept", ACCEPT_JSON).build()

    private fun String.toAbsoluteUrl(): String =
        if (startsWith("https://") || startsWith("http://")) this else "$apiUrl/$this"

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        private const val ACCEPT_JSON = "application/json, text/plain, */*"
        private const val ACCEPT_IMAGE = "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"

        private const val PER_PAGE_CHAPTERS = 1000

        private val CLOUDFLARE_BLOCK_MARKERS = listOf(
            "Attention Required",
            "cf-error-details",
            "Sorry, you have been blocked",
        )

        private val gson = Gson()
    }
}
