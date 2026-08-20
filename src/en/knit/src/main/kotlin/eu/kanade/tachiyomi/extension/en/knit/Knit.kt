package eu.kanade.tachiyomi.extension.en.knit

import com.google.gson.JsonParser
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import java.net.URLEncoder

/*
 * Knit (https://xx.knit.bid/en/) — a multi-language gallery site ("Love the girl")
 * of cosplay / model / AI beauty photo sets. Server-rendered HTML; no public JSON API.
 *
 *     Latest   : /en/sort/new/            + /en/sort/new/page/N/
 *     Popular  : /en/sort/hot/            + /en/sort/hot/page/N/
 *     Search   : /en/search/?s=<q>        + /en/search/page/N/?s=<q>
 *     Details  : /en/article/<id>/
 *     Pages    : the gallery is paginated server-side into 10-image pages;
 *                page 1 is embedded in the article HTML, pages 2..N live at
 *                /en/article/<id>/page/N/  (N comes from the article-page-config JSON)
 *
 * The site sits behind Cloudflare, so we send just a browser User-Agent (no
 * Referer/Origin) — that serves both the HTML pages and the /static/ image CDN.
 */
class Knit : HttpSource() {

    override val name = "Knit"
    override val baseUrl = "https://xx.knit.bid"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder =
        super.headersBuilder().set("User-Agent", BROWSER_UA)

    // ========================== Search & Browse ===========================

    override fun popularMangaRequest(page: Int): Request =
        GET(sortUrl("hot", page), headers)

    override fun popularMangaParse(response: Response): MangasPage = parseListing(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET(sortUrl("new", page), headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseListing(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val q = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val base = if (page > 1) "$baseUrl/en/search/page/$page/" else "$baseUrl/en/search/"
        return GET("$base?s=$q", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseListing(response)

    private fun sortUrl(method: String, page: Int): String {
        val base = "$baseUrl/en/sort/$method/"
        return if (page > 1) "${base}page/$page/" else base
    }

    private fun parseListing(response: Response): MangasPage {
        val doc = response.asJsoup()
        val mangas = doc.select("article.excerpt").mapNotNull { card ->
            val link = card.selectFirst("a.imgbox-link") ?: return@mapNotNull null
            val url = link.absUrl("href")
            if (!url.contains("/article/")) return@mapNotNull null
            val img = card.selectFirst("img.imgbox-img")
            val title = link.attr("title").trim()
                .takeIf { it.isNotBlank() }
                ?: img?.attr("alt")?.trim().orEmpty()
            val dataSrc = img?.attr("data-original-src").orEmpty()
            val cover = if (dataSrc.isNotBlank()) link.absUrl("data-original-src") else link.absUrl("src")
            SManga.create().apply {
                this.url = url
                this.title = title
                thumbnail_url = cover.takeIf { it.isNotBlank() && !it.contains("timg.gif") }
            }
        }
        val hasNext = doc.select(".next-page").isNotEmpty()
        return MangasPage(mangas, hasNext)
    }

    // ========================== Manga Details =============================

    override fun getMangaUrl(manga: SManga): String = manga.url

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()
        val title = doc.selectFirst("h1.focusbox-title")?.text()?.trim().orEmpty()
        val genre = doc.select(".article-tags a").mapNotNull { it.text().trim().takeIf(String::isNotBlank) }
            .distinct().joinToString()
        return SManga.create().apply {
            this.title = title
            url = response.request.url.toString()
            thumbnail_url = doc.coverUrl()
            status = SManga.ONGOING
            this.genre = genre
        }
    }

    // ============================= Chapters ===============================

    override fun chapterListRequest(manga: SManga): Request =
        GET(manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        return listOf(SChapter.create().apply {
            url = response.request.url.toString()
            name = "Gallery"
            chapter_number = 1f
        })
    }

    override fun getChapterUrl(chapter: SChapter): String = chapter.url

    // =============================== Pages ================================

    override fun pageListRequest(chapter: SChapter): Request =
        GET(chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        val first = response.asJsoup()
        var pages = parseImagePages(first).toMutableList()
        val id = ARTICLE_ID_REGEX.find(response.request.url.toString())?.groupValues?.get(1)
            ?: return pages
        val totalPages = totalImagePages(first)
        if (totalPages > 1) {
            for (p in 2..minOf(totalPages, MAX_IMAGE_PAGES)) {
                val doc = client.newCall(GET("$baseUrl/en/article/$id/page/$p/", headers))
                    .execute().use { it.asJsoup() }
                pages += parseImagePages(doc)
            }
        }
        return pages
    }

    private fun totalImagePages(doc: Document): Int {
        val raw = doc.selectFirst("script#article-page-config")?.data() ?: return 1
        return runCatching {
            JsonParser.parseString(raw).asJsonObject
                .getAsJsonObject("pagination")
                .get("total_pages").asInt
        }.getOrDefault(1)
    }

    private fun parseImagePages(doc: Document): List<Page> {
        val pages = mutableListOf<Page>()
        doc.select("img.item-image__img").forEach { img ->
            val dataSrc = img.attr("data-src")
            val url = if (dataSrc.isNotBlank()) img.absUrl("data-src") else img.absUrl("src")
            if (url.isNotBlank() && !url.contains("timg.gif")) {
                pages.add(Page(pages.size, url, url))
            }
        }
        return pages
    }

    private fun Document.coverUrl(): String? {
        val img = selectFirst("img.item-image__img") ?: return null
        val dataSrc = img.attr("data-src")
        val url = if (dataSrc.isNotBlank()) img.absUrl("data-src") else img.absUrl("src")
        return url.takeIf { it.isNotBlank() && !it.contains("timg.gif") }
    }

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl!!, headers)

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException()

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        private val ARTICLE_ID_REGEX = Regex("""/article/(\d+)/""")

        private const val MAX_IMAGE_PAGES = 200
    }
}
