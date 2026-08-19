package eu.kanade.tachiyomi.extension.en.linkmanga

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
import java.net.URLEncoder

/*
 * Linkmanga (https://linkmanga.com) — WordPress WP-Manga reader.
 *
 *     Popular : /?m_orderby=views
 *     Latest  : /?m_orderby=latest
 *     Search  : /?s=<query>&post_type=wp-manga
 *     Detail  : /manga/<slug>/
 *     Chapters: /manga/<slug>/ch-N/
 *     Pages   : reader div.reading-content embeds <img src="https://linkmanga.com/wp-content/uploads/WP-manga/data/...">
 */
class Linkmanga : HttpSource() {

    override val name = "Linkmanga"
    override val baseUrl = "https://linkmanga.com"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", BROWSER_UA)
        // Full browser header set: the site sits behind Cloudflare which challenges plain
        // OkHttp requests (Mihon shows "Failed to bypass Cloudflare"), while real browsers
        // load it fine. Mimic a browser document request so the edge lets us through.
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
        .set("Accept-Language", "en-US,en;q=0.9")
        .set("Upgrade-Insecure-Requests", "1")
        .set("Referer", "$baseUrl/")

    // =========================== Browse & Search =========================

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/?m_orderby=views", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/?m_orderby=latest", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseList(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/?s=${URLEncoder.encode(query, "utf-8")}&post_type=wp-manga", headers)

    override fun searchMangaParse(response: Response): MangasPage = parseList(response)

    override fun getFilterList(): FilterList = FilterList()

    private fun parseList(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body?.string() ?: "", response.request.url.toString())
        // The flat anchor selector grabbed tab links ("Manga List"), the cover <a>
        // (empty text -> img alt like "52381") AND the real title <a>; distinctBy kept
        // the wrong entries. Scope to cards instead:
        // browse uses .page-item-detail, search results use .c-tabs-item__content.
        val mangas = doc.select(".page-item-detail, .c-tabs-item__content").mapNotNull { card ->
            val link = card.selectFirst(".item-summary .post-title a, .tab-summary .post-title a")
                ?: return@mapNotNull null
            val url = link.attr("href")
            if (!url.contains("/manga/") || url.contains("/chapter") || url.contains("/ch-")) return@mapNotNull null
            val img = card.selectFirst(".item-thumb img, .tab-thumb img")
            SManga.create().apply {
                this.url = url.substringAfter(baseUrl)
                title = link.text().ifBlank { img?.attr("alt") }?.ifBlank { url.trimEnd('/').substringAfterLast('/') }.orEmpty()
                thumbnail_url = img?.attr("abs:src") ?: img?.attr("abs:data-src")
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
                ?.attr("abs:src") ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
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

    override fun chapterListRequest(manga: SManga): Request =
        GET("$baseUrl${manga.url}", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = Jsoup.parse(response.body?.string() ?: "", response.request.url.toString())
        return doc.select("li.wp-manga-chapter a, .wp-manga-chapter-list a").mapNotNull { element ->
            val url = element.attr("href")
            if (!url.contains("/ch-")) return@mapNotNull null
            SChapter.create().apply {
                this.url = url.substringAfter(baseUrl)
                name = element.text().ifBlank { url.trimEnd('/').substringAfterLast('/') }
                val num = Regex("""ch-(\d+(?:\.\d+)?)""").find(url)?.groupValues?.get(1)
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
        val urls = doc.select(".reading-content img, div.reading-content img").mapNotNull { img ->
            img.attr("abs:src").ifBlank { img.attr("abs:data-src") }.ifBlank { null }
        }
        return urls.mapIndexed { index, url -> Page(index, response.request.url.toString(), url) }
    }

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl!!, headers)

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
