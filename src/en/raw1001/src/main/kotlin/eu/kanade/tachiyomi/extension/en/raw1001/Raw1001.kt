package eu.kanade.tachiyomi.extension.en.raw1001

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.net.URLEncoder

/*
 * raw1001 (https://raw1001.net) — raw manga with POST-based page API.
 *
 *     Popular : /manga/?orderby=update (manga cards)
 *     Search  : /search?keyword=<query>
 *     Detail  : /manga/<slug>          (h1, cover /uploads/covers/<slug>.jpg)
 *     Chapters: /manga/<slug>/di<N>hua/<chapterId>  (di272hua etc.)
 *     Pages   : reader page defines  const CHAPTER_ID = <id>;
 *               then POST /ajax/image/list/chap/<CHAPTER_ID>
 *               -> {"status":true,"html":"<a href='https://sg.cdnkk.top/...webp' class='readImg'>"}
 */
class Raw1001 : HttpSource() {

    override val name = "raw1001"
    override val baseUrl = "https://raw1001.net"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", BROWSER_UA)
        .set("Referer", "$baseUrl/")
        .set("X-Requested-With", "XMLHttpRequest")

    // =========================== Browse & Search =========================

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/manga/?orderby=update&page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response): MangasPage = parseList(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/search?keyword=${URLEncoder.encode(query, "utf-8")}", headers)

    override fun searchMangaParse(response: Response): MangasPage = parseList(response)

    override fun getFilterList(): FilterList = FilterList()

    private fun parseList(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string(), response.request.url.toString())
        val items = doc.select("a[href*='/manga/']")
        val mangas = items.mapNotNull { element ->
            val url = element.attr("href")
            val slug = url.trimEnd('/').substringAfterLast('/')
            if (!url.contains("/manga/") || slug.isEmpty() || slug.startsWith("di")) return@mapNotNull null
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
            thumbnail_url = doc.selectFirst("img[src*='covers'], img[src*='uploads'], meta[property='og:image']")
                ?.attr("abs:src") ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
            description = doc.selectFirst(".manga-info-detail, .content, [itemprop=description]")?.text() ?: ""
            genre = doc.select("a[href*='/genre/'], a[href*='/tag/']").joinToString { it.text() }
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
        return doc.select("a[href*='/manga/'][href*='hua'], .chapter-list a, .detail-chapter-list a").mapNotNull { element ->
            val url = element.attr("href")
            if (!url.contains("hua")) return@mapNotNull null
            SChapter.create().apply {
                this.url = url.substringAfter(baseUrl)
                name = element.text().ifBlank { url.trimEnd('/').substringAfterLast('/') }
                val num = Regex("""di(\d+(?:\.\d+)?)hua""").find(url)?.groupValues?.get(1)
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
        val chapterId = Regex("""const\s+CHAPTER_ID\s*=\s*(\d+)""").find(body)?.groupValues?.get(1)
            ?: return emptyList()
        val ajaxUrl = "$baseUrl/ajax/image/list/chap/$chapterId"
        val form = FormBody.Builder().build()
        val req = Request.Builder()
            .url(ajaxUrl)
            .post(form)
            .headers(headers)
            .build()
        client.newCall(req).execute().use { ajaxResp ->
            if (!ajaxResp.isSuccessful) return emptyList()
            val json = ajaxResp.body?.string() ?: return emptyList()
            val html = Regex(""""html":"([\s\S]*?)"(\s*[,}])""").find(json)?.groupValues?.get(1)
                ?: return emptyList()
            val urls = Regex("""href=['"]([^'"]+)['"]\s+class=['"]readImg['"]""").findAll(html)
                .map { it.groupValues[1].replace("\\/", "/") }.toList()
            return urls.mapIndexed { index, url -> Page(index, chapter.url, url) }
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
