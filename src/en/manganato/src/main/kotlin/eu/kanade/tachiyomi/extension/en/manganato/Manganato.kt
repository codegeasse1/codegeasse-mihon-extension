package eu.kanade.tachiyomi.extension.en.manganato

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
 * Manganato (https://www.manganato.gg)
 *
 *     Popular : /            (homepage .content-homepage-item cards, ?page=N)
 *     Search  : /search/story/<query>  (Cloudflare-protected; may fail -> falls back to homepage)
 *     Detail  : /manga/<slug>          (h1, cover img-r2.2xstorage.com/thumb/<slug>.webp)
 *     Chapters: /manga/<slug>/chapter-N
 *     Pages   : reader div.container-chapter-reader img[src] = https://img-r1.2xstorage.com/<slug>/<chapter_id>/<n>.webp
 *               chapter_id comes from window.chapter_data JSON on the reader page.
 */
class Manganato : HttpSource() {

    override val name = "Manganato"
    override val baseUrl = "https://www.manganato.gg"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", BROWSER_UA)
        .set("Referer", "$baseUrl/")

    // =========================== Browse & Search =========================

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response): MangasPage = parseList(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/search/story/${URLEncoder.encode(query, "utf-8")}", headers)

    override fun searchMangaParse(response: Response): MangasPage = parseList(response)

    override fun getFilterList(): FilterList = FilterList()

    private fun parseList(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body?.string() ?: "", response.request.url.toString())
        val items = doc.select(".content-homepage-item a.item-title, .search-story-item a.item-title, .panel-search-story .search-story-item a")
        val mangas = items.mapNotNull { element ->
            val url = element.attr("href")
            if (!url.contains("/manga/")) return@mapNotNull null
            val card = element.parent()
            SManga.create().apply {
                this.url = url.substringAfter(baseUrl).ifEmpty { url.substringAfter("://") }
                title = element.text().ifBlank { url.substringAfterLast('/') }
                thumbnail_url = card?.selectFirst("img")?.attr("abs:src") ?: card?.selectFirst("img")?.attr("abs:data-src")
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
            thumbnail_url = doc.selectFirst(".story-info-right img, .info-image img, meta[property='og:image']")
                ?.attr("abs:src") ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
            description = doc.selectFirst("#panel-story-description, .panel-story-info-description, .story-detail p")?.text() ?: ""
            genre = doc.select(".story-info-right .a-h, a[href*='/genre/']").joinToString { it.text() }
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
        return doc.select("ul.row-content-chapter a.chapter-name, a.chapter-name").mapNotNull { element ->
            val url = element.attr("href")
            if (!url.contains("/chapter-")) return@mapNotNull null
            SChapter.create().apply {
                this.url = url.substringAfter(baseUrl).ifEmpty { url.substringAfter("://") }
                name = element.text().ifBlank { url.substringAfterLast('/') }
                val num = Regex("""chapter-([\d.]+)""").find(url)?.groupValues?.get(1)
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
        val urls = doc.select("div.container-chapter-reader img[src]").mapNotNull { img ->
            img.attr("abs:src").ifBlank { null }
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
