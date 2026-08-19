package eu.kanade.tachiyomi.extension.en.mgeko

import com.google.gson.JsonParser
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
import org.jsoup.nodes.Element
import java.net.URLEncoder

/*
 * Mgeko (https://www.mgeko.cc)
 *
 *     Popular : /browse-comics/data/?page=N            (JSON: {results_html, page, num_pages})
 *     Latest  : /browse-comics/data/?page=N&sort=recently_added
 *     Search  : /browse-comics/data/?page=N&q=<query>
 *               results_html = <article class="comic-card"> <a href="/manga/<slug>/"> <img src="https://imgsrv5.com/...">
 *     Detail  : /manga/<slug>/          (og:image cover, chapters li.chapter-list-item a[href*='/reader/en/'])
 *     Chapters: /reader/en/<slug>-chapter-N-eng-li/
 *     Pages   : reader page embeds <img src="https://imgsrv5.com/sv2/comic/<slug>/chapter-N/<p>.jpg">
 */
class Mgeko : HttpSource() {

    override val name = "Mgeko"
    override val baseUrl = "https://www.mgeko.cc"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", BROWSER_UA)
        .set("Referer", "$baseUrl/")

    // =========================== Browse & Search =========================

    private fun dataUrl(page: Int, sort: String? = null, query: String? = null): String =
        buildString {
            append("$baseUrl/browse-comics/data/?page=$page")
            if (sort != null) append("&sort=$sort")
            if (query != null) append("&q=${URLEncoder.encode(query, "utf-8")}")
        }

    override fun popularMangaRequest(page: Int): Request =
        GET(dataUrl(page), headers)

    override fun popularMangaParse(response: Response): MangasPage = parseJson(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET(dataUrl(page, "recently_added"), headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseJson(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET(dataUrl(page, query = query), headers)

    override fun searchMangaParse(response: Response): MangasPage = parseJson(response)

    override fun getFilterList(): FilterList = FilterList()

    private fun parseJson(response: Response): MangasPage {
        val body = response.body?.string() ?: return MangasPage(emptyList(), false)
        val json = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
            ?: return MangasPage(emptyList(), false)
        val html = json.get("results_html")?.takeIf { it.isJsonPrimitive }?.asString
            ?: return MangasPage(emptyList(), false)
        val doc = Jsoup.parse(html, response.request.url.toString())
        val mangas = doc.select("article.comic-card a[href*='/manga/']").mapNotNull { element ->
            val url = element.attr("href")
            val slug = url.trimEnd('/').substringAfterLast('/')
            if (!url.contains("/manga/") || slug.isEmpty()) return@mapNotNull null
            val img = element.selectFirst("img")
            SManga.create().apply {
                this.url = url.substringAfter(baseUrl)
                title = element.text().ifBlank { img?.attr("alt") }?.ifBlank { slug }.orEmpty()
                thumbnail_url = img?.httpImageUrl()
            }
        }.distinctBy { it.url }
        val page = json.get("page")?.takeIf { it.isJsonPrimitive }?.asInt ?: 1
        val numPages = json.get("num_pages")?.takeIf { it.isJsonPrimitive }?.asInt ?: 1
        return MangasPage(mangas, page < numPages)
    }

    // =========================== Manga Details ===========================

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET("$baseUrl${manga.url}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body?.string() ?: "", response.request.url.toString())
        return SManga.create().apply {
            url = response.request.url.toString().substringAfter(baseUrl)
            title = doc.selectFirst("h1")?.text() ?: ""
            thumbnail_url = doc.selectFirst("img[src*='manga_covers'], meta[property='og:image']")
                ?.httpImageUrl() ?: doc.selectFirst("meta[property='og:image']")?.attr("content")?.toHttpUrl()
            description = doc.selectFirst(".manga-description, [itemprop=description]")?.text() ?: ""
            genre = doc.select("a[href*='/genre/'], a[href*='/category/']").joinToString { it.text() }
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
        return doc.select("li.chapter-list-item a[href*='/reader/en/']").mapNotNull { element ->
            val url = element.attr("href")
            if (!url.contains("/reader/en/")) return@mapNotNull null
            SChapter.create().apply {
                this.url = url.substringAfter(baseUrl)
                name = element.text().ifBlank { url.trimEnd('/').substringAfterLast('/') }
                val num = Regex("""chapter-(\d+(?:\.\d+)?)-eng""").find(url)?.groupValues?.get(1)
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
        val urls = doc.select("img[src*='imgsrv5.com/sv2/comic/']").mapNotNull { it.httpImageUrl() }
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
