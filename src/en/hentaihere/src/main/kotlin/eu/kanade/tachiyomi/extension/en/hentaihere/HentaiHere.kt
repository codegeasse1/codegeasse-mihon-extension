package eu.kanade.tachiyomi.extension.en.hentaihere

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
 * HentaiHere (https://hentaihere.com) — hentaicdn-family doujinshi reader.
 *
 *     Latest   : /directory            (div.item cards, sorted newest; ?page=N)
 *     Search   : /search?s=<query>     (paginated with &page=N)
 *     Detail   : /m/<id>               (#cover img, chapters in ul.arf-list a#chapterBlock)
 *     Reader   : /m/<id>/<ch>/1/       (page count = max a.rdrPage[data-pag])
 *     Pages    : https://hentaicdn.com/hentai/<numid>/<ch>/ccdnNNNN.jpg
 */
class HentaiHere : HttpSource() {

    override val name = "HentaiHere"
    override val baseUrl = "https://hentaihere.com"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", BROWSER_UA)
        .set("Referer", "$baseUrl/")

    // =========================== Browse & Search =========================

    override fun popularMangaRequest(page: Int): Request = latestUpdatesRequest(page)

    override fun popularMangaParse(response: Response): MangasPage = latestUpdatesParse(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET(if (page <= 1) "$baseUrl/directory" else "$baseUrl/directory?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseList(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val q = URLEncoder.encode(query, "utf-8")
        return GET(if (page <= 1) "$baseUrl/search?s=$q" else "$baseUrl/search?s=$q&page=$page", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseList(response)

    private fun parseList(response: Response): MangasPage {
        val doc = parseDoc(response)
        val mangas = doc.select("div.item").mapNotNull { item ->
            val link = item.selectFirst("a[href*='/m/']") ?: return@mapNotNull null
            val title = link.text().trim() ?: return@mapNotNull null
            if (title.isBlank()) return@mapNotNull null
            SManga.create().apply {
                this.title = title
                setUrlWithoutDomain(link.absUrl("href"))
                thumbnail_url = item.selectFirst("img")?.absUrl("src").orEmpty()
            }
        }
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val maxPage = maxPageNumber(doc)
        return MangasPage(mangas, maxPage == null || page < maxPage)
    }

    // =========================== Manga Details ===========================

    override fun getMangaUrl(manga: SManga): String =
        if (manga.url.startsWith("http")) manga.url else baseUrl + manga.url

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = parseDoc(response)
        return SManga.create().apply {
            title = doc.selectFirst("h4 a.arkPO")?.text()?.trim()
                ?.substringBefore(" [")?.trim()
                ?.ifBlank { null }
                ?: doc.title().substringBefore(" Hentai by").substringBefore(" at HentaiHere").trim()
            thumbnail_url = doc.selectFirst("#cover img")?.absUrl("src").orEmpty()
        }
    }

    // ============================== Chapters =============================

    override fun getChapterUrl(chapter: SChapter): String =
        if (chapter.url.startsWith("http")) chapter.url else baseUrl + chapter.url

    override fun chapterListRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = parseDoc(response)
        return doc.select("ul.arf-list a#chapterBlock").mapNotNull { a ->
            val href = a.attr("href")
            if (href.isBlank()) return@mapNotNull null
            val name = a.text().trim().substringBefore(" (by:").trim()
            if (name.isBlank()) return@mapNotNull null
            SChapter.create().apply {
                url = a.absUrl("href")
                this.name = name
                chapter_number = name.substringBefore(" -").trim().toFloatOrNull() ?: 0f
            }
        }
    }

    // =============================== Pages ===============================

    override fun pageListRequest(chapter: SChapter): Request =
        GET(getChapterUrl(chapter), headers)

    override fun pageListParse(response: Response): List<Page> {
        val doc = parseDoc(response)
        val total = doc.select("a.rdrPage[data-pag]").mapNotNull { it.attr("data-pag").toIntOrNull() }
            .maxOrNull() ?: 0
        val segments = response.request.url.pathSegments
        // /m/<id>/<ch>/<page>/
        val id = segments.getOrNull(1)?.removePrefix("S").orEmpty()
        val chapter = segments.getOrNull(2).orEmpty()
        if (id.isEmpty() || chapter.isEmpty() || total <= 0) return emptyList()
        return (1..total).map { n ->
            Page(n - 1, imageUrl = "https://hentaicdn.com/hentai/$id/$chapter/ccdn${"%04d".format(n)}.jpg")
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================= Utilities =============================

    private fun parseDoc(response: Response): Document =
        Jsoup.parse(response.body?.string() ?: throw IOException("Empty response body"))

    private fun maxPageNumber(doc: Document): Int? =
        doc.select("ul.pagination a[href]").mapNotNull { it.attr("href").queryParam("page")?.toIntOrNull() }
            .maxOrNull()

    private fun String.queryParam(name: String): String? {
        val q = substringAfter('?', "").substringBefore('#')
        return q.split('&').mapNotNull { p ->
            val kv = p.split('=', limit = 2)
            if (kv.size == 2 && kv[0] == name) kv[1] else null
        }.firstOrNull()
    }

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
