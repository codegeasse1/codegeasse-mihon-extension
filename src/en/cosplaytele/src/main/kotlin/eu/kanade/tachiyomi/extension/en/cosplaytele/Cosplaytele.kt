package eu.kanade.tachiyomi.extension.en.cosplaytele

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
 * Cosplaytele (https://cosplaytele.com) — a WordPress (Flatsome theme) cosplay photo
 * gallery. Standard WordPress pagination at /page/N/ (the site currently has ~471
 * pages), so we keep following a.next.page-number until it runs out.
 *
 *     Listing : https://cosplaytele.com[/page/N/]
 *               div.box-text-bottom.box-blog-post cards (the sidebar's box-shade
 *               widgets are excluded), hasNext via a.next.page-number
 *     Search  : https://cosplaytele.com/?s=QUERY (also /page/N/?s=... when it exists)
 *     Details : https://cosplaytele.com/<slug>/  h1.entry-title + gallery shortcode
 *     Pages   : the whole gallery lives in .entry-content .gallery-icon a[href] on
 *               the post page (videos are ignored — images only).
 * Cloudflare in front, so the headers are just a browser User-Agent.
 */
class Cosplaytele : HttpSource() {

    override val name = "CosplayTele"
    override val baseUrl = "https://cosplaytele.com"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder =
        super.headersBuilder().set("User-Agent", BROWSER_UA)

    // ========================== Browse & Search ==========================

    override fun popularMangaRequest(page: Int): Request =
        GET(if (page == 1) baseUrl else "$baseUrl/page/$page/", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseListing(response)

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response): MangasPage = parseListing(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/?s=${query.encodeUrl()}", headers)

    override fun searchMangaParse(response: Response): MangasPage = parseListing(response)

    private fun parseListing(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body?.string().orEmpty(), response.request.url.toString())
        val mangas = doc.select("div.box-text-bottom.box-blog-post").mapNotNull { box ->
            val link = box.selectFirst(".box-image a") ?: return@mapNotNull null
            val url = link.absUrl("href")
            if (url.isBlank()) return@mapNotNull null
            SManga.create().apply {
                this.url = url
                title = box.selectFirst(".post-title a")?.text()?.trim().orEmpty()
                thumbnail_url = box.selectFirst(".box-image img")?.let { img ->
                    img.absUrl("src").ifBlank { img.absUrl("data-src") }
                }
            }
        }
        val hasNext = doc.selectFirst("a.next.page-number") != null
        return MangasPage(mangas, hasNext)
    }

    // ========================== Manga Details =============================

    override fun getMangaUrl(manga: SManga): String = manga.url

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body?.string().orEmpty(), response.request.url.toString())
        val title = doc.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        val genre = doc.select("a[rel=\"category tag\"]")
            .mapNotNull { it.text().trim().takeIf(String::isNotEmpty) }
            .distinct()
            .joinToString()
        val cover = doc.selectFirst(".entry-content .gallery-icon a[href]")?.absUrl("href")
        val description = doc.selectFirst(".entry-content")?.text()?.trim()
        return SManga.create().apply {
            this.title = title
            url = response.request.url.toString()
            thumbnail_url = cover
            this.genre = genre
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
        val date = doc.selectFirst("time.entry-date[datetime]")?.attr("datetime")?.let(::parseDate) ?: 0L
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
        doc.select(".entry-content .gallery-icon a[href]").forEach { a ->
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
