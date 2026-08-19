package eu.kanade.tachiyomi.extension.en.mangasushi

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

/*
 * Mangasushi (https://mangasushi.org) — WordPress WP-Manga reader.
 * Chapter list loads through the theme's ajax/chapters/ POST endpoint.
 *
 *     Popular : /manga/?m_orderby=views
 *     Latest  : /manga/?m_orderby=latest
 *     Search  : /?s=<query>&post_type=wp-manga
 *     Detail  : /manga/<slug>/
 *     Chapters: POST <detail_url>ajax/chapters/  -> <li class="wp-manga-chapter"><a href="/manga/<slug>/chapter-N/">
 *     Pages   : reader div.reading-content embeds <img src="https://mangasushi.org/wp-content/uploads/WP-manga/data/...">
 */
class Mangasushi : HttpSource() {

    override val name = "Mangasushi"
    override val baseUrl = "https://mangasushi.org"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", BROWSER_UA)
        .set("Referer", "$baseUrl/")

    // =========================== Browse & Search =========================

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/manga/?m_orderby=views", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/manga/?m_orderby=latest", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseList(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/?s=${URLEncoder.encode(query, "utf-8")}&post_type=wp-manga", headers)

    override fun searchMangaParse(response: Response): MangasPage = parseList(response)

    override fun getFilterList(): FilterList = FilterList()

    private fun parseList(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body?.string() ?: "", response.request.url.toString())
        val items = doc.select(".page-item-detail a, .c-tabs-item a, a[href*='/manga/']")
        val mangas = items.mapNotNull { element ->
            val url = element.attr("href")
            if (!url.contains("/manga/") || url.contains("/chapter")) return@mapNotNull null
            val img = element.selectFirst("img")
            SManga.create().apply {
                this.url = url.substringAfter(baseUrl)
                title = element.text().ifBlank { img?.attr("alt") }?.ifBlank { url.trimEnd('/').substringAfterLast('/') }.orEmpty()
                thumbnail_url = img?.httpImageUrl()
            }
        }.distinctBy { it.url }
        return MangasPage(mangas, false)
    }

    // =========================== Manga Details ===========================

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET("$baseUrl${manga.url}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body?.string() ?: "", response.request.url.toString())
        return SManga.create().apply {
            url = response.request.url.toString().substringAfter(baseUrl)
            title = doc.selectFirst("h1")?.text() ?: ""
            thumbnail_url = doc.selectFirst(".summary_image img, .tab-summary img, meta[property='og:image']")
                ?.httpImageUrl() ?: doc.selectFirst("meta[property='og:image']")?.attr("content")?.toHttpUrl()
            description = doc.selectFirst(".summary__content p, .description-summary p")?.text() ?: ""
            genre = doc.select(".genres-content a, a[href*='/genre/']").joinToString { it.text() }
            status = when {
                doc.text().contains("Ongoing", true) -> SManga.ONGOING
                doc.text().contains("Completed", true) -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    // ============================== Chapters =============================

    override fun chapterListRequest(manga: SManga): Request {
        // The theme POSTs to <detail_url>ajax/chapters/ to get the chapter list HTML
        val detail = manga.url.trimEnd('/') + "/"
        val form = FormBody.Builder().build()
        return Request.Builder()
            .url("$baseUrl$detail" + "ajax/chapters/")
            .post(form)
            .headers(headers)
            .build()
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = Jsoup.parse(response.body?.string() ?: "", response.request.url.toString())
        return doc.select("li.wp-manga-chapter a").mapNotNull { element ->
            val url = element.attr("href")
            if (!url.contains("/chapter")) return@mapNotNull null
            SChapter.create().apply {
                this.url = url.substringAfter(baseUrl)
                name = element.text().ifBlank { url.trimEnd('/').substringAfterLast('/') }
                val num = Regex("""chapter-(\d+(?:\.\d+)?)""").find(url)?.groupValues?.get(1)
                chapter_number = num?.toFloatOrNull() ?: 0F
            }
        }.distinctBy { it.url }.sortedByDescending { it.chapter_number }
    }

    // =============================== Pages ===============================

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    override fun pageListRequest(chapter: SChapter): Request =
        GET("$baseUrl${chapter.url}", headers)

    override fun pageListParse(response: Response): List<Page> {
        val doc = Jsoup.parse(response.body?.string() ?: "", response.request.url.toString())
        val urls = doc.select("img.wp-manga-chapter-img, .reading-content img, div.reading-content img").mapNotNull { it.httpImageUrl() }
        return urls.mapIndexed { index, url -> Page(index, response.request.url.toString(), url) }
    }

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl!!, headers)

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================= Utilities =============================

    private fun Element.httpImageUrl(): String? =
        attr("abs:data-src").ifEmpty { attr("abs:src") }.toHttpUrl()

    private fun String.toHttpUrl(): String? {
        val raw = trim()
        return raw.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
