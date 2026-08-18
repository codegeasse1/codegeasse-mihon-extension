package eu.kanade.tachiyomi.extension.en.pornhwa

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URLEncoder

/*
 * Pornhwa (https://pornhwa.pro) — a Qwik SSR reader sharing the manhwa18.com
 * image CDN. Every page is plain server-rendered HTML (the client only adds an
 * infinite-scroll "Load More" through a Qwik action we never need to call).
 *
 *     Browse : /type/manhwa/[/?page=<N>]  (latest; newer counterpart of the
 *              legacy /manhwa-list/ which still serves dead hentairead.com
 *              covers — must NOT be used)
 *              /popular/[/?page=<N>]       (most viewed)
 *              /tax/genre/<slug>[/?page=<N>]
 *     Search : /search/<query>[/?page=<N>] (URL-encoded query, NOT slugified:
 *              spaces must stay %20 or the results page comes back empty)
 *     Manga  : /manhwa/<slug>/  -> h1[class*=drop-shadow-solid] title,
 *              meta[property=og:image] cover, p:containsOwn(Author) block,
 *              div[class*=bg-green-800] status, a[href*=/tax/genre/] tags and
 *              div.mt-4.w-full > p synopsis; all chapters (newest first) sit
 *              in div.mt-4.flex.max-h-96.flex-col.gap-2.overflow-y-auto
 *     Chapter: /manhwa/<slug>/chapter-<n> -> div.bg-reader img[data-src].
 *              The site has rotated page-image CDNs (s1.manhwature.com signed
 *              URLs now; previously cdn.manhwature.com WP-manga paths that
 *              contain no "/chapter-"), so we scope on the reader container
 *              class instead of filtering on the URL.
 *
 * Pagination quirk: ?page=N grows the SSR page cumulatively (page N renders
 * pages 1..N, adding 18 new cards per page), so for page > 1 we only return
 * the new tail and derive hasNextPage from its size. Uncensored/adult titles.
 */
class Pornhwa : HttpSource() {

    override val name = "Pornhwa"
    override val baseUrl = "https://pornhwa.pro"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder =
        super.headersBuilder()
            .set("User-Agent", BROWSER_UA)
            .set("Referer", "$baseUrl/")

    // ============================== Filters ==============================

    override fun getFilterList(): FilterList = FilterList(
        GenreList(GENRES.map { Genre(it.first, it.second) }),
    )

    private class Genre(name: String, val key: String) : Filter.TriState(name)

    private class GenreList(genres: List<Genre>) : Filter.Group<Genre>("Genres", genres)

    // =========================== Browse & Search =========================

    override fun popularMangaRequest(page: Int): Request =
        GET(listUrl("/popular/", page), headers)

    override fun popularMangaParse(response: Response): MangasPage =
        parseList(response, requestPage(response), isLatest = false)

    override fun latestUpdatesRequest(page: Int): Request =
        GET(listUrl("/type/manhwa/", page), headers)

    override fun latestUpdatesParse(response: Response): MangasPage =
        parseList(response, requestPage(response), isLatest = true)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val genreFilter = filters.filterIsInstance<GenreList>().firstOrNull()
        val genre = genreFilter
            ?.state
            ?.filter { it.isIncluded() }
            ?.joinToString(",") { it.key }
            ?.takeIf { it.isNotBlank() }

        val url = when {
            query.isNotBlank() ->
                listUrl("/search/${URLEncoder.encode(query, "utf-8").replace("+", "%20")}/", page)
            genre != null ->
                listUrl("/tax/genre/$genre/", page)
            else ->
                listUrl("/type/manhwa/", page)
        }
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage =
        parseList(response, requestPage(response), isLatest = false)

    private fun listUrl(path: String, page: Int): String =
        if (page == 1) baseUrl + path else "$baseUrl$path?page=$page"

    private fun parseList(response: Response, page: Int, isLatest: Boolean): MangasPage {
        val document = response.asDocument()
        val cards = document.select(CARD_SELECTOR).mapNotNull(::mangaFromCard)

        val (mangas, hasNextPage) = if (page == 1) {
            cards to (if (isLatest) cards.isNotEmpty() else cards.size == PAGE_SIZE)
        } else {
            val previousCumulative = if (isLatest) {
                PAGE_SIZE * (page - 1) - 1
            } else {
                PAGE_SIZE * (page - 1)
            }
            val newCount = minOf(maxOf(0, cards.size - previousCumulative), PAGE_SIZE)
            val newMangas = if (newCount > 0) cards.takeLast(newCount) else emptyList()
            newMangas to (newCount == PAGE_SIZE)
        }
        return MangasPage(mangas, hasNextPage)
    }

    private fun requestPage(response: Response): Int {
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        return if (page < 1) 1 else page
    }

