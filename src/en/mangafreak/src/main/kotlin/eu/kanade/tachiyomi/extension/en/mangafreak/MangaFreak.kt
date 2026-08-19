package eu.kanade.tachiyomi.extension.en.mangafreak

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
import java.net.URLEncoder

/*
 * MangaFreak (https://mangafreak.me)
 *
 *     Popular : /Mangalist/All/<page>   (a[href^="/Manga/"], cover img mini_images/<slug>/100x140)
 *     Latest  : /Latest_Releases        (recent chapter rows)
 *     Search  : /Find/<query>           (a[href^="/Manga/"])
 *     Detail  : /Manga/<Title_Slug>     (h1, cover images.mangafreak.me/manga_images/<slug>.jpg)
 *     Chapters: /Read1_<Title_Slug>_<n> (chapter links)
 *     Pages   : reader embeds <img id="gohere" src="https://images.mangafreak.me/mangas/<slug>/<slug>_<n>/<slug>_<n>_<p>.jpg">
 */
class MangaFreak : HttpSource() {

    override val name = "MangaFreak"
    override val baseUrl = "https://mangafreak.me"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", BROWSER_UA)
        .set("Referer", "$baseUrl/")

    // =========================== Browse & Search =========================

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/Mangalist/All/$page", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/Latest_Releases${if (page > 1) "/$page" else ""}", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseList(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/Find/${URLEncoder.encode(query, "utf-8")}", headers)

    override fun searchMangaParse(response: Response): MangasPage = parseList(response)

    override fun getFilterList(): FilterList = FilterList()

    private fun parseList(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body?.string() ?: "", response.request.url.toString())
        val items = doc.select("a[href^='/Manga/']")
        val mangas = items.mapNotNull { element ->
            val url = element.attr("href")
            if (url == "/Manga/" || element.text().isBlank()) return@mapNotNull null
            val card = element.selectFirst("img")
            val slug = url.substringAfterLast('/')
            SManga.create().apply {
                this.url = url
                title = element.text().ifBlank { slug.replace('_', ' ') }
                thumbnail_url = card?.attr("abs:src")
                    ?: "https://images.mangafreak.me/manga_images/${slug.lowercase()}.jpg"
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
            thumbnail_url = doc.selectFirst("img[src*='manga_images']")?.attr("abs:src")
                ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
            description = doc.selectFirst(".genre_info, .desc, .description")?.text() ?: ""
            genre = doc.select("a[href*='/Genre/']").joinToString { it.text() }
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
        return doc.select("a[href^='/Read1_']").mapNotNull { element ->
            val url = element.attr("href")
            val num = url.substringAfterLast('_').toFloatOrNull() ?: return@mapNotNull null
            SChapter.create().apply {
                this.url = url
                name = "Chapter $num"
                chapter_number = num
            }
        }.distinctBy { it.url }.sortedByDescending { it.chapter_number }
    }

    // =============================== Pages ===============================

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    override fun pageListRequest(chapter: SChapter): Request =
        GET("$baseUrl${chapter.url}", headers)

    override fun pageListParse(response: Response): List<Page> {
        val doc = Jsoup.parse(response.body?.string() ?: "", response.request.url.toString())
        val urls = doc.select("img[id=gohere], img[src*='mangafreak.me/mangas/']").mapNotNull { img ->
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
