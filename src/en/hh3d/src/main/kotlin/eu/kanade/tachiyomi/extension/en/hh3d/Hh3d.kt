package eu.kanade.tachiyomi.extension.en.hh3d

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/*
 * Hh3d (https://hh3d.wtf) — a Next.js SSR'd gallery of AI 3D adult images
 * (Vietnamese content). Everything is server-rendered HTML; no public JSON API.
 *
 *     Home/Latest : /?page=N                      -> a[href*="/posts/"] cards (videos filtered out)
 *     Popular     : same listing (site has no server-side popularity sort)
 *     Search      : /search?q=<q>&tab=images&page=N
 *     Details     : /posts/<slug>
 *     Pages       : the <article> gallery, medium/ URLs rewritten to full/
 *
 * The site sits behind Cloudflare and its image CDN rejects requests carrying
 * browser-only headers (Origin/Sec-Fetch-Site) from foreign contexts, so we send
 * just a browser User-Agent (no Referer/Origin) — that works for both HTML and
 * the images.hh3d.wtf CDN.
 */
class Hh3d : HttpSource() {

    override val name = "Hh3d"
    override val baseUrl = "https://hh3d.wtf"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder =
        super.headersBuilder().set("User-Agent", BROWSER_UA)

    // ========================== Search & Browse ===========================

    override fun popularMangaRequest(page: Int): Request = latestUpdatesRequest(page)

    override fun popularMangaParse(response: Response): MangasPage = parseListing(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseListing(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val q = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        return GET("$baseUrl/search?q=$q&tab=images&page=$page", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseListing(response)

    private fun parseListing(response: Response): MangasPage {
        val doc = response.asJsoup()
        val seen = HashSet<String>()
        val mangas = doc.select("a[href*=\"/posts/\"]").mapNotNull { card ->
            val title = card.selectFirst("h3")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: card.selectFirst("img")?.attr("alt")?.trim()
                ?: return@mapNotNull null
            SManga.create().apply {
                url = card.absUrl("href")
                this.title = title
                thumbnail_url = card.selectFirst("img")?.attr("src")
            }
        }.filter { seen.add(it.url) }
        val hasNext = doc.select("a").any { it.text().trim() == "Next" }
        return MangasPage(mangas, hasNext)
    }

    // ========================== Manga Details =============================

    override fun getMangaUrl(manga: SManga): String = manga.url

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()
        val title = doc.selectFirst("article h1")?.text()?.trim()
            ?.let { TITLE_SUFFIX_REGEX.replace(it, "").trim() }
            ?: doc.selectFirst("h1")?.text()?.trim().orEmpty()
        val cover = doc.selectFirst("article img[src*=\"/medium/\"]")?.attr("src")
            ?.replace("/medium/", "/full/")
        return SManga.create().apply {
            this.title = title
            url = response.request.url.toString()
            thumbnail_url = cover
            status = SManga.ONGOING
            genre = doc.select("a[href*=\"/tags/\"]").map { it.text().trim() }.distinct().joinToString()
            description = doc.selectFirst(".prose")?.text()?.trim()
        }
    }

    // ============================= Chapters ===============================

    override fun chapterListRequest(manga: SManga): Request =
        GET(manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = response.asJsoup()
        val date = doc.selectFirst("article time[datetime]")?.attr("datetime")?.let(::parseDate) ?: 0L
        return listOf(SChapter.create().apply {
            url = response.request.url.toString()
            name = "Gallery"
            chapter_number = 1f
            date_upload = date
        })
    }

    override fun getChapterUrl(chapter: SChapter): String = chapter.url

    // =============================== Pages ================================

    override fun pageListRequest(chapter: SChapter): Request =
        GET(chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        val doc = response.asJsoup()
        return doc.select("article img[src*=\"/medium/\"]").mapIndexed { index, img ->
            val full = img.attr("src").replace("/medium/", "/full/")
            Page(index, full, full)
        }
    }

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl!!, headers)

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException()

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        // SEO titles carry a " | <description>" suffix that isn't part of the name.
        private val TITLE_SUFFIX_REGEX = Regex("""\s*\|\s*.+$""")
    }
}

private fun parseDate(value: String): Long? = runCatching { DATE_FORMAT_PARSER.parse(value)?.time }.getOrNull()

private val DATE_FORMAT_PARSER = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    .apply { timeZone = TimeZone.getTimeZone("UTC") }
