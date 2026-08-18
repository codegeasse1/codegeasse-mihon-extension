package eu.kanade.tachiyomi.extension.en.hentaivox

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
 * HentaiVox (https://hentaivox.com) — doujinshi gallery site (same template
 * family as HentaiForce).
 *
 *     Latest   : /            (a.gallery-thumb cards; pagination /page/<n> via next link)
 *     Search   : /search?q=<query>
 *     Detail   : /view/<id>/  (h1 title, cover, page thumbs a1.hentaivox.com/i/images/<id>-<n>t.jpg)
 *     Pages    : https://a1.hentaivox.com/i/images/<id>-<n>.jpg   (strip the trailing 't')
 */
class HentaiVox : HttpSource() {

    override val name = "HentaiVox"
    override val baseUrl = "https://hentaivox.com"
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
        val url = "$baseUrl/search?q=${URLEncoder.encode(query, "utf-8")}"
        return GET(nextUrl ?: url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseList(response)

    private fun parseList(response: Response): MangasPage {
        val doc = parseDoc(response)
        val mangas = doc.select("a.gallery-thumb").mapNotNull { link ->
            val title = link.parent()?.selectFirst("h2 a")?.text()?.trim()
                ?: link.parent()?.selectFirst(".gallery-name a")?.text()?.trim()
                ?: return@mapNotNull null
            if (title.isBlank()) return@mapNotNull null
            SManga.create().apply {
                this.title = title
                setUrlWithoutDomain(link.absUrl("href"))
                thumbnail_url = link.selectFirst("img")?.attr("data-src").orEmpty()
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
            thumbnail_url = doc.selectFirst("img[data-src$='-cover.jpg']")?.attr("data-src").orEmpty()
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
        // All page thumbs are rendered server-side as <id>-<n>t.jpg — full strips the 't'.
        return doc.select("img[data-src$='t.jpg']").mapIndexed { i, img ->
            val thumb = img.attr("data-src")
            val full = thumb.removeSuffix("t.jpg") + ".jpg"
            Page(i, imageUrl = full)
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================= Utilities =============================

    private fun parseDoc(response: Response): Document =
        Jsoup.parse(response.body?.string() ?: throw IOException("Empty response body"))

    private fun nextPageUrl(doc: Document): String? {
        doc.selectFirst("a[rel='next']")?.let { return it.absUrl("href") }
        val links = doc.select("ul.pagination a.page-link")
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
