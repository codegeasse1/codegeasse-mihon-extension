package eu.kanade.tachiyomi.extension.en.asianpink

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/*
 * AsianPink (https://asianpink.net) — a WordPress photo-gallery site ("high quality model
 * stock photos asian models"): cosplay / model photo sets. The home page is a custom,
 * unpaginated "latest" template, so we browse the paginated asian-model category:
 *
 *     Listing : https://asianpink.net/category/asian-model/[page/N/]
 *               article.latest-post-item cards; hasNext via a.next.page-numbers,
 *               so pagination runs to the real last page (no artificial cap).
 *     Search  : https://asianpink.net/?s=QUERY   (the search template is single-page)
 *     Details : https://asianpink.net/<slug>/    h1.entry-title + Yoast JSON-LD
 *     Pages   : the my-photo-gallery block in .entry-content is itself paginated as
 *               <slug>/N/ with a .next.page-numbers link — we follow those sub-pages.
 * Images are lazy-loaded via data-lazy-src. The site is behind Cloudflare, so the
 * headers are just a browser User-Agent.
 */
class Asianpink : HttpSource() {

    override val name = "AsianPink"
    override val baseUrl = "https://asianpink.net"
    override val lang = "en"
    override val supportsLatest = true

    private val categoryUrl = "$baseUrl/category/asian-model"

    override fun headersBuilder(): Headers.Builder =
        super.headersBuilder().set("User-Agent", BROWSER_UA)

    // ========================== Browse & Search ==========================

    override fun popularMangaRequest(page: Int): Request =
        GET(if (page == 1) categoryUrl else "$categoryUrl/page/$page/", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseListing(response)

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response): MangasPage = parseListing(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/?s=${query.encodeUrl()}", headers)

    override fun searchMangaParse(response: Response): MangasPage = parseListing(response)

    private fun parseListing(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body?.string().orEmpty())
        val mangas = doc.select("article.latest-post-item").mapNotNull { article ->
            val link = article.selectFirst(".post-thumbnail a") ?: return@mapNotNull null
            val url = link.absUrl("href")
            if (url.isBlank()) return@mapNotNull null
            SManga.create().apply {
                this.url = url
                title = article.selectFirst(".post-title a")?.text()?.trim().orEmpty()
                thumbnail_url = article.selectFirst(".post-thumbnail img")?.let { img ->
                    img.absUrl("data-lazy-src").ifBlank { img.absUrl("src") }
                }
            }
        }
        val hasNext = doc.selectFirst("a.next.page-numbers") != null
        return MangasPage(mangas, hasNext)
    }

    // ========================== Manga Details =============================

    override fun getMangaUrl(manga: SManga): String = manga.url

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body?.string().orEmpty())
        val jsonLd = doc.selectFirst("script[type=\"application/ld+json\"]")?.html()?.replace("\\/", "/")
        val title = doc.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        val cover = jsonLd?.let { Regex("\"thumbnailUrl\":\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
        val genre = jsonLd?.let {
            Regex("\"articleSection\":\\[([^\\]]*)\\]").find(it)?.groupValues?.get(1)
        }?.replace("\"", "")
        val description = doc.selectFirst(".entry-content")?.text()?.trim()
        return SManga.create().apply {
            this.title = title
            url = response.request.url.toString()
            thumbnail_url = cover
            this.genre = genre.orEmpty()
            this.description = description?.take(DESCRIPTION_LIMIT)
            status = SManga.ONGOING
        }
    }

    // ============================= Chapters ===============================

    override fun chapterListRequest(manga: SManga): Request =
        GET(manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = Jsoup.parse(response.body?.string().orEmpty())
        val url = response.request.url.toString()
        val date = doc.selectFirst("script[type=\"application/ld+json\"]")?.html()
            ?.replace("\\/", "/")
            ?.let { Regex("\"datePublished\":\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
            ?.let(::parseDate) ?: 0L
        return listOf(SChapter.create().apply {
            this.url = url
            name = "Gallery"
            chapter_number = 1f
            date_upload = date
        })
    }

    // =============================== Pages ================================

    override fun pageListRequest(chapter: SChapter): Request =
        GET(chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        val pages = mutableListOf<Page>()
        val pending = ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        pending.addLast(response.request.url.toString())
        var fetched = 0
        while (pending.isNotEmpty() && fetched < 100) {
            val pageUrl = pending.removeFirst()
            if (!visited.add(pageUrl)) continue
            fetched++
            val doc = runCatching {
                client.newCall(GET(pageUrl, headers)).execute().use { res ->
                    Jsoup.parse(res.body?.string().orEmpty())
                }
            }.getOrNull() ?: continue
            doc.select(".my-photo-gallery-container .gallery-item img").forEach { img ->
                val src = img.absUrl("data-lazy-src").ifBlank { img.absUrl("src") }
                if (src.startsWith("http") && IMAGE_REGEX.containsMatchIn(src)) {
                    pages.add(Page(pages.size, src, src))
                }
            }
            doc.selectFirst(".my-photo-gallery-container .next.page-numbers")?.let { next ->
                val href = next.absUrl("href")
                if (href.isNotBlank()) pending.addLast(href)
            }
        }
        return pages
    }
}

private fun String.encodeUrl(): String =
    URLEncoder.encode(this, "UTF-8").replace("+", "%20")

private fun parseDate(value: String): Long? =
    runCatching { DATE_FORMAT_WITH_TZ.parse(value)?.time }.getOrNull()
        ?: runCatching { DATE_FORMAT_NO_TZ.parse(value)?.time }.getOrNull()

private val DATE_FORMAT_WITH_TZ = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
    .apply { timeZone = TimeZone.getTimeZone("UTC") }

private val DATE_FORMAT_NO_TZ = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    .apply { timeZone = TimeZone.getTimeZone("UTC") }

private val IMAGE_REGEX = Regex("""\.(jpe?g|png|gif|webp)(\?.*)?$""", RegexOption.IGNORE_CASE)

private const val BROWSER_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

private const val DESCRIPTION_LIMIT = 3000
