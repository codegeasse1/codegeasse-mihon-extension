package eu.kanade.tachiyomi.extension.en.mangataro

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
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/*
 * MangaTaro (https://mangataro.org) — Laravel/WP reader.
 *
 *     Popular : /            (homepage manga cards)
 *     Search  : /?s=<query>
 *     Detail  : /manga/<slug>        (h1, cover content/media/manga-<id>-cover-*, data-manga-id)
 *     Chapters: GET /auth/manga-chapters?manga_id=<id>&offset=0&limit=500&order=DESC&_t=<token>&_ts=<ts>
 *               token = md5(ts + "mng_ch_" + yyyyMMddHH).substring(0,16)   (UTC hour)
 *               -> {"chapters":[{id, chapter, title, url, date, ...}]}
 *     Pages   : GET /auth/chapter-content?chapter_id=<chapterId>
 *               -> {"images":["https://mangataro.yachts/storage/chapters/.../NNN.webp", ...]}
 */
class MangaTaro : HttpSource() {

    override val name = "MangaTaro"
    override val baseUrl = "https://mangataro.org"
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
        GET("$baseUrl/?s=${URLEncoder.encode(query, "utf-8")}", headers)

    override fun searchMangaParse(response: Response): MangasPage = parseList(response)

    override fun getFilterList(): FilterList = FilterList()

    private fun parseList(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string(), response.request.url.toString())
        val items = doc.select("a[href*='/manga/']")
        val mangas = items.mapNotNull { element ->
            val url = element.attr("href")
            val slug = url.trimEnd('/').substringAfterLast('/')
            if (!url.contains("/manga/") || slug.contains("?") || url.contains("/read/")) return@mapNotNull null
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
            thumbnail_url = doc.selectFirst("img[src*='content/media'], meta[property='og:image']")
                ?.attr("abs:src") ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
            description = doc.selectFirst(".description, [itemprop=description], .content p")?.text() ?: ""
            genre = doc.select("a[href*='/genre/']").joinToString { it.text() }
            status = when {
                doc.text().contains("Ongoing", true) -> SManga.ONGOING
                doc.text().contains("Completed", true) -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    // ============================== Chapters =============================

    override fun chapterListRequest(manga: SManga): Request {
        val mangaId = mangaIdOf(manga)
        val ts = System.currentTimeMillis() / 1000
        val token = chapterToken(ts)
        return GET(
            "$baseUrl/auth/manga-chapters?manga_id=$mangaId&offset=0&limit=500&order=DESC&_t=$token&_ts=$ts",
            headers,
        )
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val json = runCatching { JsonParser.parseString(response.body.string()).asJsonObject }.getOrNull()
            ?: return emptyList()
        val chapters = json.get("chapters")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return chapters.mapNotNull { element ->
            val chapter = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = chapter.get("id")?.asString ?: return@mapNotNull null
            val num = chapter.get("chapter")?.asString ?: return@mapNotNull null
            SChapter.create().apply {
                url = "/read/" + id
                name = buildString {
                    append("Ch. ", num)
                    chapter.get("title")?.takeIf { it.isJsonPrimitive }?.asString
                        ?.takeIf { it.isNotBlank() }?.let { append(": ", it) }
                }
                chapter_number = num.toFloatOrNull() ?: 0F
            }
        }.sortedByDescending { it.chapter_number }
    }

    // =============================== Pages ===============================

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    override fun pageListRequest(chapter: SChapter): Request {
        val chapterId = chapter.url.substringAfterLast('/')
        return GET("$baseUrl/auth/chapter-content?chapter_id=$chapterId", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val json = runCatching { JsonParser.parseString(response.body.string()).asJsonObject }.getOrNull()
            ?: return emptyList()
        val images = json.get("images")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return images.mapNotNull { element ->
            if (!element.isJsonPrimitive) return@mapNotNull null
            val url = element.asString.replace("\\/", "/")
            Page(images.indexOf(element), "", url)
        }
    }

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl!!, headers)

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================= Utilities =============================

    private fun mangaIdOf(manga: SManga): String {
        // fall back to fetching the detail page and reading data-manga-id
        return runCatching {
            val doc = Jsoup.parse(client.newCall(mangaDetailsRequest(manga)).execute().body?.string() ?: return "")
            doc.selectFirst("[data-manga-id]")?.attr("data-manga-id")
        }.getOrNull().orEmpty()
    }

    private fun chapterToken(ts: Long): String {
        val hour = SimpleDateFormat("yyyyMMddHH", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(ts * 1000))
        return md5("$ts" + "mng_ch_" + hour).substring(0, 16)
    }

    private fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
