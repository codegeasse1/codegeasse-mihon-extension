package eu.kanade.tachiyomi.extension.en.manhwaz

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
import java.io.IOException
import java.net.URLEncoder

/*
 * ManhwaZ (https://manhwaz.com) — WordPress with a Madara-style WP-Manga theme.
 * Plain SSR HTML everywhere.
 *
 *     Browse  : /                (latest updates, page 1)
 *               ?page=<N>        (pagination; hasNextPage via <link rel="next">)
 *     Popular : /                (#slide-top "POPULAR WEB UPDATES" carousel, no paging)
 *     Search  : /search?s=<q>    (all results on one page, no pagination)
 *     Manga   : /webtoon/<slug>  -> .post-title h1, .summary_image img,
 *               .post-content_item rows (Author(s), status, Genre(s) in
 *               .genres-content a), .description-summary .summary__content;
 *               chapters in ul.list-item.box-list-chapter li.wp-manga-chapter a
 *     Chapter : /webtoon/<slug>/chapter-<n> -> div.reading-content img (page
 *               images on cdn.manhwaz.com, direct src or data-src)
 */
class ManhwaZ : HttpSource() {

    override val name = "ManhwaZ"
    override val baseUrl = "https://manhwaz.com"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", BROWSER_UA)
        .set("Referer", "$baseUrl/")

    // =========================== Browse & Search =========================

    override fun popularMangaRequest(page: Int): Request =
        GET(baseUrl, headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asDocument()
        val mangas = document.select("#slide-top .item").mapNotNull(::mangaFromCard)
        return MangasPage(mangas, false)
    }

    override fun latestUpdatesRequest(page: Int): Request =
        GET(if (page == 1) baseUrl else "$baseUrl?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asDocument()
        val mangas = document.select(".manga-content .page-item-detail").mapNotNull(::mangaFromCard)
        val hasNextPage = document.selectFirst("link[rel=next]") != null
        return MangasPage(mangas, hasNextPage)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/search?s=${URLEncoder.encode(query, "utf-8")}", headers)

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asDocument()
        val mangas = document.select(".manga-content .page-item-detail").mapNotNull(::mangaFromCard)
        return MangasPage(mangas, false)
    }

    private fun mangaFromCard(card: Element): SManga? {
        val link = card.selectFirst("a[href*=\"/webtoon/\"]") ?: return null
        val img = link.selectFirst("img") ?: return null
        return SManga.create().apply {
            setUrlWithoutDomain(link.absUrl("href"))
            title = link.attr("title")
                .takeIf { it.isNotBlank() }
                ?: card.selectFirst(".post-title a")?.text()
                ?: card.selectFirst(".line-2 a")?.text()
                ?: img.attr("title")
                ?: img.attr("alt")
                ?: ""
            thumbnail_url = img.absUrl("src")
        }
    }

    // =========================== Manga Details ===========================

    override fun getMangaUrl(manga: SManga): String =
        if (manga.url.startsWith("http")) manga.url else baseUrl + manga.url

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asDocument()
        val postItems = document.select(".post-content_item")
        fun detail(label: String): String? =
            postItems.firstOrNull { item ->
                item.selectFirst(".summary-heading h5")?.text()?.trim()?.equals(label, true) == true
            }?.selectFirst(".summary-content")?.text()?.trim()

        return SManga.create().apply {
            title = document.selectFirst(".post-title h1")?.text()?.trim().orEmpty()
            setUrlWithoutDomain(response.request.url.toString())
            document.selectFirst(".summary_image img")?.let { thumbnail_url = it.absUrl("src") }
            detail("Author(s)")?.takeIf { it.isNotBlank() && it != "Updating" }?.let { author = it }
            detail("Artist")?.takeIf { it.isNotBlank() && it != "Updating" }?.let { artist = it }
            document.select(".genres-content a").eachText().joinToString().takeIf { it.isNotBlank() }?.let { genre = it }
            val statusText = detail("status")
            status = when {
                statusText?.contains("ongoing", true) == true -> SManga.ONGOING
                statusText?.contains("completed", true) == true -> SManga.COMPLETED
                statusText?.contains("hiatus", true) == true -> SManga.ON_HIATUS
                statusText?.contains("cancel", true) == true -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
            document.selectFirst(".description-summary .summary__content")?.let { description = it.text() }
        }
    }

    // ============================== Chapters =============================

    override fun getChapterUrl(chapter: SChapter): String =
        if (chapter.url.startsWith("http")) chapter.url else baseUrl + chapter.url

    override fun chapterListRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asDocument()
        return document.select("ul.list-item.box-list-chapter li.wp-manga-chapter a").mapNotNull { link ->
            SChapter.create().apply {
                url = link.absUrl("href")
                name = link.text().trim().ifBlank { link.attr("title") }
                chapter_number = CHAPTER_NUMBER_REGEX.find(name)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
            }
        }
    }

    // =============================== Pages ===============================

    override fun pageListRequest(chapter: SChapter): Request =
        GET(getChapterUrl(chapter), headers)

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asDocument()
        val chapterUrl = response.request.url.toString()
        return document.select(".reading-content img").mapIndexedNotNull { index, element ->
            val imageUrl = imageFromElement(element)
            if (imageUrl.isNullOrBlank()) null
            else Page(index, chapterUrl, imageUrl)
        }
    }

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl ?: page.url, headers.newBuilder().set("Referer", "$baseUrl/").build())

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ========================== Image helpers ============================

    private fun imageFromElement(element: Element): String? = when {
        element.hasAttr("data-src") -> element.absUrl("data-src")
        element.hasAttr("data-lazy-src") -> element.absUrl("data-lazy-src")
        element.hasAttr("data-cfsrc") -> element.absUrl("data-cfsrc")
        else -> element.absUrl("src")
    }

    private fun Response.asDocument(): Document {
        val body = body?.string() ?: throw IOException("Empty response body")
        return Jsoup.parse(body, request.url.toString())
    }

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        private val CHAPTER_NUMBER_REGEX = Regex("""(\d+(?:\.\d+)?)""")
    }
}
