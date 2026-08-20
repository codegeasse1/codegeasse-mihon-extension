package eu.kanade.tachiyomi.extension.en.ososedki

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
 * OSOSEDKI (https://ososedki.com) — a custom PHP gallery of leaked OnlyFans/Patreon
 * sets. The requested source is the /category/asian listing, which is paginated at
 * ?page=N (currently ~567 pages) with a.next-page as the "more pages" signal.
 *
 *     Listing : https://ososedki.com/category/asian?page=N
 *               article.gallery-item a.gallery-link cards (ads are banner-item divs
 *               without a gallery-link, so they're skipped); hasNext via a.next-page
 *     Search  : https://ososedki.com/search?q=QUERY&page=N
 *     Details : https://ososedki.com/photos/<id>  — title/date/description come from
 *               the embedded ImageGallery JSON-LD, pages from figure.photo-item a[href]
 *               (1280px-wide full images).
 * Cloudflare in front, so the headers are just a browser User-Agent.
 */
class Ososedki : HttpSource() {

    override val name = "OSOSEDKI"
    override val baseUrl = "https://ososedki.com"
    override val lang = "en"
    override val supportsLatest = true

    private val categoryUrl = "$baseUrl/category/asian"

    override fun headersBuilder(): Headers.Builder =
        super.headersBuilder().set("User-Agent", BROWSER_UA)

    // ========================== Browse & Search ==========================

    override fun popularMangaRequest(page: Int): Request =
        GET("$categoryUrl?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseListing(response)

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response): MangasPage = parseListing(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/search?q=${query.encodeUrl()}&page=$page", headers)

    override fun searchMangaParse(response: Response): MangasPage = parseListing(response)

    private fun parseListing(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body?.string().orEmpty(), response.request.url.toString())
        val mangas = doc.select("article.gallery-item a.gallery-link").mapNotNull { link ->
            val url = link.absUrl("href")
            if (url.isBlank()) return@mapNotNull null
            SManga.create().apply {
                this.url = url
                title = link.selectFirst("h3")?.text()?.trim().orEmpty()
                thumbnail_url = link.selectFirst("img")?.absUrl("src")
            }
        }
        val hasNext = doc.selectFirst("a.next-page") != null
        return MangasPage(mangas, hasNext)
    }

    // ========================== Manga Details =============================

    override fun getMangaUrl(manga: SManga): String = manga.url

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body?.string().orEmpty(), response.request.url.toString())
        val jsonLd = doc.selectFirst("script[type=\"application/ld+json\"]")?.html()
        val title = jsonLd?.let { Regex("\"name\":\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
            ?.takeIf(String::isNotBlank)
            ?: doc.selectFirst("h1.text-white")?.text()?.trim().orEmpty()
        val cover = jsonLd?.let { Regex("\"thumbnailUrl\":\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
        val description = jsonLd?.let { Regex("\"description\":\"([^\"]*)\"").find(it)?.groupValues?.get(1) }
            ?: doc.selectFirst("p.text-white-50")?.text()?.trim()
        return SManga.create().apply {
            this.title = title
            url = response.request.url.toString()
            thumbnail_url = cover
            this.description = description?.take(DESCRIPTION_LIMIT)
            status = SManga.ONGOING
        }
    }

    // ============================= Chapters ===============================

    override fun chapterListRequest(manga: SManga): Request =
        GET(manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = Jsoup.parse(response.body?.string().orEmpty(), response.request.url.toString())
        val url = response.request.url.toString()
        val date = doc.selectFirst("script[type=\"application/ld+json\"]")?.html()
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
        val doc = Jsoup.parse(response.body?.string().orEmpty(), response.request.url.toString())
        val pages = mutableListOf<Page>()
        doc.select("figure.photo-item a[href]").forEach { a ->
            val src = a.absUrl("href")
            if (src.startsWith("http") && IMAGE_REGEX.containsMatchIn(src)) {
                pages.add(Page(pages.size, src, src))
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
