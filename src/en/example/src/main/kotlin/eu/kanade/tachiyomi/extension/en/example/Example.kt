package eu.kanade.tachiyomi.extension.en.example

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup // <-- MISSING IMPORT ADDED HERE
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Minimal skeleton for a manga source extension.
 * Rename the class/package (and the matching values in build.gradle.kts /
 * AndroidManifest.xml) for your real target site, then fill in the parse
 * logic below using the site's actual HTML structure or API.
 */
class Example : HttpSource() {

    override val name = "Example"

    override val baseUrl = "https://example.com"

    override val lang = "en"

    override val supportsLatest = true

    // ---- Popular -----------------------------------------------------

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/manga?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("div.manga-item").map { it.toSManga() }
        val hasNextPage = document.selectFirst("a.next-page") != null
        return MangasPage(mangas, hasNextPage)
    }

    // ---- Latest --------------------------------------------------------

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/latest?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage =
        popularMangaParse(response)

    // ---- Search ----------------------------------------------------------

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/search?q=$query&page=$page", headers)

    override fun searchMangaParse(response: Response): MangasPage =
        popularMangaParse(response)

    override fun getFilterList(): FilterList = FilterList()

    // ---- Manga details -----------------------------------------------

    // FIXED: Changed parameter from Document to Response, and converted it to Document inside.
    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("h1.title")?.text().orEmpty()
            author = document.selectFirst("span.author")?.text()
            description = document.selectFirst("div.description")?.text()
            thumbnail_url = document.selectFirst("img.cover")?.absUrl("src")
        }
    }

    // ---- Chapters ----------------------------------------------------

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select("li.chapter-item").map { element ->
            SChapter.create().apply {
                name = element.select("a.chapter-title").text()
                setUrlWithoutDomain(element.select("a.chapter-title").attr("href"))
            }
        }
    }

    // ---- Pages ----------------------------------------------------------

    override fun pageListParse(response: Response): List<eu.kanade.tachiyomi.source.model.Page> {
        val document = response.asJsoup()
        return document.select("div.page-image img").mapIndexed { index, element ->
            eu.kanade.tachiyomi.source.model.Page(index, imageUrl = element.absUrl("src"))
        }
    }

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException("Not used")

    // ---- Helpers ----------------------------------------------------------

    private fun Element.toSManga(): SManga = SManga.create().apply {
        val link = select("a.manga-link")
        title = link.text()
        setUrlWithoutDomain(link.attr("href"))
        thumbnail_url = select("img").attr("abs:src")
    }
}
