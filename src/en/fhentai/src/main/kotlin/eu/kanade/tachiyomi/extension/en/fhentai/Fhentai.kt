package eu.kanade.tachiyomi.extension.en.fhentai

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
import java.io.IOException
import java.net.URLEncoder

/*
 * Fhentai (https://fhentai.net) — Next.js SSR doujinshi gallery.
 *
 *     Latest   : /            (div.card-wrapper[data-id] cards; pagination via rel=next)
 *     Search   : /search?q=<query>
 *     Detail   : /f/<id>      (h1 title, PAGES meta, cover = image 0)
 *     Pages    : https://fhentai.net/api/v1/images/<id>/<n>?ext=avif   (n = 1..N)
 *     Thumbs   : https://fhentai.net/api/v1/images/<id>/<n>?ext=avif&thumb=true
 */
class Fhentai : HttpSource() {

    override val name = "Fhentai"
    override val baseUrl = "https://fhentai.net"
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
        val mangas = doc.select("div.card-wrapper").mapNotNull { card ->
            val link = card.selectFirst("a[href*='/f/']") ?: return@mapNotNull null
            val title = link.attr("aria-label").ifBlank {
                link.selectFirst("img")?.attr("alt")?.substringBefore(" — hentai manga cover") ?: ""
            }.trim()
            if (title.isBlank()) return@mapNotNull null
            SManga.create().apply {
                this.title = title
                setUrlWithoutDomain(link.absUrl("href"))
                thumbnail_url = card.selectFirst("img")?.attr("src").orEmpty()
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
                .ifBlank { doc.title().substringBefore(" | Fhentai").trim() }
            thumbnail_url = doc.selectFirst("meta[property='og:image']")?.absUrl("content")
                ?.ifBlank { null }
                ?: doc.selectFirst("img[src*='thumb=true']")?.absUrl("src").orEmpty()
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
        val id = galleryIdOf(response.request.url.toString())
        val total = doc.select(".detail-meta-item").firstOrNull { it.text().contains("PAGES") }
            ?.selectFirst("b")?.text()?.trim()?.toIntOrNull()
            ?: doc.title().let {
                Regex("""\[(\d+) Pages\]""").find(it)?.groupValues?.get(1)?.toIntOrNull()
            }
            ?: 0
        if (id.isEmpty() || total <= 0) return emptyList()
        return (1..total).map { n ->
            Page(n - 1, imageUrl = "https://fhentai.net/api/v1/images/$id/$n?ext=avif")
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================= Utilities =============================

    private fun galleryIdOf(url: String): String = url.trim('/').substringAfterLast('/')

    private fun parseDoc(response: Response): Document =
        Jsoup.parse(response.body?.string() ?: throw IOException("Empty response body"))

    private fun nextPageUrl(doc: Document): String? {
        val next = doc.selectFirst("link[rel='next']")
            ?: doc.select("a[rel='next']").firstOrNull()
            ?: return null
        val href = next.attr("href")
        if (href.isEmpty()) return null
        return next.absUrl("href").ifEmpty { href }
    }

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
