package eu.kanade.tachiyomi.extension.en.pururin

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
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URLEncoder

/*
 * Pururin (https://pururin.me) — curated doujinshi/manga reader.
 *
 *     Latest   : /            (a.card.card-gallery[data-gid] cards)
 *     Browse   : /browse?page=<n>   (data-last-page on .row-gallery)
 *     Search   : /search?q=<query>
 *     Detail   : /gallery/<gid>/<slug>  (h1 title, img.cover, read button)
 *     Reader   : /read/<gid>/<nn>/<slug> (.img-viewer[data-img] JSON:
 *                {"directory":"...","images":[{"page":N,"filename":"N.jpg"},...]})
 *     Pages    : https://i.pururin.me/<directory>/<filename>
 */
class Pururin : HttpSource() {

    override val name = "Pururin"
    override val baseUrl = "https://pururin.me"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", BROWSER_UA)
        .set("Referer", "$baseUrl/")

    // =========================== Browse & Search =========================

    override fun popularMangaRequest(page: Int): Request = latestUpdatesRequest(page)

    override fun popularMangaParse(response: Response): MangasPage = latestUpdatesParse(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET(if (page <= 1) "$baseUrl/" else "$baseUrl/browse?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseList(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/search?q=${URLEncoder.encode(query, "utf-8")}&page=$page", headers)

    override fun searchMangaParse(response: Response): MangasPage = parseList(response)

    private fun parseList(response: Response): MangasPage {
        val doc = parseDoc(response)
        val mangas = doc.select("a.card-gallery").mapNotNull { card ->
            val link = card.attr("href")
            if (link.isBlank()) return@mapNotNull null
            val img = card.selectFirst("img") ?: return@mapNotNull null
            val title = img.attr("alt").ifBlank { card.attr("title") }.trim()
            if (title.isBlank()) return@mapNotNull null
            SManga.create().apply {
                this.title = title
                setUrlWithoutDomain(card.absUrl("href"))
                thumbnail_url = img.absUrl("src")
            }
        }
        val current = doc.selectFirst(".row-gallery")?.attr("data-current-page")?.toIntOrNull() ?: 1
        val last = doc.selectFirst(".row-gallery")?.attr("data-last-page")?.toIntOrNull() ?: 1
        return MangasPage(mangas, current < last)
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
            thumbnail_url = doc.selectFirst("img.cover")?.absUrl("src")
                ?.ifBlank { null }
                ?: doc.selectFirst("meta[property='og:image']")?.attr("content").orEmpty()
        }
    }

    // ============================== Chapters =============================

    override fun getChapterUrl(chapter: SChapter): String =
        if (chapter.url.startsWith("http")) chapter.url else baseUrl + chapter.url

    override fun chapterListRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = parseDoc(response)
        val read = doc.selectFirst("a[href*='/read/']") ?: return emptyList()
        return listOf(SChapter.create().apply {
            url = read.absUrl("href")
            name = "Read"
            chapter_number = 1f
        })
    }

    // =============================== Pages ===============================

    override fun pageListRequest(chapter: SChapter): Request =
        GET(getChapterUrl(chapter), headers)

    override fun pageListParse(response: Response): List<Page> {
        val doc = parseDoc(response)
        val viewer = doc.selectFirst(".img-viewer") ?: return emptyList()
        val server = viewer.attr("data-svr").ifBlank { "https://i.pururin.me" }
        val json = runCatching {
            JsonParser.parseString(viewer.attr("data-img")).asJsonObject
        }.getOrNull() ?: return emptyList()
        val directory = json.get("directory")?.takeIf { it.isJsonPrimitive }?.asString ?: return emptyList()
        val images = json.get("images")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return images.mapNotNull { el ->
            val img = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val filename = img.get("filename")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
            val page = img.get("page")?.takeIf { it.isJsonPrimitive }?.asInt ?: return@mapNotNull null
            Page(page - 1, imageUrl = "$server/$directory/$filename")
        }.sortedBy { it.index }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================= Utilities =============================

    private fun parseDoc(response: Response): Document =
        Jsoup.parse(response.body?.string() ?: throw IOException("Empty response body"))

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
