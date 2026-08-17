package eu.kanade.tachiyomi.extension.en.manhuaplus

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
 * ManhuaPlus (https://manhuaplus.top) — an ASP.NET WebForms (NetTruyen theme)
 * reader. Everything is plain server-rendered HTML.
 *
 *     Browse : /all-manga[ /<N>]                    (latest, last_update sort)
 *              /all-manga[/<N>]?sort=views_month    (most popular)
 *              /genres/<slug>[/<N>]                 (genre taxonomy)
 *     Search : /search/?keyword=<q> and /search/<N>/?keyword=<q>
 *     Manga  : /manga/<slug>  -> h1 title, div.col-image img cover,
 *              li.author.row a, li.status.row p.col-xs-8 status,
 *              li.kind.row a[href*=/genres/] tags, div.detail-content p
 *              synopsis; all chapters (newest first) in #nt_listchapter
 *     Chapter: /manga/<slug>/chapter-<n> -> #image_container img (the images
 *              live on cdn.manhuaplus.cc, no hotlink protection)
 *
 * Pagination is li.hidden "Page X / Y" inside ul.pagination.
 */
class ManhuaPlus : HttpSource() {

    override val name = "ManhuaPlus"
    override val baseUrl = "https://manhuaplus.top"
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
        GET(listUrl("/all-manga", page, sort = "views_month"), headers)

