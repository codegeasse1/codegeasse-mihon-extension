package eu.kanade.tachiyomi.extension.en.hentaifox

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
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URLEncoder

/*
 * HentaiFox (https://hentaifox.com) â a nhentai-style doujinshi gallery.
 *
 *     Latest   : /            (div.thumb cards; pagination via next link,
 *                page 2 = /page/2/, page 3+ = /pag/<n>/ â so we follow the
 *                "Next" link from the previous page instead of building URLs)
 *     Search   : /search/?q=<query>
 *     Detail   : /gallery/<id>/   (h1 title, div.cover img, #load_dir/#load_id/#load_pages)
 *     Reader   : /g/<id>/<n>/     (#gimg data-src)
 *     Pages    : https://i3.hentaifox.com/<load_dir>/<load_id>/<n>.jpg
 */
class HentaiFox : HttpSource() {

    override val name = "HentaiFox"
    override val baseUrl = "https://hentaifox.com"
    override val lang = "en"
    override val supportsLatest = true

    @Volatile
    private var nextUrl: String? = null

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", BROWSER_UA)
        .set("Referer", "$baseUrl/")

    // =========================== Browse & Search =========================

    override fun popularMangaRequest(page: Int): Request = latestUpdatesRequest(page)

    override fun popularMangaParse(response: Response): MangasPage = latestUpdatesParse(response)

    override fun latestUpdatesRequest(page: Int): Request {
        if (page == 1) nextUrl = null
        return GET(nextUrl ?: "$baseUrl/", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = parseList(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (page == 1) nextUrl = null
        val url = "$baseUrl/search/?q=${URLEncoder.encode(query, "utf-8")}"
        return GET(nextUrl ?: url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseList(response)

    private fun parseList(response: Response): MangasPage {
        val doc = parseDoc(response)
        val mangas = doc.select("div.thumb").mapNotNull { item ->
            val link = item.selectFirst("a[href^='/gallery/']") ?: return@mapNotNull null
            val title = item.selectFirst("h2.g_title")?.text()?.trim() ?: return@mapNotNull null
            if (title.isBlank()) return@mapNotNull null
            SManga.create().apply {
                this.title = title
                setUrlWithoutDomain(link.absUrl("href"))
                thumbnail_url = item.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("abs:src") } }?.toHttpUrl()
            }
        }
        nextUrl = nextPageUrl(doc)
        return MangasPage(mangas, nextUrl != null)
    }

    // =========================== Manga Details ===========================

    override fun getMangaUrl(manga: SManga): String =
        if (manga.url.startsWith("http")) manga.url else baseUrl + manga.url

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = parseDoc(response)
        return SManga.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
            thumbnail_url = doc.selectFirst("div.cover img")?.let { it.attr("data-src").ifEmpty { it.absUrl("src") } }?.toHttpUrl()
        }
    }

    // ============================== Chapters =============================

    override fun getChapterUrl(chapter: SChapter): String =
        if (chapter.url.startsWith("http")) chapter.url else baseUrl + chapter.url

    override fun chapterListRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), headers)

    override fun chapterListParse(response: Response): List<SChapter> =
        listOf(SChapter.create().apply {
            url = response.request.url.toString()
            name = "Gallery"
            chapter_number = 1f
        })

    // =============================== Pages ===============================

    override fun pageListRequest(chapter: SChapter): Request =
        GET(getChapterUrl(chapter), headers)

    override fun pageListParse(response: Response): List<Page> {
        val doc = parseDoc(response)
        val dir = doc.selectFirst("#load_dir")?.attr("value").orEmpty()
        val id = doc.selectFirst("#load_id")?.attr("value").orEmpty()
        val pages = doc.selectFirst("#load_pages")?.attr("value")?.toIntOrNull()
            ?: doc.select("img[data-src*='t.jpg']").size
        if (dir.isEmpty() || id.isEmpty() || pages == null || pages <= 0) return emptyList()
        return (1..pages).map { n ->
            Page(n - 1, imageUrl = "https://i3.hentaifox.com/$dir/$id/$n.webp")
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================= Utilities =============================

    private fun String.toHttpUrl(): String? {
        val raw = trim()
        return raw.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun parseDoc(response: Response): Document =
        Jsoup.parse(response.body?.string() ?: throw IOException("Empty response body"), response.request.url.toString())

    private fun nextPageUrl(doc: Document): String? {
        val next = doc.select("ul.pagination a.page-link").firstOrNull { it.text().trim().startsWith("Next") }
            ?: doc.select("a[rel='next']").firstOrNull()
            ?: return null
        val href = next.attr("href")
        if (href.isEmpty() || href == "#") return null
        return next.absUrl("href").toHttpUrl()
    }

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
