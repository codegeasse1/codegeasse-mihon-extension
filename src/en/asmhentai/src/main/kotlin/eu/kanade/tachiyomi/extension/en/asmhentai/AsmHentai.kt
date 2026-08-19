package eu.kanade.tachiyomi.extension.en.asmhentai

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
 * AsmHentai (https://asmhentai.com) — a nhentai-style doujinshi gallery.
 *
 *     Latest   : /            (div.preview_item cards, ?page=N)
 *     Search   : /search/?q=<query>
 *     Detail   : /g/<id>/     (h1 title, div.cover img[data-src], #load_dir/#load_id/#t_pages)
 *     Pages    : https://images.asmhentai.com/<load_dir>/<load_id>/<n>.jpg
 */
class AsmHentai : HttpSource() {

    override val name = "AsmHentai"
    override val baseUrl = "https://asmhentai.com"
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
        val mangas = doc.select("div.preview_item").mapNotNull { item ->
            val link = item.selectFirst("a[href*='/g/']") ?: return@mapNotNull null
            val title = item.selectFirst("h2.caption")?.text()?.trim() ?: return@mapNotNull null
            // The first <img> in a card is the tiny "flag" icon (plain src), and the
            // actual cover is the lazy-loaded one carrying a data-src, so target that.
            val coverImg = item.selectFirst("img[data-src]") ?: item.selectFirst("img")
            val thumb = coverImg?.attr("data-src")
                ?.takeIf { it.isNotBlank() }
                ?: coverImg?.absUrl("src").orEmpty()
            if (title.isBlank()) return@mapNotNull null
            SManga.create().apply {
                this.title = title
                setUrlWithoutDomain(link.absUrl("href"))
                thumbnail_url = fixScheme(thumb)
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
            thumbnail_url = fixScheme(doc.selectFirst("div.cover img")?.attr("data-src").orEmpty())
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
        val pages = doc.selectFirst("#t_pages")?.attr("value")?.toIntOrNull() ?: 0
        if (dir.isEmpty() || id.isEmpty() || pages <= 0) return emptyList()
        return (1..pages).map { n ->
            Page(n - 1, imageUrl = "https://images.asmhentai.com/$dir/$id/$n.jpg")
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================= Utilities =============================

    private fun parseDoc(response: Response): Document =
        Jsoup.parse(
            response.body?.string() ?: throw IOException("Empty response body"),
            response.request.url.toString(),
        )

    private fun fixScheme(url: String): String =
        if (url.startsWith("//")) "https:$url" else url

    private fun nextPageUrl(doc: Document): String? {
        val next = doc.select("ul.pagination a.page-link").firstOrNull { it.text().trim().startsWith("Next") }
            ?: doc.select("a[rel='next']").firstOrNull()
            ?: return null
        val href = next.attr("href")
        if (href.isEmpty() || href == "#") return null
        return next.absUrl("href")
    }

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