    override fun popularMangaParse(response: Response): MangasPage =
        parseList(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET(listUrl("/all-manga", page, sort = null), headers)

    override fun latestUpdatesParse(response: Response): MangasPage =
        parseList(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val genreFilter = filters.filterIsInstance<GenreList>().firstOrNull()
        val genre = genreFilter
            ?.state
            ?.filter { it.isIncluded() }
            ?.firstOrNull()
            ?.key

        val url = when {
            query.isNotBlank() -> {
                val encoded = URLEncoder.encode(query, "utf-8")
                if (page == 1) "$baseUrl/search/?keyword=$encoded"
                else "$baseUrl/search/$page/?keyword=$encoded"
            }
            genre != null -> listUrl("/genres/$genre", page, sort = null)
            else -> listUrl("/all-manga", page, sort = null)
        }
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage =
        parseList(response)

    private fun listUrl(path: String, page: Int, sort: String?): String {
        val base = if (page == 1) baseUrl + path else "$baseUrl$path/$page"
        return if (sort == null) base else "$base/?sort=$sort"
    }

    private fun parseList(response: Response): MangasPage {
        val document = response.asDocument()
        val mangas = document.select(LIST_ITEM_SELECTOR).mapNotNull(::mangaFromElement)
        return MangasPage(mangas, hasNextPage(document))
    }

    private fun mangaFromElement(element: Element): SManga? {
        val link = element.selectFirst("a.jtip") ?: element.selectFirst("h3 a") ?: return null
        val title = link.text().trim()
        if (title.isEmpty()) return null
        return SManga.create().apply {
            setUrlWithoutDomain(link.absUrl("href"))
            this.title = title
            thumbnail_url = element.selectFirst("img")?.let { imageFromElement(it) } ?: ""
        }
    }

    private fun hasNextPage(document: Document): Boolean {
        val pager = document.selectFirst("ul.pagination li.hidden")?.text().orEmpty()
        val match = PAGER_REGEX.find(pager) ?: return false
        val current = match.groupValues[1].toIntOrNull() ?: return false
        val total = match.groupValues[2].toIntOrNull() ?: return false
        return current < total
    }

    // =========================== Manga Details ===========================

    override fun getMangaUrl(manga: SManga): String =
        if (manga.url.startsWith("http")) manga.url else baseUrl + manga.url

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asDocument()
        return SManga.create().apply {
            title = document.selectFirst("h1")?.text()?.trim().orEmpty()
            setUrlWithoutDomain(response.request.url.toString())
            document.select("li.author.row a").eachText().joinToString(", ")
                .takeIf { it.isNotBlank() }
                ?.let { author = it }
            document.select("li.kind.row a[href*='/genres/']").eachText().joinToString()
                .takeIf { it.isNotBlank() }
                ?.let { genre = it }
            document.selectFirst("li.status.row p.col-xs-8")?.text()?.trim()?.let { text ->
                status = when {
                    text.contains("ongoing", true) -> SManga.ONGOING
                    text.contains("hiatus", true) -> SManga.ON_HIATUS
                    text.contains("drop", true) || text.contains("discontinue", true) -> SManga.CANCELLED
                    text.contains("completed", true) -> SManga.COMPLETED
                    else -> SManga.UNKNOWN
                }
            }
            document.selectFirst("div.detail-content p")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { description = it }
            document.selectFirst("div.col-image img")?.let { thumbnail_url = imageFromElement(it) ?: "" }
        }
    }

    // ============================== Chapters =============================

    override fun getChapterUrl(chapter: SChapter): String =
        if (chapter.url.startsWith("http")) chapter.url else baseUrl + chapter.url

    override fun chapterListRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asDocument()
        return document.select(CHAPTER_SELECTOR).mapNotNull { element ->
            val link = element.selectFirst("div.chapter a") ?: return@mapNotNull null
            val chapterName = link.text().trim()
            SChapter.create().apply {
                url = link.absUrl("href")
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
        element.hasAttr("data-original") -> element.absUrl("data-original")
        element.hasAttr("data-src") -> element.absUrl("data-src")
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

        private const val LIST_ITEM_SELECTOR = "div.item"
        private const val CHAPTER_SELECTOR = "#nt_listchapter li.row"
        private const val PAGE_SELECTOR = "#image_container img"

        private val PAGER_REGEX = Regex("""Page\s*(\d+)\s*/\s*(\d+)""")
        private val CHAPTER_NUMBER_REGEX = Regex("""(\d+(?:\.\d+)?)""")
    }
}

// Mirrors the genre taxonomy in the site's "Genres" header menu.
private val GENRES = listOf(
    "Action" to "action",
    "Adaptation" to "adaptation",
    "Adult" to "adult",
    "Adventure" to "adventure",
    "Aliens" to "aliens",
    "Animals" to "animals",
    "Award Winning" to "award-winning",
    "Blood" to "blood",
    "Cartoon" to "cartoon",
    "Comedy" to "comedy",
    "Cooking" to "cooking",
    "Crime" to "crime",
    "Delinquents" to "delinquents",
    "Demons" to "demons",
    "Drama" to "drama",
    "Dungeons" to "dungeons",
    "Ecchi" to "ecchi",
    "Fantasy" to "fantasy",
    "Fighting" to "fighting",
    "Full Color" to "full-color",
    "Genderswap" to "genderswap",
    "Ghosts" to "ghosts",
    "Gore" to "gore",
    "Gyaru" to "gyaru",
    "Harem" to "harem",
    "Historical" to "historical",
    "Horror" to "horror",
    "Isekai" to "isekai",
    "Live action" to "live-action",
    "Loli" to "loli",
    "Long Strip" to "long-strip",
    "Mafia" to "mafia",
    "Magic" to "magic",
    "Magical Girls" to "magical-girls",
    "Manhua" to "manhua",
    "Manhwa" to "manhwa",
    "Martial Arts" to "martial-arts",
    "Mature" to "mature",
    "Mecha" to "mecha",
    "Medical" to "medical",
    "Military" to "military",
    "Monster Girls" to "monster-girls",
    "Monsters" to "monsters",
    "Music" to "music",
    "Mystery" to "mystery",
    "Official Colored" to "official-colored",
    "Op-Mc" to "op-mc",
    "Philosophical" to "philosophical",
    "Police" to "police",
    "Post-Apocalyptic" to "post-apocalyptic",
    "Psychological" to "psychological",
    "Reincarnation" to "reincarnation",
    "Returner" to "returner",
    "Revenge" to "revenge",
    "Romance" to "romance",
    "Ruthless Protagonist" to "ruthless-protagonist",
    "School Life" to "school-life",
    "Sci-Fi" to "sci-fi",
    "Seinen" to "seinen",
    "Shounen" to "shounen",
    "Shounen Ai" to "shounen-ai",
    "Slice of life" to "slice-of-life",
    "Smart MC" to "smart-mc",
    "Sports" to "sports",
    "Superhero" to "superhero",
    "Supernatural" to "supernatural",
    "Survival" to "survival",
    "Thriller" to "thriller",
    "Time Travel" to "time-travel",
    "Traditional Games" to "traditional-games",
    "Tragedy" to "tragedy",
    "Vampires" to "vampires",
    "Video Games" to "video-games",
    "Villainess" to "villainess",
    "Virtual Reality" to "virtual-reality",
    "Web Comic" to "web-comic",
    "Webtoon" to "webtoon",
    "Wuxia" to "wuxia",
    "Zombies" to "zombies",
)
