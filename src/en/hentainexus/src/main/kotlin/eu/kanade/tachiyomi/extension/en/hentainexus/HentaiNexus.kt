package eu.kanade.tachiyomi.extension.en.hentainexus

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
 * HentaiNexus (https://hentainexus.com) — English hentai publisher/aggregator.
 *
 *     Latest   : /            (a[href^='/view/'] > div.card, pagination /page/<n>)
 *     Search   : /?q=<query>  (pagination /page/<n>?q=<query>)
 *     Detail   : /view/<id>   (h1.title, all pages as img[src$=".thumb.jpg"])
 *     Pages    : full URL = thumb src with ".thumb.jpg" suffix removed
 */
class HentaiNexus : HttpSource() {

    override val name = "HentaiNexus"
    override val baseUrl = "https://hentainexus.com"
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
        val url = "$baseUrl/?q=${URLEncoder.encode(query, "utf-8")}"
        return GET(nextUrl ?: url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseList(response)

    private fun parseList(response: Response): MangasPage {
        val doc = parseDoc(response)
        val mangas = doc.select("a[href^='/view/']").mapNotNull { link ->
            val card = link.selectFirst("div.card") ?: return@mapNotNull null
            val title = card.selectFirst("header.card-header")?.attr("title")?.trim()
                ?: card.selectFirst("p.card-header-title")?.text()?.trim()
                ?: return@mapNotNull null
            if (title.isBlank()) return@mapNotNull null
            SManga.create().apply {
                this.title = title
                setUrlWithoutDomain(link.absUrl("href"))
                thumbnail_url = card.selectFirst("img")?.attr("src").orEmpty()
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
            title = doc.selectFirst("h1.title")?.text()?.trim().orEmpty()
            thumbnail_url = doc.selectFirst("img[src$='.thumb.jpg']")?.attr("src").orEmpty()
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
        val pages = doc.select("div.columns.is-multiline.is-mobile img[src$='.thumb.jpg']")
        return pages.mapIndexed { i, img ->
            Page(i, imageUrl = img.attr("src").removeSuffix(".thumb.jpg"))
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================= Utilities =============================

    private fun parseDoc(response: Response): Document =
        Jsoup.parse(
            response.body?.string() ?: throw IOException("Empty response body"),
            response.request.url.toString(),
        )

    private fun nextPageUrl(doc: Document): String? {
        val next = doc.selectFirst("a.pagination-next") ?: return null
        val style = next.attr("style")
        if (style.contains("hidden", ignoreCase = true)) return null
        return next.absUrl("href")
    }

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
