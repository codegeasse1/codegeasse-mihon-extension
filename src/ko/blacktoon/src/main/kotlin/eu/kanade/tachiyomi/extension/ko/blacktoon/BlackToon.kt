package eu.kanade.tachiyomi.extension.ko.blacktoon

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URLEncoder
import kotlin.math.min
import kotlin.random.Random

/*
 * BlackToon (https://blacktoon.cc) — Korean webtoon aggregator.
 * The full series catalog is shipped as JS data files ("data/webtoon/webtoon_*.js")
 * referenced from the homepage; each file contains `data<idx> = [ ... ]`.
 * Chapters live in per-series JSON files (/data/toonlist/<id>.js) and pages are
 * rendered in #toon_content_imgs. Images are hosted on cdn blacktoonimg.com.
 * Contains adult content (NSFW).
 *
 *     Browse  : in-memory catalog sorted by hot  (24 per page)
 *     Latest  : in-memory catalog sorted by updatedAt
 *     Search  : in-memory filter by title/author
 *     Manga   : /webtoon/<id>.html  |  Chapters: /data/toonlist/<id>.js
 *     Chapter : /webtoons/<mangaId>/<chapterId>.html
 */
class BlackToon : HttpSource() {

    override val name = "BlackToon"
    override val baseUrl = "https://blacktoon.cc"
    override val lang = "ko"
    override val supportsLatest = true

    private var currentHost = ""
    private val cdnUrl = "https://blacktoonimg.com/"

    override val client = network.client.newBuilder().addInterceptor { chain ->
        if (currentHost.isBlank()) {
            noRedirectClient.newCall(GET(baseUrl, headers)).execute().use {
                currentHost = it.headers["location"]?.toHttpUrlOrNull()?.host
                    ?: baseUrl.toHttpUrlOrNull()?.host.orEmpty()
            }
        }

        val request = chain.request().newBuilder().apply {
            if (chain.request().url.toString().startsWith(baseUrl)) {
                url(
                    chain.request().url.newBuilder()
                        .host(currentHost)
                        .build(),
                )
            }
            header("Referer", "https://$currentHost/")
            header("Origin", "https://$currentHost")
        }.build()

        chain.proceed(request)
    }.build()

    private val noRedirectClient = network.client.newBuilder()
        .followRedirects(false)
        .build()

    private val gson = Gson()

    private val seriesType = object : TypeToken<List<SeriesItem>>() {}.type
    private val chapterType = object : TypeToken<List<Chapter>>() {}.type

    @Volatile
    private var cachedDb: List<SeriesItem>? = null

    private fun db(): List<SeriesItem> {
        cachedDb?.let { return it }
        synchronized(this) {
            cachedDb?.let { return it }
            val doc = Jsoup.parse(client.newCall(GET(baseUrl, headers)).execute().body.string())
            val list = mutableListOf<SeriesItem>()
            for (scriptEl in doc.select("script[src*=data/webtoon]")) {
                val jsUrl = scriptEl.absUrl("src")
                if (jsUrl.isBlank()) continue
                val body = runCatching {
                    client.newCall(GET(jsUrl, headers)).execute().use { it.body.string() }
                }.getOrNull() ?: continue
                val listIdx = body.substringBefore(" = ").substringAfter("data").toIntOrNull() ?: continue
                val json = body.substringAfter(" = ").removeSuffix(";")
                val items = runCatching { gson.fromJson<List<SeriesItem>>(json, seriesType) }.getOrNull() ?: continue
                items.forEach { it.listIndex = listIdx }
                list += items
            }
            cachedDb = list
            return list
        }
    }

    private fun List<SeriesItem>.pageChunk(page: Int): MangasPage {
        if (isEmpty()) return MangasPage(emptyList(), false)
        val from = (page - 1) * PAGE_SIZE
        val to = min(page * PAGE_SIZE, size)
        if (from >= size) return MangasPage(emptyList(), false)
        return MangasPage(
            mangas = subList(from, to).map { it.toSManga(cdnUrl) },
            hasNextPage = (page * PAGE_SIZE) < size,
        )
    }

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        return db().sortedByDescending { it.hot }.pageChunk(page)
    }

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        return db().sortedByDescending { it.updatedAt }.pageChunk(page)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = if (query.isBlank()) {
            "$baseUrl/?page=$page"
        } else {
            "$baseUrl/?page=$page&s=${URLEncoder.encode(query, "utf-8")}"
        }
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val query = response.request.url.queryParameter("s")?.trim().orEmpty()
        var list = db()
        if (query.isNotBlank()) {
            list = list.filter {
                it.name.contains(query, true) || it.author.contains(query, true)
            }
        }
        return list.pageChunk(page)
    }

    override fun getMangaUrl(manga: SManga): String = buildString {
        append("https://")
        append(host())
        append("/webtoon/")
        append(manga.url)
        append(".html")
    }

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET("$baseUrl/webtoon/${manga.url}.html#${manga.status}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body?.string() ?: throw IOException("Empty response body"))
        return SManga.create().apply {
            description = doc.select("p.mt-2").lastOrNull()?.text()
            thumbnail_url = doc.selectFirst("script:containsData(+img_domain+)")?.data()?.let {
                cdnUrl + it.substringAfter("+'").substringBefore("'+")
            }
            status = response.request.url.fragment?.toIntOrNull() ?: SManga.UNKNOWN
        }
    }

    override fun chapterListRequest(manga: SManga): Request {
        val url = "$baseUrl/data/toonlist/${manga.url}.js?v=${"%.17f".format(Random.nextDouble())}"
        return GET(url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val mangaId = response.request.url.pathSegments.last().removeSuffix(".js")
        val body = response.body?.string() ?: return emptyList()
        val json = body.substringAfter(" = ").removeSuffix(";")
        val data = runCatching { gson.fromJson<List<Chapter>>(json, chapterType) }.getOrNull() ?: return emptyList()
        return data.map { it.toSChapter(mangaId) }.reversed()
    }

    override fun getChapterUrl(chapter: SChapter): String = buildString {
        append("https://")
        append(host())
        append("/webtoons/")
        append(chapter.url)
        append(".html")
    }

    override fun pageListRequest(chapter: SChapter): Request =
        GET("$baseUrl/webtoons/${chapter.url}.html", headers)

    override fun pageListParse(response: Response): List<Page> {
        val doc = Jsoup.parse(response.body?.string() ?: throw IOException("Empty response body"))
        val pages = mutableListOf<Page>()
        for (img in doc.select("#toon_content_imgs img")) {
            val oSrc = img.attr("o_src").trim()
            if (oSrc.isBlank()) continue
            pages.add(Page(pages.size, imageUrl = cdnUrl + oSrc))
        }
        return pages
    }

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl ?: page.url, headers.newBuilder().set("Referer", "$baseUrl/").build())

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    private fun host(): String =
        currentHost.ifBlank { baseUrl.toHttpUrlOrNull()?.host.orEmpty() }

    companion object {
        private const val PAGE_SIZE = 24
    }
}
