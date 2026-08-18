package eu.kanade.tachiyomi.extension.en.mangack

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
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/*
 * Mangack (https://mangack.com) — custom WordPress theme ("Ifenzi").
 *
 *     Browse   : /newest/ and /newest/page/N/   (Newest Manga catalog)
 *     Latest   : /updates/ and /updates/page/N/ (Latest Updates, paginated)
 *     Search   : /?s=<query>                    (single page, no pagination)
 *     Manga    : /manga/<slug>/  -> details in an <infobox> table, chapters in
 *               <ul class="chapterslist">
 *     Chapter  : /chapter/<slug>-chapter-<num>/ -> pages are
 *               <div class="separator"><img src="..."></div> (absolute CDN URLs)
 *     Covers   : <a href="/manga/.." title=".."><img></a> blocks on list pages.
 *
 * Dates on chapter items are relative ("9 hours ago"), so they are parsed
 * best-effort and fall back to 0 when unparseable.
 */
class Mangack : HttpSource() {

    override val name = "Mangack"
    override val baseUrl = "https://mangack.com"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", BROWSER_UA)
        .set("Referer", "$baseUrl/")
        .set("Accept-Language", "en-US,en;q=0.5")

    // =========================== Browse & Search =========================

    override fun popularMangaRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/newest/" else "$baseUrl/newest/page/$page/"
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage = parseMangaList(response)

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/updates/" else "$baseUrl/updates/page/$page/"
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = parseMangaList(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = if (query.isBlank()) {
            if (page == 1) "$baseUrl/newest/" else "$baseUrl/newest/page/$page/"
        } else {
            "$baseUrl/?s=${URLEncoder.encode(query, "utf-8")}"
        }
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseMangaList(response)

    private fun parseMangaList(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body?.string() ?: throw IOException("Empty response body"))

        val seen = HashSet<String>()
        val mangas = mutableListOf<SManga>()
        for (link in doc.select("a[href*='/manga/']")) {
            if (link.select("img").isEmpty()) continue
            val url = link.absUrl("href")
            if (url.isBlank() || !seen.add(url)) continue
            val title = link.attr("title").ifBlank { link.text() }.trim().ifBlank { continue }
            mangas.add(SManga.create().apply {
                this.title = title
                this.url = url
                thumbnail_url = link.selectFirst("img")?.absUrl("src").orEmpty()
            })
        }

        val hasNextPage = doc.selectFirst("a.next.page-numbers") != null
        return MangasPage(mangas, hasNextPage)
    }

    // =========================== Manga Details ===========================

