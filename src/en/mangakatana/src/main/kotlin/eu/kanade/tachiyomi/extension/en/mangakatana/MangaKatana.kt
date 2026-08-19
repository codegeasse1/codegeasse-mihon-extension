package eu.kanade.tachiyomi.extension.en.mangakatana

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
 * MangaKatana (https://mangakatana.com)
 *
 *     Popular : /          (homepage .manga-list or .item, ?page=N)
 *     Search  : /?search=<query>
 *     Detail  : /manga/<slug>.<id>   (h1, cover mangakatana.com/imgs/cover/...)
 *     Chapters: /manga/<slug>.<id>/cNN.M
 *     Pages   : reader page contains  var thzq=['<tokenized-url>/0.jpg', ...]
 *               each token URL works directly (verified).
 */
class MangaKatana : HttpSource() {

    override val name = "MangaKatana"
    override val baseUrl = "https://mangakatana.com"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", BROWSER_UA)
        .set("Referer", "$baseUrl/")

    // =========================== Browse & Search =========================

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/?page=$page&sort=popular", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseList(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/?search=${URLEncoder.encode(query, "utf-8")}", headers)

    override fun searchMangaParse(response: Response): MangasPage = parseList(response)

    override fun getFilterList(): FilterList = FilterList()

    private fun parseList(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string(), response.request.url.toString())
        val items = doc.select("a[href*='/manga/'][href*='.']")
        val mangas = items.mapNotNull { element ->
            val url = element.attr("href")
            if (!Regex("""/manga/[^/]+\.[\d]+$""").containsMatchIn(url.trimEnd('/'))) return@mapNotNull null
            val img = element.selectFirst("img")
            SManga.create().apply {
                this.url = url.substringAfter(baseUrl).trimEnd('/')
                title = element.text().ifBlank { img?.attr("alt") }?.ifBlank { url.substringAfterLast('/').substringBefore('.') }.orEmpty()
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
            url = response.request.url.toString().substringAfter(baseUrl).trimEnd('/')
            title = doc.selectFirst("h1")?.text() ?: ""
            thumbnail_url = doc.selectFirst("img[src*='imgs/cover'], .cover img, meta[property='og:image']")
                ?.attr("abs:src") ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
            description = doc.selectFirst(".summary p, #info p")?.text() ?: ""
            genre = doc.select("a[href*='/genres/']").joinToString { it.text() }
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
        return doc.select("table.table a[href*='/c'], .chapter a[href*='/c']").mapNotNull { element ->
            val url = element.attr("href")
            if (!Regex("""/c[\d.]+$""").containsMatchIn(url.trimEnd('/'))) return@mapNotNull null
            SChapter.create().apply {
                this.url = url.substringAfter(baseUrl).trimEnd('/')
                name = element.text().ifBlank { url.substringAfterLast('/') }
                val num = Regex("""/c([\d.]+)$""").find(url.trimEnd('/'))?.groupValues?.get(1)
                chapter_number = num?.toFloatOrNull() ?: 0F
            }
        }.distinctBy { it.url }.sortedByDescending { it.chapter_number }
    }

    // =============================== Pages ===============================

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    override fun pageListRequest(chapter: SChapter): Request =
        GET("$baseUrl${chapter.url}", headers)

    override fun pageListParse(response: Response): List<Page> {
        val body = response.body.string()
        val match = Regex("""var\s+thzq\s*=\s*\[([\s\S]*?)\]""").find(body) ?: return emptyList()
        val urls = Regex("""'([^']+)'""").findAll(match.groupValues[1]).map { it.groupValues[1] }.toList()
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
