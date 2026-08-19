package eu.kanade.tachiyomi.extension.en.manganato

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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/*
 * Manganato (https://www.manganato.gg)
 *
 *     Popular : /?page=N            (homepage cards; manga links a[href*='/manga/'], cover = thumb/<slug>.webp)
 *     Search  : /search/story/<q>   (same card markup)
 *     Detail  : /manga/<slug>       (h1, cover 2xstorage.com/thumb/<slug>.webp)
 *     Chapters: GET /api/manga/<slug>/chapters  -> {"data":{"chapters":[{chapter_name,chapter_slug,chapter_num,...}]}}
 *               chapter url = /manga/<slug>/<chapter_slug>
 *     Pages   : reader div.container-chapter-reader img[src] = https://img-r1.2xstorage.com/<slug>/<chapterId>/<n>.webp
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
        val mangas = doc.select("a[href*='/manga/']").mapNotNull { element ->
            val url = element.attr("href")
            val slug = url.trimEnd('/').substringAfterLast('/')
            if (!url.contains("/manga/") || url.contains("/chapter-") || slug.isEmpty()) return@mapNotNull null
            val img = element.selectFirst("img")
            SManga.create().apply {
                this.url = url.substringAfter(baseUrl).ifEmpty { url.substringAfter("://") }
                title = element.attr("title").ifBlank { element.text() }
                    .ifBlank { img?.attr("alt") }?.ifBlank { slug }.orEmpty()
                thumbnail_url = img?.httpImageUrl()
                    ?: "https://img-r2.2xstorage.com/thumb/$slug.webp"
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
            thumbnail_url = doc.selectFirst("img[src*='2xstorage.com'], .info-image img, meta[property='og:image']")
                ?.httpImageUrl() ?: doc.selectFirst("meta[property='og:image']")?.attr("content")?.toHttpUrl()
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

    override fun chapterListRequest(manga: SManga): Request {
        val slug = manga.url.trimEnd('/').substringAfterLast('/')
        return GET("$baseUrl/api/manga/$slug/chapters", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val json = runCatching {
            JsonParser.parseString(response.body?.string() ?: "").asJsonObject
        }.getOrNull() ?: return emptyList()
        val slug = response.request.url.toString().substringBefore("/chapters")
            .substringAfterLast('/')
        val chapters = json.getAsJsonObject("data")?.get("chapters")
            ?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return chapters.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val obj = element.asJsonObject
            val chapterSlug = obj.get("chapter_slug")?.asString ?: return@mapNotNull null
            val num = obj.get("chapter_num")?.takeIf { it.isJsonPrimitive }?.asString
                ?: Regex("""(\d+(?:\.\d+)?)""").find(chapterSlug)?.groupValues?.get(1)
                ?: return@mapNotNull null
            SChapter.create().apply {
                url = "/manga/$slug/$chapterSlug"
                name = obj.get("chapter_name")?.takeIf { it.isJsonPrimitive }?.asString
                    ?.ifBlank { "Chapter $num" } ?: "Chapter $num"
                chapter_number = num.toFloatOrNull() ?: 0F
            }
        }.sortedByDescending { it.chapter_number }
    }

    // =============================== Pages ===============================

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    override fun pageListRequest(chapter: SChapter): Request =
        GET("$baseUrl${chapter.url}", headers)

    override fun pageListParse(response: Response): List<Page> {
        val doc = Jsoup.parse(response.body?.string() ?: "", response.request.url.toString())
        val urls = doc.select("div.container-chapter-reader img[src]").mapNotNull { it.httpImageUrl() }
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
