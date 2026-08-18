package eu.kanade.tachiyomi.extension.en.hentaizap

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
 * HentaiZap (https://hentaizap.com) — a nhentai-style doujinshi gallery
 * (same template family as IMHentai).
 *
 *     Latest   : /            (article.hz-gallery-card, ?page=N)
 *     Search   : /search/?key=<query>
 *     Detail   : /gallery/<id>/   (h1#gallery-title, #js-thumbs-grid[data-total-pages],
 *                first thumb img src = https://m11.hentaizap.com/<dir>/<id>/1t.jpg)
 *     Pages    : https://m11.hentaizap.com/<dir>/<id>/<n>.webp
 */
class HentaiZap : HttpSource() {

    override val name = "HentaiZap"
    override val baseUrl = "https://hentaizap.com"
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
        val url = "$baseUrl/search/?key=${URLEncoder.encode(query, "utf-8")}"
        return GET(nextUrl ?: url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseList(response)

    private fun parseList(response: Response): MangasPage {
        val doc = parseDoc(response)
        val mangas = doc.select("article.hz-gallery-card").mapNotNull { item ->
            val link = item.selectFirst("a.hz-gallery-card__cover[href^='/gallery/']")
                ?: item.selectFirst("a[href^='/gallery/']")
                ?: return@mapNotNull null
            val title = item.selectFirst("h2.hz-gallery-card__title a")?.text()?.trim()
                ?: item.selectFirst("h2 a")?.text()?.trim()
                ?: return@mapNotNull null
            if (title.isBlank()) return@mapNotNull null
            SManga.create().apply {
                this.title = title
                setUrlWithoutDomain(link.absUrl("href"))
                thumbnail_url = item.selectFirst("img")?.attr("src").orEmpty()
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
            title = doc.selectFirst("h1#gallery-title")?.text()?.trim().orEmpty()
            thumbnail_url = doc.selectFirst("img[src$='cover.jpg']")?.attr("src").orEmpty()
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
        val total = doc.selectFirst("#js-thumbs-grid")?.attr("data-total-pages")?.toIntOrNull() ?: 0
        val firstThumb = doc.selectFirst("#js-thumbs-grid img")?.attr("src").orEmpty()
        // e.g. https://m11.hentaizap.com/032/u6t1y8jo3e/1t.jpg  ->  base = .../u6t1y8jo3e
        val base = firstThumb.substringBeforeLast('/')
        if (total <= 0 || base.isEmpty()) return emptyList()
        return (1..total).map { n ->
            Page(n - 1, imageUrl = "$base/$n.webp")
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================= Utilities =============================

    private fun parseDoc(response: Response): Document =
        Jsoup.parse(response.body?.string() ?: throw IOException("Empty response body"))

    private fun nextPageUrl(doc: Document): String? {
        doc.selectFirst("a[rel='next']")?.let { return it.absUrl("href") }
        val links = doc.select("nav.hz-pagination a[href]")
        for (i in links.size - 1 downTo 0) {
            val a = links[i]
            val href = a.attr("href")
            if (href.isEmpty() || href == "#") continue
            return a.absUrl("href")
        }
        return null
    }

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
