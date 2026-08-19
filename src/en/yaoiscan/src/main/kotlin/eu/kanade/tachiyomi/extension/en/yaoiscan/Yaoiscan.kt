package eu.kanade.tachiyomi.extension.en.yaoiscan

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
 * Yaoiscan (https://yaoiscan.com) — WordPress (Madara) reader.
 *
 *     Popular : /manga/?m_orderby=views
 *     Latest  : /manga/?m_orderby=latest
 *     Search  : /?s=<query>&post_type=wp-manga
 *     Detail  : /read/<slug>/          (h1, cover wp-content/uploads/...)
 *     Chapters: /read/<slug>/chapter-N/
 *     Pages   : reader page embeds <img data-src="https://img6.imgyaoiscan.cc/<slug>-<mangaId>/chapter-N/NNN.webp">
 */
class Yaoiscan : HttpSource() {

    override val name = "Yaoiscan"
    override val baseUrl = "https://yaoiscan.com"
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
        val doc = Jsoup.parse(response.body.string(), response.request.url.toString())
        val items = doc.select("a[href*='/read/']")
        val mangas = items.mapNotNull { element ->
            val url = element.attr("href")
            val slug = url.trimEnd('/').substringAfterLast('/')
            if (!url.contains("/read/") || url.contains("/chapter") || slug.isEmpty()) return@mapNotNull null
            val img = element.selectFirst("img")
            SManga.create().apply {
                this.url = url.substringAfter(baseUrl)
                title = element.text().ifBlank { img?.attr("alt") }?.ifBlank { slug }.orEmpty()
                thumbnail_url = img?.attr("abs:src") ?: img?.attr("abs:data-src")
            }
        }.distinctBy { it.url }
        return MangasPage(mangas, false)
    }

    // =========================== Manga Details ===========================

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET("$baseUrl${manga.url}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body.string(), response.request.url.toString())
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
        val doc = Jsoup.parse(response.body.string(), response.request.url.toString())
        return doc.select("li.wp-manga-chapter a, .wp-manga-chapter-list a, a[href*='/chapter-']").mapNotNull { element ->
            val url = element.attr("href")
            if (!url.contains("/chapter-")) return@mapNotNull null
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
        val doc = Jsoup.parse(response.body.string(), response.request.url.toString())
        val urls = doc.select("img.wp-manga-chapter-img").mapNotNull { img ->
            img.attr("abs:data-src").ifBlank { img.attr("abs:src") }.ifBlank { null }
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
