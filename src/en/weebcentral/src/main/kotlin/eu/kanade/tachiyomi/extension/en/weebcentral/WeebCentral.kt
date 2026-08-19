package eu.kanade.tachiyomi.extension.en.weebcentral

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
 * WeebCentral (https://weebcentral.com) — Hyread/Squirrelfaucet.
 *
 *     Popular : /series?order=views        (series cards)
 *     Latest  : /updates                    (recent chapters)
 *     Search  : /search?query=<q>
 *     Detail  : /series/<seriesId>/<slug>   (h1, cover temp.compsci88.com/cover/fallback/<id>.jpg)
 *     Chapters: /chapters/<chapterId>       (chapter rows)
 *     Pages   : reader page has <link rel="preload" href="https://scans-hot.planeptune.us/manga/<slug>/<NNNN>-001.png">
 *               and  max_page: parseInt('19')  -> images are .../<NNNN>-<PPP>.png for p in 1..max_page
 */
class WeebCentral : HttpSource() {

    override val name = "Weeb Central"
    override val baseUrl = "https://weebcentral.com"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", BROWSER_UA)
        .set("Referer", "$baseUrl/")

    // =========================== Browse & Search =========================

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/series?page=$page&order=views", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/updates?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseList(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/search?query=${URLEncoder.encode(query, "utf-8")}", headers)

    override fun searchMangaParse(response: Response): MangasPage = parseList(response)

    override fun getFilterList(): FilterList = FilterList()

    private fun parseList(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body?.string() ?: "", response.request.url.toString())
        val items = doc.select("a[href^='/series/']")
        val mangas = items.mapNotNull { element ->
            val url = element.attr("href")
            val slug = url.substringAfterLast('/')
            if (!url.startsWith("/series/") || url.endsWith("/rss") || url.endsWith("/subscribe")) return@mapNotNull null
            val img = element.selectFirst("img")
            SManga.create().apply {
                this.url = url
                title = element.text().ifBlank { img?.attr("alt") }?.ifBlank { slug.replace('-', ' ') }.orEmpty()
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
            thumbnail_url = doc.selectFirst("img[src*='cover'], img[src*='compsci88'], meta[property='og:image']")
                ?.attr("abs:src") ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
            description = doc.selectFirst("[itemprop=description], #series_description, .prose")?.text() ?: ""
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
        val doc = Jsoup.parse(response.body?.string() ?: "", response.request.url.toString())
        return doc.select("a[href^='/chapters/']").mapNotNull { element ->
            val url = element.attr("href")
            if (!Regex("""/chapters/[A-Za-z0-9]+$""").containsMatchIn(url.trimEnd('/'))) return@mapNotNull null
            SChapter.create().apply {
                this.url = url
                name = element.text().ifBlank { "Chapter" }
                val num = Regex("""#?\s*([\d.]+)""").find(name)?.groupValues?.get(1)
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
        val preload = doc.selectFirst("link[rel=preload][href*='planeptune.us']")?.attr("abs:href") ?: return emptyList()
        val baseMatch = Regex("""^(https://[^/]+/manga/[^/]+/\d{3,4})-\d{3}\.png$""").find(preload)
            ?: return emptyList()
        val base = baseMatch.groupValues[1]
        val maxPage = doc.select("script").mapNotNull {
            Regex("""max_page\s*:\s*parseInt\('(\d+)'\)""").find(it.html())?.groupValues?.get(1)?.toIntOrNull()
        }.firstOrNull() ?: return emptyList()

        return (1..maxPage).map { pageNum ->
            val url = "$base-${pageNum.toString().padStart(3, '0')}.png"
            Page(pageNum - 1, response.request.url.toString(), url)
        }
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
