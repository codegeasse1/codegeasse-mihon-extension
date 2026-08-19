package eu.kanade.tachiyomi.extension.en.rawkuma

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
import org.jsoup.nodes.Element
import java.net.URLEncoder

/*
 * Rawkuma (https://rawkuma.net) — WordPress raws.
 *
 *     Popular : /                    (home/advanced-search grid with /manga/<slug>/ links)
 *     Latest  : /                    (same grid)
 *     Search  : GET /  to read the admin-ajax nonce, then POST /wp-admin/admin-ajax.php?nonce=<n>&action=search
 *               with form field `query` (htmx protocol; best-effort — empty results on failure).
 *     Detail  : /manga/<slug>/
 *     Chapters: /manga/<slug>/chapter-N.<chapterId>/
 *     Pages   : reader page embeds <img src="https://cdn.kumacdn.club/wp-content/uploads/src/...">
 */
class Rawkuma : HttpSource() {

    override val name = "Rawkuma"
    override val baseUrl = "https://rawkuma.net"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", BROWSER_UA)
        .set("Referer", "$baseUrl/")

    // =========================== Browse & Search =========================

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response): MangasPage = parseList(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/?__rk_search=${URLEncoder.encode(query, "utf-8")}", headers)

    override fun searchMangaParse(response: Response): MangasPage {
        val query = response.request.url.queryParameter("__rk_search") ?: return MangasPage(emptyList(), false)
        val body = response.body?.string() ?: return MangasPage(emptyList(), false)
        val nonce = Regex("""admin-ajax\.php\?nonce=([a-f0-9]+)[^"]*action=search""")
            .find(body)?.groupValues?.get(1) ?: return MangasPage(emptyList(), false)

        val form = FormBody.Builder().add("query", query).build()
        val req = Request.Builder()
            .url("$baseUrl/wp-admin/admin-ajax.php?nonce=$nonce&action=search")
            .post(form)
            .headers(headers)
            .build()
        return runCatching {
            client.newCall(req).execute().use { ajaxResp ->
                if (!ajaxResp.isSuccessful) return MangasPage(emptyList(), false)
                val doc = Jsoup.parse(ajaxResp.body?.string() ?: "", req.url.toString())
                parseDoc(doc)
            }
        }.getOrDefault(MangasPage(emptyList(), false))
    }

    override fun getFilterList(): FilterList = FilterList()

    private fun parseList(response: Response): MangasPage =
        parseDoc(Jsoup.parse(response.body?.string() ?: "", response.request.url.toString()))

    private fun parseDoc(doc: org.jsoup.nodes.Document): MangasPage {
        val items = doc.select("a[href*='/manga/']")
        val mangas = items.mapNotNull { element ->
            val url = element.attr("href")
            val slug = url.trimEnd('/').substringAfterLast('/')
            if (!url.contains("/manga/") || slug in setOf("library", "bookmark", "history", "premium", "leaderboard")) return@mapNotNull null
            val img = element.selectFirst("img")
            SManga.create().apply {
                this.url = url.substringAfter(baseUrl)
                title = element.attr("title").ifBlank { element.text() }
                    .ifBlank { img?.attr("alt") }?.ifBlank { slug.replace('-', ' ') }.orEmpty()
                thumbnail_url = img?.httpImageUrl()
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
            thumbnail_url = doc.selectFirst("img.wp-post-image, img[src*='wp-content/uploads'], meta[property='og:image']")
                ?.httpImageUrl() ?: doc.selectFirst("meta[property='og:image']")?.attr("content")?.toHttpUrl()
            description = doc.selectFirst(".description, .entry-content p, [itemprop=description]")?.text() ?: ""
            genre = doc.select("a[href*='/genre/']").joinToString { it.text() }
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
        return doc.select("a[href*='/manga/'][href*='chapter-']").mapNotNull { element ->
            val url = element.attr("href")
            if (!url.contains("/chapter-")) return@mapNotNull null
            SChapter.create().apply {
                this.url = url.substringAfter(baseUrl)
                name = element.text().ifBlank { url.trimEnd('/').substringAfterLast('/') }
                val num = Regex("""chapter-(\d+(?:\.\d+)?)""").find(url)?.groupValues?.get(1)
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
        val urls = doc.select("img[src*='cdn.kumacdn.club'], img[src*='rcdn.kyut.dev'], img[src*='rawkuma.net/wp-content/uploads/src']")
            .mapNotNull { it.httpImageUrl() }
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
