package eu.kanade.tachiyomi.extension.en.mangazin

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
 * Mangazin (https://mangazin.org) — WordPress with a Madara (WP-Manga) theme.
 * Hosts ecchi/smut/adult-tagged titles too, so marked NSFW. Plain SSR HTML.
 *
 *     Popular : /manga/?m_orderby=views           (page N: /manga/page/N/?m_orderby=views)
 *     Latest  : /manga/?m_orderby=latest          (page N: /manga/page/N/?m_orderby=latest)
 *     Search  : /?s=<q>&post_type=wp-manga        (12 results per page, no pagination)
 *     Manga   : /manga/<slug>                     -> .post-title h1, .summary_image img
 *               (cover uses data-src), .author-content a, .genres-content a, status row
 *               with span.h5 heading, .description-summary .summary__content; chapters
 *               SSR in ul.main.version-chap li.wp-manga-chapter a
 *     Chapter : /manga/<slug>/chapter-<n>         -> div.reading-content img.wp-manga-chapter-img,
 *               real image URL in data-src (src is a loading2.svg placeholder)
 */
class Mangazin : HttpSource() {

    override val name = "Mangazin"
    override val baseUrl = "https://mangazin.org"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", BROWSER_UA)
        .set("Referer", "$baseUrl/")

    // =========================== Browse & Search =========================

    override fun popularMangaRequest(page: Int): Request =
        GET(if (page == 1) "$baseUrl/manga/?m_orderby=views" else "$baseUrl/manga/page/$page/?m_orderby=views", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asDocument()
        val mangas = document.select(".page-listing-item .page-item-detail").mapNotNull(::mangaFromCard)
        val hasNextPage = document.selectFirst("link[rel=next]") != null
        return MangasPage(mangas, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request =
        GET(if (page == 1) "$baseUrl/manga/?m_orderby=latest" else "$baseUrl/manga/page/$page/?m_orderby=latest", headers)

    override fun latestUpdatesParse(response: Response): MangasPage =
        popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/?s=${URLEncoder.encode(query, "utf-8")}&post_type=wp-manga", headers)

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asDocument()
        val mangas = document.select(".c-tabs-item__content .tab-thumb a").mapNotNull { link ->
            val img = link.selectFirst("img") ?: return@mapNotNull null
            SManga.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                title = link.attr("title").takeIf { it.isNotBlank() }
                    ?: link.text()
                    ?: img.attr("title")
                    ?: img.attr("alt")
                    ?: ""
                thumbnail_url = imageFromElement(img) ?: ""
            }
        }
        return MangasPage(mangas, false)
    }

    private fun mangaFromCard(card: Element): SManga? {
        val link = card.selectFirst(".item-thumb a") ?: return null
        val img = card.selectFirst(".item-thumb img") ?: return null
        return SManga.create().apply {
            setUrlWithoutDomain(link.absUrl("href"))
            title = card.selectFirst(".post-title a")?.text()
                ?.takeIf { it.isNotBlank() }
                ?: link.attr("title")
                ?: img.attr("title")
                ?: img.attr("alt")
                ?: ""
            thumbnail_url = imageFromElement(img) ?: ""
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
                item.selectFirst(".summary-heading h5, .summary-heading .h5")?.text()?.trim()?.equals(label, true) == true
            }?.selectFirst(".summary-content")?.text()?.trim()

        return SManga.create().apply {
            title = document.selectFirst(".post-title h1")?.text()?.trim().orEmpty()
            setUrlWithoutDomain(response.request.url.toString())
            document.selectFirst(".summary_image img")?.let { thumbnail_url = imageFromElement(it) ?: "" }
            document.select(".author-content a").eachText().joinToString().takeIf { it.isNotBlank() }?.let { author = it }
            document.select(".artist-content a").eachText().joinToString().takeIf { it.isNotBlank() }?.let { artist = it }
            document.select(".genres-content a").eachText().joinToString().takeIf { it.isNotBlank() }?.let { genre = it }
            val statusText = detail("Status")
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
        return document.select("ul.main.version-chap li.wp-manga-chapter a").mapNotNull { link ->
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

    private fun imageFromElement(element: Element): String? {
        val url = element.attr("data-src").trim()
            .ifEmpty { element.attr("data-lazy-src").trim() }
            .ifEmpty { element.attr("data-cfsrc").trim() }
            .ifEmpty { element.attr("src").trim() }
        return url.takeIf { it.isNotBlank() }
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
