package eu.kanade.tachiyomi.extension.en.mangapill

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
import org.jsoup.nodes.Element
import java.net.URLEncoder

/*
 * Mangapill (https://mangapill.com)
 *
 *     Popular : /mangas/new?page=N    (cards: a[href^="/manga/"], img[data-src], img[alt])
 *     Latest  : /chapters?page=N      (recent chapter rows linking to /manga/<id>/<slug>)
 *     Search  : /search?title=<q>     (same card markup as popular)
 *     Detail  : /manga/<id>/<slug>    (h1, cover img[data-src], chapter links a[href^="/chapters/"])
 *     Pages   : reader page embeds every image in <img class="js-page" data-src="https://cdn.readdetectiveconan.com/file/mangap/...">
 */
class Mangapill : HttpSource() {

    override val name = "Mangapill"
    override val baseUrl = "https://mangapill.com"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", BROWSER_UA)
        .set("Referer", "$baseUrl/")

    // =========================== Browse & Search =========================

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/mangas/new?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/chapters?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseList(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/search?title=${URLEncoder.encode(query, "utf-8")}", headers)

    override fun searchMangaParse(response: Response): MangasPage = parseList(response)

    override fun getFilterList(): FilterList = FilterList()

    private fun parseList(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body?.string() ?: "", response.request.url.toString())
        val mangas = doc.select("a[href*='/manga/']").mapNotNull { element ->
            val url = element.attr("href")
            val slug = url.substringAfter("/manga/", "").trimEnd('/')
            if (!url.startsWith("/manga/") || slug.isEmpty() || "/" == slug) return@mapNotNull null
            val img = element.selectFirst("img") ?: return@mapNotNull null
            val alt = img.attr("alt").trim()
            SManga.create().apply {
                this.url = url
                title = alt.ifEmpty { slug.substringAfter('/').replace('-', ' ') }
                thumbnail_url = img.httpImageUrl()
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
            thumbnail_url = doc.selectFirst("img[data-src]")?.httpImageUrl()
                ?: doc.selectFirst("meta[property='og:image']")?.attr("content")?.toHttpUrl()
            description = doc.selectFirst("main p")?.text() ?: ""
            genre = doc.select(".badge, [class*='tag']").joinToString { it.text() }
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
        return doc.select("a[href*='/chapters/']").mapNotNull { element ->
            val url = element.attr("href")
            if (!url.startsWith("/chapters/")) return@mapNotNull null
            SChapter.create().apply {
                this.url = url
                name = element.text().ifBlank { "Chapter" }
                val num = Regex("""[Cc]hapter\s+([\d.]+)""").find(name)?.groupValues?.get(1)
                    ?: Regex("""-chapter-([\d.]+)$""").find(url)?.groupValues?.get(1)
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
        val urls = doc.select("img.js-page[data-src]").mapNotNull { it.httpImageUrl() }
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
