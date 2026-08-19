package eu.kanade.tachiyomi.extension.en.threehentai

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
 * 3Hentai (https://3hentai.net) — doujinshi gallery site.
 *
 *     Latest   : /            (div.doujin cards; pagination /2, /3, ... via next link)
 *     Search   : /search?q=<query>
 *     Detail   : /d/<id>/     (h1 title, cover img[data-src$='cover.jpg'], page thumbs s1.3hentai.xyz/<dir>/<n>t.jpg)
 *     Pages    : https://s1.3hentai.xyz/<dir>/<n>.jpg   (strip the trailing 't')
 *
 *     NOTE: 3hentai.net is behind Cloudflare with TLS-level bot protection; on some devices
 *     okhttp's TLS handshake is rejected there (SSLHandshakeException) — that is server-side
 *     and cannot be fixed from the extension. This file fixes the URL handling.
 */
class ThreeHentai : HttpSource() {

    override val name = "3Hentai"
    override val baseUrl = "https://3hentai.net"
    override val lang = "en"
    override val supportsLatest = true

    @Volatile
    private var nextUrl: String? = null

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", BROWSER_UA)
        .set("Referer", "$baseUrl/")

    // =========================== Browse & Search =========================

    override fun popularMangaRequest(page: Int): Request = latestUpdatesRequest(page)

    override fun popularMangaParse(response: Response): MangasPage = latestUpdatesParse(response)

    override fun latestUpdatesRequest(page: Int): Request {
        if (page == 1) nextUrl = null
        return GET(nextUrl ?: "$baseUrl/", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = parseList(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (page == 1) nextUrl = null
        val url = "$baseUrl/search?q=${URLEncoder.encode(query, "utf-8")}"
        return GET(nextUrl ?: url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseList(response)

    private fun parseList(response: Response): MangasPage {
        val doc = parseDoc(response)
        val mangas = doc.select("div.doujin").mapNotNull { item ->
            val link = item.selectFirst("a.cover[href*='/d/']") ?: return@mapNotNull null
            val title = item.selectFirst("div.title")?.text()?.trim() ?: return@mapNotNull null
            if (title.isBlank()) return@mapNotNull null
            SManga.create().apply {
                this.title = title
                setUrlWithoutDomain(link.absUrl("href"))
                thumbnail_url = item.selectFirst("img")?.httpImageUrl()
            }
        }
        nextUrl = nextPageUrl(doc)
        return MangasPage(mangas, nextUrl != null)
    }

    // =========================== Manga Details ===========================

    override fun getMangaUrl(manga: SManga): String =
        if (manga.url.startsWith("http")) manga.url else baseUrl + manga.url

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = parseDoc(response)
        return SManga.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
            thumbnail_url = doc.selectFirst("img[data-src$='cover.jpg']")?.httpImageUrl()
                ?: doc.selectFirst("meta[property='og:image']")?.attr("content")?.toHttpUrl()
        }
    }

    // ============================== Chapters =============================

    override fun getChapterUrl(chapter: SChapter): String =
        if (chapter.url.startsWith("http")) chapter.url else baseUrl + chapter.url

    override fun chapterListRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), headers)

    override fun chapterListParse(response: Response): List<SChapter> =
        listOf(SChapter.create().apply {
            url = response.request.url.toString()
            name = "Gallery"
            chapter_number = 1f
        })

    // =============================== Pages ===============================

    override fun pageListRequest(chapter: SChapter): Request =
        GET(getChapterUrl(chapter), headers)

    override fun pageListParse(response: Response): List<Page> {
        val doc = parseDoc(response)
        // All page thumbs are rendered server-side as <dir>/<n>t.jpg — full image strips the 't'.
        return doc.select("img[data-src$='t.jpg']").mapIndexedNotNull { i, img ->
            val thumb = img.attr("abs:data-src").ifEmpty { img.attr("abs:src") }
            val full = thumb.removeSuffix("t.jpg") + ".jpg"
            full.toHttpUrl()?.let { Page(i, imageUrl = it) }
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================= Utilities =============================

    private fun parseDoc(response: Response): Document =
        Jsoup.parse(response.body?.string() ?: throw IOException("Empty response body"))

    private fun Element.httpImageUrl(): String? =
        attr("abs:data-src").ifEmpty { attr("abs:src") }.toHttpUrl()

    private fun String.toHttpUrl(): String? {
        val raw = trim()
        return raw.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun nextPageUrl(doc: Document): String? {
        doc.selectFirst("a[rel='next']")?.let { return it.absUrl("href") }
        val links = doc.select("ul.pagination a.page-link")
        for (i in links.size - 1 downTo 0) {
            val a = links[i]
            val href = a.attr("href")
            if (href.isEmpty() || href == "#") continue
            return a.absUrl("href")
        }
        return null
    }

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
