package eu.kanade.tachiyomi.extension.en.pornpics

import com.google.gson.JsonParser
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
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
import org.jsoup.nodes.Element
import java.net.URLEncoder

/*
 * PornPics (https://www.pornpics.de) — a ~500k gallery porn-pics aggregator.
 * Category pages expose a JSON "offset" API for pagination; search and the
 * gallery detail pages are plain server-rendered HTML.
 *
 *     Latest/Browse : /asian/?offset=N          -> JSON array (20 per batch; offset=1 = first)
 *     Search        : /?q=<term>                -> HTML li.thumbwook cards (single page; 404 = no hits)
 *     Details       : /galleries/<slug>-<gid>/  -> .gallery-title h1, .gallery-info__item.tags
 *     Pages         : gallery page li.thumbwook a.rel-link[href] -> 1280px CDN images
 *
 * Each gallery is exposed as a single "Gallery" chapter.
 */
class Pornpics : HttpSource() {

    override val name = "PornPics"
    override val baseUrl = "https://www.pornpics.de"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder =
        super.headersBuilder()
            .set("User-Agent", BROWSER_UA)
            .set("Referer", "$baseUrl/")

    // ========================== Search & Browse ===========================

    override fun getFilterList(): FilterList = FilterList(
        CategoryFilter(),
    )

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/asian/?offset=${offset(page)}", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseJson(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/asian/?offset=${offset(page)}", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseJson(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val category = filters.filterIsInstance<CategoryFilter>().firstOrNull()?.selected
        return if (query.isBlank() && category != null) {
            GET("$baseUrl/$category/?offset=${offset(page)}", headers)
        } else {
            val q = URLEncoder.encode(query.trim(), "UTF-8")
            GET("$baseUrl/?q=$q", headers)
        }
    }

    override fun searchMangaParse(response: Response): MangasPage {
        return if (response.request.url.toString().contains("offset=")) {
            parseJson(response)
        } else {
            parseSearchHtml(response)
        }
    }

    private fun parseJson(response: Response): MangasPage {
        val body = response.body?.string().orEmpty()
        val array = runCatching { JsonParser.parseString(body).asJsonArray }.getOrNull()
            ?: return MangasPage(emptyList(), false)
        val mangas = array.mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            val obj = el.asJsonObject
            val title = obj.get("desc")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
            val url = obj.get("g_url")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
            val thumb = obj.get("t_url_460")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
            if (url.isEmpty() || title.isEmpty()) return@mapNotNull null
            SManga.create().apply {
                this.url = url
                this.title = title
                thumbnail_url = thumb.ifBlank { null }
            }
        }
        return MangasPage(mangas, hasNextPage = array.size() >= 20)
    }

    private fun parseSearchHtml(response: Response): MangasPage {
        if (response.code == 404) return MangasPage(emptyList(), false)
        val doc = response.asJsoup()
        val mangas = buildList {
            doc.select("li.thumbwook a.rel-link[href]").forEach { a ->
                val href = a.absUrl("href")
                if (!href.startsWith("http")) return@forEach
                val img = a.selectFirst("img")
                val title = a.attr("title").ifBlank { img?.attr("alt").orEmpty() }.trim()
                if (title.isEmpty()) return@forEach
                add(SManga.create().apply {
                    url = href
                    this.title = title
                    thumbnail_url = img?.let(::imgUrl)
                })
            }
        }
        return MangasPage(mangas, hasNextPage = false)
    }

    // ========================== Manga Details =============================

    override fun getMangaUrl(manga: SManga): String = manga.url

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()
        val title = doc.selectFirst(".gallery-title h1")?.text()?.trim()
            ?: doc.title().substringBefore(" - pornpics.de").trim()
        return SManga.create().apply {
            this.title = title
            url = response.request.url.toString()
            thumbnail_url = doc.selectFirst("li.thumbwook a.rel-link[href]")?.absUrl("href")
            genre = doc.select(".gallery-info__item.tags").firstOrNull()
                ?.select(".gallery-info__content a span")
                ?.mapNotNull { it.text().trim().ifEmpty { null } }
                ?.distinct()?.joinToString()
                ?: ""
            this.author = infoItem(doc, "Models")
            this.status = SManga.UNKNOWN
            description = doc.selectFirst("meta[name=description]")?.attr("content")?.trim().orEmpty()
        }
    }

    // ============================= Chapters ===============================

    override fun chapterListRequest(manga: SManga): Request =
        GET(manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = response.asJsoup()
        val title = doc.selectFirst(".gallery-title h1")?.text()?.trim()
            ?: doc.title().substringBefore(" - pornpics.de").trim()
        return listOf(
            SChapter.create().apply {
                url = response.request.url.toString()
                name = title
                date_upload = 0L
                chapter_number = 1f
            },
        )
    }

    override fun getChapterUrl(chapter: SChapter): String = chapter.url

    // =============================== Pages ================================

    override fun pageListRequest(chapter: SChapter): Request =
        GET(chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        val doc = response.asJsoup()
        return doc.select("li.thumbwook a.rel-link[href]").mapIndexedNotNull { index, a ->
            val url = a.absUrl("href")
            if (!url.startsWith("https://cdni.pornpics.de/")) return@mapIndexedNotNull null
            Page(index, url, url)
        }
    }

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl!!, headers)

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException()

    // ============================= Utilities ==============================

    // offset=0 serves HTML; any non-zero offset serves the JSON batch API.
    private fun offset(page: Int): Int = if (page <= 1) 1 else (page - 1) * 20

    private fun imgUrl(img: Element): String? {
        val attr = when {
            img.attr("data-src").isNotBlank() -> "data-src"
            img.attr("src").isNotBlank() -> "src"
            else -> return null
        }
        return img.absUrl(attr).ifBlank { null }
    }

    private fun infoItem(doc: Document, heading: String): String =
        doc.select(".gallery-info__item").mapNotNull { item ->
            val h = item.selectFirst(".gallery-info__title")?.text()?.trim() ?: return@mapNotNull null
            if (h.equals(heading, ignoreCase = true)) {
                item.select(".gallery-info__content a span").map { it.text().trim() }.distinct().joinToString()
            } else null
        }.firstOrNull() ?: ""

    // ============================== Filters ===============================

    private class CategoryFilter : Filter.Select<String>(
        "Category",
        arrayOf("All") + CATEGORIES.map { it.first },
    ) {
        val selected: String? get() = if (state == 0) null else CATEGORIES[state - 1].second
    }

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        // Verified category slugs (all serve the offset JSON API like /asian/).
        private val CATEGORIES = listOf(
            "Asian" to "asian",
            "Creampie" to "creampie",
            "Blonde" to "blonde",
            "Lingerie" to "lingerie",
            "Pussy" to "pussy",
            "Shaved" to "shaved",
            "Maid" to "maid",
            "Hardcore" to "hardcore",
            "Ass" to "ass",
            "Asshole" to "asshole",
            "Babe" to "babe",
            "Perfect" to "perfect",
        )
    }
}