    override fun getMangaUrl(manga: SManga): String =
        if (manga.url.startsWith("http")) manga.url else baseUrl + manga.url

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body?.string() ?: throw IOException("Empty response body"))
        val manga = SManga.create()

        manga.title = doc.selectFirst("h1.entry-title")?.text()?.trim()
            ?.removeSuffix(" mangack") ?: doc.title()
            .removeSuffix(" mangack")
            .trim()
        manga.thumbnail_url = doc.selectFirst(".manga-cover-wrap img, .ifenzi-cover img")?.absUrl("src").orEmpty()

        val descEl = doc.selectFirst("td.manga-description")
        if (descEl != null) {
            descEl.select("script, style, .code-block, center").remove()
            val desc = descEl.text().trim()
            if (desc.isNotBlank()) manga.description = desc
        }
        if (manga.description.isNullOrBlank()) {
            manga.description = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
        }

        val statusText = infoRow(doc, "Status")
        manga.status = when {
            statusText.contains("ongoing", true) -> SManga.ONGOING
            statusText.contains("completed", true) -> SManga.COMPLETED
            statusText.contains("hiatus", true) -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }

        val genres = infoRow(doc, "Genres")
        if (genres.isNotBlank()) manga.genre = genres

        val author = infoRow(doc, "Author")
        if (author.isNotBlank() && !author.contains("Warning")) {
            manga.author = author
        }

        return manga
    }

    private fun infoRow(doc: Document, label: String): String {
        val td = doc.selectFirst("td:containsOwn($label)") ?: return ""
        return td.nextElementSibling()?.select("a")?.joinToString(", ") { it.text() }
            ?.takeIf { it.isNotBlank() }
            ?: td.nextElementSibling()?.ownText()?.trim().orEmpty()
    }

    // ============================== Chapters =============================

    override fun getChapterUrl(chapter: SChapter): String =
        if (chapter.url.startsWith("http")) chapter.url else baseUrl + chapter.url

    override fun chapterListRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = Jsoup.parse(response.body?.string() ?: return emptyList())
        val chapters = mutableListOf<SChapter>()
        for (li in doc.select("ul.chapterslist li")) {
            val link = li.selectFirst("a.title") ?: continue
            val href = link.absUrl("href")
            if (href.isBlank()) continue
            val num = CHAPTER_NUM_REGEX.find(href)?.groupValues?.getOrNull(1)
                ?: link.text().trim().filter { it.isDigit() }
            val chapter = SChapter.create().apply {
                url = href
                name = if (num.isNullOrBlank()) link.text().trim() else "Chapter $num"
                chapter_number = num?.toFloatOrNull() ?: 0f
                date_upload = li.selectFirst("span.entry-date")?.text()
                    ?.let { parseRelativeDate(it) }
                    ?: 0L
            }
            chapters.add(chapter)
        }
        return chapters.sortedByDescending { it.chapter_number }
    }

    // =============================== Pages ===============================

    override fun pageListRequest(chapter: SChapter): Request =
        GET(getChapterUrl(chapter), headers)

    override fun pageListParse(response: Response): List<Page> {
        val doc = Jsoup.parse(response.body?.string() ?: throw IOException("Empty response body"))
        val images = doc.select("div.separator img[src], div.entry-content img[src]")
        val pages = mutableListOf<Page>()
        for (img in images) {
            val url = img.absUrl("data-lazy-src")
                .ifBlank { img.absUrl("data-src") }
                .ifBlank { img.absUrl("src") }
            if (url.isBlank() || url.startsWith("data:")) continue
            pages.add(Page(pages.size, imageUrl = url))
        }
        return pages
    }

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl ?: page.url, headers.newBuilder().set("Referer", "$baseUrl/").build())

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================= Utilities =============================

    private fun parseRelativeDate(text: String): Long {
        val trimmed = text.trim()
        val match = RELATIVE_DATE_REGEX.find(trimmed)
        if (match != null) {
            val amount = match.groupValues[1].toIntOrNull() ?: return 0L
            val unit = match.groupValues[2].lowercase()
            val now = System.currentTimeMillis()
            return when {
                unit.startsWith("second") -> now - TimeUnit.SECONDS.toMillis(amount.toLong())
                unit.startsWith("minute") -> now - TimeUnit.MINUTES.toMillis(amount.toLong())
                unit.startsWith("hour") -> now - TimeUnit.HOURS.toMillis(amount.toLong())
                unit.startsWith("day") -> now - TimeUnit.DAYS.toMillis(amount.toLong())
                unit.startsWith("week") -> now - TimeUnit.DAYS.toMillis(amount.toLong() * 7)
                unit.startsWith("month") -> now - TimeUnit.DAYS.toMillis(amount.toLong() * 30)
                unit.startsWith("year") -> now - TimeUnit.DAYS.toMillis(amount.toLong() * 365)
                else -> 0L
            }
        }
        if (trimmed.contains("yesterday")) return System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
        if (trimmed.contains("just now")) return System.currentTimeMillis()
        return runCatching {
            ABSOLUTE_DATE_FORMAT.parse(trimmed)?.time ?: 0L
        }.getOrDefault(0L)
    }

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        private val CHAPTER_NUM_REGEX = Regex("-chapter-(\\d+)")

        private val RELATIVE_DATE_REGEX = Regex("""(\d+)\s+(second|minute|hour|day|week|month|year)s?\s+ago""", RegexOption.IGNORE_CASE)

        private val ABSOLUTE_DATE_FORMAT = SimpleDateFormat("MMMM d, yyyy", Locale.US)
    }
}