    private fun mangaFromCard(img: Element): SManga? {
        val link = img.parent() ?: return null
        if (!CARD_URL_REGEX.matches(link.attr("href"))) return null
        val cardTitle = img.attr("alt").trim()
        if (cardTitle.isEmpty()) return null
        return SManga.create().apply {
            setUrlWithoutDomain(link.absUrl("href"))
            title = cardTitle
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
        return SManga.create().apply {
            title = document.selectFirst("h1[class*='drop-shadow-solid']")?.text()?.trim().orEmpty()
            setUrlWithoutDomain(response.request.url.toString())
            document.selectFirst("p:containsOwn(Author)")?.parent()?.ownText()?.trim()?.let {
                if (it.isNotBlank() && it != "-") author = it
            }
            document.select("a[href*='/tax/genre/']").eachText().joinToString()
                .takeIf { it.isNotBlank() }
                ?.let { genre = it }
            document.selectFirst("div[class*='bg-green-800']")?.ownText()?.trim()?.let { text ->
                status = when {
                    text.contains("on-going", true) -> SManga.ONGOING
                    text.contains("on-hold", true) || text.contains("hiatus", true) -> SManga.ON_HIATUS
                    text.contains("end", true) -> SManga.COMPLETED
                    text.contains("cancel", true) -> SManga.CANCELLED
                    else -> SManga.UNKNOWN
                }
            }
            document.selectFirst("div.mt-4.w-full > p")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { description = it }
            document.selectFirst("meta[property='og:image']")?.attr("content")
                ?.takeIf { it.isNotBlank() }
                ?.let { thumbnail_url = it }
        }
    }

    // ============================== Chapters =============================

    override fun getChapterUrl(chapter: SChapter): String =
        if (chapter.url.startsWith("http")) chapter.url else baseUrl + chapter.url

    override fun chapterListRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asDocument()
        return document.select(CHAPTER_LIST_SELECTOR).mapNotNull { element ->
            val chapterName = element.selectFirst("div p")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: element.text().trim()
            SChapter.create().apply {
                url = element.absUrl("href")
                name = chapterName
                chapter_number = CHAPTER_NUMBER_REGEX.find(chapterName)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
            }
        }
    }

    // =============================== Pages ===============================

    override fun pageListRequest(chapter: SChapter): Request =
        GET(getChapterUrl(chapter), headers)

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asDocument()
        val chapterUrl = response.request.url.toString()
        return document.select(PAGE_SELECTOR).mapIndexedNotNull { index, element ->
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
        element.hasAttr("src") && element.attr("src").startsWith("http") -> element.absUrl("src")
        else -> null
    }

    private fun Response.asDocument(): Document {
        val body = body?.string() ?: throw IOException("Empty response body")
        return Jsoup.parse(body, request.url.toString())
    }

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        private const val PAGE_SIZE = 18

        private const val CARD_SELECTOR = "a[href^='/manhwa/'] img"
        private val CARD_URL_REGEX = Regex("""^/manhwa/[^/]+/$""")
        private const val CHAPTER_LIST_SELECTOR =
            "div.mt-4.flex.max-h-96.flex-col.gap-2.overflow-y-auto a[href*='/chapter-']"
        private const val PAGE_SELECTOR = "div.bg-reader img[data-src]"

        private val CHAPTER_NUMBER_REGEX = Regex("""(\d+(?:\.\d+)?)""")
    }
}

// Mirrors the /tax/genre/ taxonomy on https://pornhwa.pro (nhentai-style tags).
private val GENRES = listOf(
    "Adult" to "adult",
    "Ahegao" to "ahegao",
    "Anal" to "anal",
    "BDSM" to "bdsm",
    "Big Ass" to "big-ass",
    "Big Breasts" to "big-breasts",
    "Big Nipples" to "big-nipples",
    "Big Penis" to "big-penis",
    "Blowjob" to "blowjob",
    "Bondage" to "bondage",
    "Bukkake" to "bukkake",
    "Creampie" to "creampie",
    "Cumflation" to "cumflation",
    "Deep Throat" to "deepthroat",
    "Double Penetration" to "double-penetration",
    "Doujinshi" to "doujinshi",
    "Femdom" to "femdom",
    "Footjob" to "footjob",
    "Full Color" to "full-color",
    "Giantess" to "giantess",
    "Glasses" to "glasses",
    "Group" to "group",
    "Harem" to "harem",
    "Impregnation" to "impregnation",
    "Incest" to "incest",
    "Lactation" to "lactation",
    "Manga" to "manga",
    "Manhwa" to "manhwa",
    "Masturbation" to "masturbation",
    "MILF" to "milf",
    "Milking" to "milking",
    "Mind Break" to "mind-break",
    "Monster Girl" to "monster-girl",
    "Netorare (NTR)" to "ntr",
    "Nurse" to "nurse",
    "Paizuri" to "paizuri",
    "Pregnant" to "pregnant",
    "Prostitution" to "prostitution",
    "Rape" to "rape",
    "Sex Toys" to "sex-toys",
    "Shemale" to "shemale",
    "Slave" to "slave",
    "Smut" to "smut",
    "Squirting" to "squirting",
    "Stockings" to "stockings",
    "Swimsuit" to "swimsuit",
    "Teacher" to "teacher",
    "Tentacles" to "tentacles",
    "Uncensored" to "uncensored",
    "Webtoon" to "webtoon",
    "Yaoi" to "yaoi",
    "Yuri" to "yuri",
)
