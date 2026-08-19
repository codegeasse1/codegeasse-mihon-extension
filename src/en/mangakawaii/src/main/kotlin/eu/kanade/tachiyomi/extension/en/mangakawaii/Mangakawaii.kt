package eu.kanade.tachiyomi.extension.en.mangakawaii

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
 * Mangakawaii (https://www.mangakawaii.io) — custom Laravel reader.
 *
 *     Popular : /           (homepage cards)
 *     Search  : /search?q=<query>
 *     Detail  : /manga/<slug>            (h1, cover cdn.mangakawaii.io/uploads/manga/<slug>/cover/...)
 *     Chapters: /manga/<slug>/en/<N>     (chapter rows)
 *     Pages   : reader page defines:
 *                 var pages  = [{"page_id":..,"page_image":"01.png",...,"page_version":...}]
 *                 var oeuvre_slug  = "<slug>";
 *                 var chapter_slug = "<N>";
 *                 var applocale    = "en";
 *               image = https://cdn.mangakawaii.io/uploads/manga/<oeuvre_slug>/chapters_<applocale>/<chapter_slug>/<page_image>?<page_version>
 */
class Mangakawaii : HttpSource() {

    override val name = "Mangakawaii"
    override val baseUrl = "https://www.mangakawaii.io"
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
        GET("$baseUrl/search?q=${URLEncoder.encode(query, "utf-8")}", headers)

    override fun searchMangaParse(response: Response): MangasPage = parseList(response)

    override fun getFilterList(): FilterList = FilterList()

    private fun parseList(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string(), response.request.url.toString())
        val items = doc.select("a[href*='/manga/']")
        val mangas = items.mapNotNull { element ->
            val url = element.attr("href")
            val slug = url.trimEnd('/').substringAfterLast('/')
            if (!url.startsWith("/manga/") || slug.contains("?") || url.contains("/en/")) return@mapNotNull null
            val img = element.selectFirst("img")
            SManga.create().apply {
                this.url = url
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
            thumbnail_url = doc.selectFirst("img[src*='mangakawaii.io/uploads'], meta[property='og:image']")
                ?.attr("abs:src") ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
            description = doc.selectFirst(".manga-description, [itemprop=description], .summary")?.text() ?: ""
            genre = doc.select("a[href*='/category/'], a[href*='/genre/']").joinToString { it.text() }
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
        return doc.select("a[href*='/manga/'][href*='/en/']").mapNotNull { element ->
            val url = element.attr("href")
            val num = Regex("""/en/(\d+)$""").find(url.trimEnd('/'))?.groupValues?.get(1) ?: return@mapNotNull null
            SChapter.create().apply {
                this.url = url
                name = element.text().ifBlank { "Chapter $num" }
                chapter_number = num.toFloatOrNull() ?: 0F
            }
        }.distinctBy { it.url }.sortedByDescending { it.chapter_number }
    }

    // =============================== Pages ===============================

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    override fun pageListRequest(chapter: SChapter): Request =
        GET("$baseUrl${chapter.url}", headers)

    override fun pageListParse(response: Response): List<Page> {
        val body = response.body.string()
        val pagesMatch = Regex("""var\s+pages\s*=\s*(\[[\s\S]*?\]);""").find(body) ?: return emptyList()
        val oeuvre = Regex("""var\s+oeuvre_slug\s*=\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
            ?: return emptyList()
        val chapterSlug = Regex("""var\s+chapter_slug\s*=\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
            ?: return emptyList()
        val locale = Regex("""var\s+applocale\s*=\s*"([^"]+)"""").find(body)?.groupValues?.get(1) ?: "en"

        val imageRegex = Regex("""\{\s*"page_id":(\d+)[^}]*?"page_image":"([^"]+)"[^}]*?"page_version":(\d+)""")
        val pages = imageRegex.findAll(pagesMatch.groupValues[1]).map { m ->
            val name = m.groupValues[2]
            val version = m.groupValues[3]
            "https://cdn.mangakawaii.io/uploads/manga/$oeuvre/chapters_$locale/$chapterSlug/$name?$version"
        }.toList()
        return pages.mapIndexed { index, url -> Page(index, response.request.url.toString(), url) }
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
