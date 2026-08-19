package eu.kanade.tachiyomi.extension.en.mangatown

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
 * MangaTown (https://mangatown.com)
 *
 *     Popular : /hot/ (page N -> /hot/N.htm)
 *     Latest  : /latest/
 *     Search  : /search?title=<query>
 *     Detail  : /manga/<slug>/           (h1, cover fmcdn.mangahere.com/store/manga/<id>/ocover.jpg)
 *     Chapters: /manga/<slug>/cNNN/      (chapter links, sorted newest-first)
 *     Pages   : /manga/<slug>/cNNN/1.html embeds <img class="image" src="//zjcdn.mangahere.org/store/manga/<id>/<NNN.0>/compressed/jNNN.jpg">
 */
class MangaTown : HttpSource() {

    override val name = "MangaTown"
    override val baseUrl = "https://www.mangatown.com"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", BROWSER_UA)
        .set("Referer", "$baseUrl/")

    // =========================== Browse & Search =========================

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/hot/${if (page > 1) "$page.htm" else ""}", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/latest/${if (page > 1) "$page.htm" else ""}", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseList(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/search?title=${URLEncoder.encode(query, "utf-8")}", headers)

    override fun searchMangaParse(response: Response): MangasPage = parseList(response)

    override fun getFilterList(): FilterList = FilterList()

    private fun parseList(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body?.string() ?: "", response.request.url.toString())
        val items = doc.select("p.title a[href^='/manga/']")
        val mangas = items.mapNotNull { element ->
            val url = element.attr("href")
            val card = element.parent()?.parent()
            val img = card?.selectFirst("img")
            SManga.create().apply {
                this.url = url
                title = element.text().ifBlank { url.trimEnd('/').substringAfterLast('/') }
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
            url = doc.selectFirst("link[rel=canonical]")?.attr("href")
                ?.substringAfter(baseUrl) ?: response.request.url.toString().substringAfter(baseUrl)
            title = doc.selectFirst("h1, .detail-info .manga-title")?.text() ?: ""
            thumbnail_url = doc.selectFirst("img.detail-info-cover, .manga_detail img[src*='store/manga'], meta[property='og:image']")
                ?.attr("abs:src") ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
            description = doc.selectFirst(".manga_description, #showmore .content")?.text() ?: ""
            genre = doc.select(".detail-info-list a[href*='/list/']").joinToString { it.text() }
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
        return doc.select("a[href*='/c'][href^='/manga/']").mapNotNull { element ->
            val url = element.attr("href")
            val match = Regex("""/c(\d+(?:\.\d+)?)/?$""").find(url.trimEnd('/')) ?: return@mapNotNull null
            SChapter.create().apply {
                this.url = url
                name = element.text().ifBlank { "Chapter ${match.groupValues[1]}" }
                chapter_number = match.groupValues[1].toFloatOrNull() ?: 0F
            }
        }.distinctBy { it.url }.sortedByDescending { it.chapter_number }
    }

    // =============================== Pages ===============================

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    override fun pageListRequest(chapter: SChapter): Request {
        val first = chapter.url.trimEnd('/') + "/1.html"
        return GET("$baseUrl$first", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val doc = Jsoup.parse(response.body?.string() ?: "", response.request.url.toString())
        val urls = doc.select("img.image[src*='mangahere.org']").mapNotNull { img ->
            val src = img.attr("abs:src")
            if (src.isBlank()) null else src.removePrefix("https:").let { if (it.startsWith("//")) "https:$it" else it }
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
