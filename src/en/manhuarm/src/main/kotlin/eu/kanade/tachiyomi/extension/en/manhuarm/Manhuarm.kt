package eu.kanade.tachiyomi.extension.en.manhuarm

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.HttpUrl.Companion.toHttpUrl
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
 * Manhuarm (https://manhuarmtl.com, alias manhuarmmtl.com) — WordPress Madara
 * theme with a custom hero/listing skin. Contains adult genres (NSFW).
 *
 *     Browse   : /manga/?m_orderby=trending  (+ /manga/page/N/?m_orderby=...)
 *     Latest   : /manga/?m_orderby=latest     (same pagination)
 *     Search   : /?post_type=wp-manga&s=<q>  (+ &pg=N for page > 1)
 *                results in #mrm-results .mrm-r-item, paginated via pg=N
 *                (standard /page/N/ search URLs 404 on this site)
 *     Manga    : /manga/<slug>/  -> hero section (.mrm-hero__*) + genres,
 *                chapters in <li class="wp-manga-chapter">
 *     Chapter  : /manga/<slug>/chapter-<num>/ -> <div class="reading-content">
 *                images (hosted on cdn.manhuarmmtl.com)
 *
 * Date labels on chapters are relative ("2 minutes ago", "1 day ago") or
 * absolute ("August 4, 2026"); parsed best-effort with 0 fallback.
 */
class Manhuarm : HttpSource() {

    override val name = "Manhuarm"
    override val baseUrl = "https://manhuarmtl.com"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", BROWSER_UA)
        .set("Referer", "$baseUrl/")
        .set("Accept-Language", "en-US,en;q=0.5")

    // =========================== Browse & Search =========================

    override fun popularMangaRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/manga/?m_orderby=trending"
        } else {
            "$baseUrl/manga/page/$page/?m_orderby=trending"
        }
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage = parseCardList(response)

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/manga/?m_orderby=latest"
        } else {
            "$baseUrl/manga/page/$page/?m_orderby=latest"
        }
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = parseCardList(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = if (query.isBlank()) {
            if (page == 1) "$baseUrl/manga/?m_orderby=trending"
            else "$baseUrl/manga/page/$page/?m_orderby=trending"
        } else {
            val q = URLEncoder.encode(query, "utf-8")
            if (page == 1) "$baseUrl/?post_type=wp-manga&s=$q"
            else "$baseUrl/?post_type=wp-manga&s=$q&pg=$page"
        }
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body?.string() ?: throw IOException("Empty response body"))
        val items = doc.select("#mrm-results .mrm-r-item")
        if (items.isEmpty()) return MangasPage(emptyList(), false)

        val mangas = items.mapNotNull { item ->
            val link = item.selectFirst(".mrm-r-item__link") ?: return@mapNotNull null
            val url = link.absUrl("href")
            if (url.isBlank()) return@mapNotNull null
            SManga.create().apply {
                this.url = url
                title = link.attr("title").ifBlank { item.selectFirst(".mrm-r-item__title")?.text().orEmpty() }
                thumbnail_url = item.selectFirst(".mrm-r-item__art img")?.absUrl("src").orEmpty()
            }
        }
        val hasNextPage = doc.selectFirst("a.mrm-pager__btn[rel=next]") != null
        return MangasPage(mangas, hasNextPage)
    }

    private fun parseCardList(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body?.string() ?: throw IOException("Empty response body"))
        val mangas = mutableListOf<SManga>()
        for (card in doc.select(".manga-card")) {
            val titleEl = card.selectFirst(".manga-title a") ?: continue
            val thumbEl = card.selectFirst(".manga-thumb a") ?: continue
            val url = thumbEl.absUrl("href")
            if (url.isBlank()) continue
            mangas.add(SManga.create().apply {
                this.url = url
                title = titleEl.text().trim()
                thumbnail_url = thumbEl.selectFirst("img")?.absUrl("src").orEmpty()
            })
        }
        val hasNextPage = doc.selectFirst("#navigation-ajax") != null
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

        manga.title = doc.selectFirst("h1.mrm-hero__title")?.text()?.trim()
            ?: doc.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()

        manga.thumbnail_url = doc.selectFirst(".mrm-hero__cover img")?.absUrl("src").orEmpty()
            .ifBlank { doc.selectFirst(".summary_image img")?.absUrl("src").orEmpty() }

        manga.description = doc.selectFirst(".description-summary .summary__content")?.text()?.trim()
            ?.ifBlank { null }
            ?: doc.selectFirst(".summary__content")?.text()?.trim()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()

        for (item in doc.select(".post-content_item")) {
            val heading = item.selectFirst(".summary-heading")?.text()?.trim() ?: continue
            when (heading) {
                "Author(s)", "Author" -> {
                    val author = item.selectFirst(".author-content")
                        ?.select("a")
                        ?.joinToString(", ") { it.text() }
                        ?.trim()
                    if (!author.isNullOrBlank() && !author.contains("Warning")) manga.author = author
                }
                "Status" -> {
                    val status = item.selectFirst(".summary-content")?.text()?.trim().orEmpty()
                    manga.status = when {
                        status.contains("ongoing", true) -> SManga.ONGOING
                        status.contains("completed", true) -> SManga.COMPLETED
                        status.contains("hiatus", true) -> SManga.ON_HIATUS
                        else -> SManga.UNKNOWN
                    }
                }
            }
        }
        if (manga.status == SManga.UNKNOWN) {
            doc.selectFirst(".mrm-hero__status .summary-content")?.text()?.trim()?.let { status ->
                manga.status = when {
                    status.contains("ongoing", true) -> SManga.ONGOING
                    status.contains("completed", true) -> SManga.COMPLETED
                    status.contains("hiatus", true) -> SManga.ON_HIATUS
                    else -> SManga.UNKNOWN
                }
            }
        }

        val genres = doc.select(".mrm-genres__list a").joinToString(", ") { it.text() }
        if (genres.isNotBlank()) manga.genre = genres

        return manga
    }

    // ============================== Chapters =============================

    override fun getChapterUrl(chapter: SChapter): String =
        if (chapter.url.startsWith("http")) chapter.url else baseUrl + chapter.url

    override fun chapterListRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = Jsoup.parse(response.body?.string() ?: return emptyList())
        val chapters = mutableListOf<SChapter>()
        for (li in doc.select(".wp-manga-chapter")) {
            val link = li.selectFirst("a[href*='/chapter-']") ?: li.selectFirst("a")
                ?: continue
            val href = link.absUrl("href")
            if (href.isBlank()) continue
            val num = CHAPTER_NUM_REGEX.find(href)?.groupValues?.getOrNull(1)
            val chapter = SChapter.create().apply {
                url = href
                name = link.text().trim().ifBlank { if (num != null) "Chapter $num" else "" }
                chapter_number = num?.toFloatOrNull() ?: 0f
                date_upload = li.selectFirst(".chapter-release-date")?.text()
                    ?.let { parseDate(it) }
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
        val pages = mutableListOf<Page>()
        for (img in doc.select("div.reading-content img")) {
            val raw = img.attr("src").trim()
                .ifBlank { img.attr("data-src").trim() }
                .ifBlank { img.attr("data-lazy-src").trim() }
            if (raw.isBlank() || raw.startsWith("data:")) continue
            val url = if (raw.startsWith("http")) raw else "$baseUrl/$raw"
            pages.add(Page(pages.size, imageUrl = url))
        }
        return pages
    }

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl ?: page.url, headers.newBuilder().set("Referer", "$baseUrl/").build())

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================= Utilities =============================

    private fun parseDate(text: String): Long {
        val trimmed = text.trim()
        val relative = RELATIVE_DATE_REGEX.find(trimmed)
        if (relative != null) {
            val amount = relative.groupValues[1].toIntOrNull() ?: return 0L
            val unit = relative.groupValues[2].lowercase()
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

        private val CHAPTER_NUM_REGEX = Regex(".*-chapter-(\\d+)/?$")

        private val RELATIVE_DATE_REGEX =
            Regex("""(\d+)\s+(second|minute|hour|day|week|month|year)s?\s+ago""", RegexOption.IGNORE_CASE)

        private val ABSOLUTE_DATE_FORMAT = SimpleDateFormat("MMMM d, yyyy", Locale.US)
    }
}
