package eu.kanade.tachiyomi.extension.en.kuramanga

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

/*
 * KuraManga (https://kuramanga.com) is a custom PostgREST-backed reader
 * (not Madara). Listings are server-rendered on the homepage, and the
 * search page is a client-side SPA that talks to two thin AJAX endpoints:
 *
 *     Ids  : GET /search?ajax=1&ids=1&name=<q>&genre=<g>&status=<s>
 *            -> { "ids": [...], "total": N }
 *     Pick : GET /search?ajax=1&pick=<id,id,...>
 *            -> { "data": [ {id, title, normalized_title, thumb, summary,
 *                            status, genres, latestChapter, ...} ] }
 *
 * Chapter pages are server-rendered: #chapterImages img[src] carries every
 * page (hosted on the shadowabyss.com CDN, which answers requests carrying
 * a browser User-Agent + Referer from kuramanga.com).
 *
 *     Popular : homepage "Popular Today" carousel (section > .sp-card);
 *               page > 1 continues through the full catalog via AJAX ids
 *     Latest  : homepage ".update-row" grid (21 rows); page > 1 continues
 *               through the full catalog via AJAX ids
 *     Search  : AJAX ids -> pick (18 per page)
 *     Details : /<slug>/ -> h1.manga-title, .mp-byline, .genre-list,
 *               .mp-synopsis .summary-inner, .mp-status
 *     Chapters: .chapter-list .chapter-item a[href] + <time> (newest first)
 *     Pages   : /<slug>/chapter-<n>/ -> #chapterImages img
 */
class KuraManga : HttpSource() {

    override val name = "KuraManga"
    override val baseUrl = "https://kuramanga.com"
    override val lang = "en"
    override val supportsLatest = true

    private val gson = Gson()

    // The search-id lookup runs synchronously inside searchMangaRequest, so
    // it uses a plain OkHttpClient (no Injekt graph dependency -> Tachidesk-safe).
    private val directClient: OkHttpClient by lazy { OkHttpClient() }

    override fun headersBuilder(): Headers.Builder =
        super.headersBuilder()
            .set("User-Agent", BROWSER_UA)
            .set("Referer", "$baseUrl/")

    private fun apiHeaders(): Headers = headersBuilder().build()

    // ========================== Search & Browse ===========================

    override fun getFilterList(): FilterList = FilterList(
        GenreFilter(),
        StatusFilter(),
    )

    override fun popularMangaRequest(page: Int): Request {
        if (page <= 1) return GET(baseUrl, apiHeaders())
        return browseRequest(page)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        if (isHomepage(response)) {
            val doc = response.asJsoup()
            return MangasPage(parsePopular(doc), hasNextPage = true)
        }
        return parseBrowseResponse(response)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        if (page <= 1) return GET(baseUrl, apiHeaders())
        return browseRequest(page)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        if (isHomepage(response)) {
            val doc = response.asJsoup()
            return MangasPage(parseLatest(doc), hasNextPage = true)
        }
        return parseBrowseResponse(response)
    }

    private var searchTotal = 0
    private var searchPage = 1

    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request {
        val genreName = filters.filterIsInstance<GenreFilter>()
            .firstOrNull()?.selectedName()
        val statusSlug = filters.filterIsInstance<StatusFilter>()
            .firstOrNull()?.selectedSlug()

        val params = mutableListOf<String>()
        if (query.isNotBlank()) params += "name=${URLEncoder.encode(query.trim(), "UTF-8")}"
        if (genreName != null) params += "genre=${URLEncoder.encode(genreName, "UTF-8")}"
        if (statusSlug != null) params += "status=$statusSlug"
        val queryStr = params.joinToString("&")

        return browseRequest(page, queryStr)
    }

    override fun searchMangaParse(response: Response): MangasPage =
        parseBrowseResponse(response)

    private fun isHomepage(response: Response): Boolean =
        response.request.url.queryParameter("pick") == null

    private fun browseRequest(page: Int, queryStr: String = ""): Request {
        searchPage = page
        searchTotal = 0
        val ids = fetchAllIds(queryStr)
        val slice = ids.drop((page - 1) * PAGE_SIZE).take(PAGE_SIZE)
        val pickUrl = "$baseUrl/search?ajax=1&pick=${slice.joinToString(",")}"
        return GET(pickUrl, apiHeaders())
    }

    private fun fetchAllIds(queryStr: String): List<String> {
        val ids = mutableListOf<String>()
        runCatching {
            directClient.newCall(GET("$baseUrl/search?ajax=1&ids=1&$queryStr", apiHeaders()))
                .execute()
        }.getOrNull()?.use { resp ->
            if (resp.isSuccessful) {
                runCatching {
                    val obj = gson.fromJson(resp.body?.string(), JsonObject::class.java)
                    obj.getAsJsonArray("ids").forEach { ids.add(it.asString) }
                    searchTotal = obj.get("total")?.takeIf { !it.isJsonNull }?.asInt ?: ids.size
                }
            }
        }
        return ids
    }

    private fun parseBrowseResponse(response: Response): MangasPage {
        val body = response.body?.string() ?: return MangasPage(emptyList(), false)
        val root = runCatching { gson.fromJson(body, JsonObject::class.java) }.getOrNull()
        val data = root?.getAsJsonArray("data") ?: JsonArray()
        val mangas = data.mapNotNull { el -> (el as? JsonObject)?.toSManga() }
        return MangasPage(mangas, hasNextPage = searchPage * PAGE_SIZE < searchTotal)
    }

    // ========================== Manga Details =============================

    override fun getMangaUrl(manga: SManga): String = manga.url

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(manga.url, apiHeaders())

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()
        val status = when (doc.selectFirst(".mp-status")?.text()?.trim()?.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus", "on hold" -> SManga.ON_HIATUS
            "canceled", "cancelled" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }

        return SManga.create().apply {
            title = doc.selectFirst("h1.manga-title")?.text()?.trim()
                ?: doc.title().substringBefore(" - Read Online").trim()
            url = response.request.url.toString()
            thumbnail_url = doc.selectFirst("img[src*='shadowabyss.com']")?.absUrl("src")
            author = doc.select(".mp-cred-v").joinToString(", ") { it.text().trim() }
            artist = author
            this.status = status
            genre = doc.select(".genre-list .genre-chip").map { it.text().trim() }.distinct().joinToString()
            description = doc.selectFirst(".mp-synopsis .summary-inner")?.text()?.trim().orEmpty()
        }
    }

    // ============================= Chapters ===============================

    override fun chapterListRequest(manga: SManga): Request =
        GET(manga.url, apiHeaders())

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = response.asJsoup()
        val items = doc.select(".chapter-list .chapter-item")
        val count = items.size
        return items.mapIndexed { index, item ->
            val a = item.selectFirst("a[href]") ?: return@mapIndexed null
            SChapter.create().apply {
                url = a.absUrl("href")
                name = a.text().trim()
                date_upload = parseChapterDate(item.selectFirst("time")?.text())
                chapter_number = parseChapterNumber(name, url)
                    ?: (count - index).toFloat()
            }
        }.filterNotNull()
    }

    override fun getChapterUrl(chapter: SChapter): String = chapter.url

    // =============================== Pages ================================

    override fun pageListRequest(chapter: SChapter): Request =
        GET(chapter.url, apiHeaders())

    override fun pageListParse(response: Response): List<Page> {
        val doc = response.asJsoup()
        return doc.select("#chapterImages img").mapIndexed { index, img ->
            val url = img.absUrl("src")
            Page(index, url, url)
        }
    }

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl ?: page.url, apiHeaders())

    // ============================= Utilities ==============================

    private fun parsePopular(doc: Document): List<SManga> {
        val section = doc.select("section.section-block").firstOrNull {
            it.selectFirst("h2.section-title")?.text()?.contains("Popular Today") == true
        } ?: return emptyList()
        return section.select(".sp-card").mapNotNull { card ->
            val href = card.absUrl("href")
            if (href.isBlank()) return@mapNotNull null
            SManga.create().apply {
                url = href
                title = card.attr("title").ifBlank { card.selectFirst("h3")?.text() }.orEmpty().trim()
                thumbnail_url = card.selectFirst("img")?.absUrl("src")
            }
        }
    }

    private fun parseLatest(doc: Document): List<SManga> =
        doc.select(".update-row:not(.lu-reserve)").mapNotNull { row ->
            val a = row.selectFirst(".update-thumb")
            val link = row.selectFirst(".update-series-link")
            val href = a?.absUrl("href") ?: link?.absUrl("href")
            if (href.isNullOrBlank()) return@mapNotNull null
            SManga.create().apply {
                url = href
                title = link?.text()?.trim() ?: a?.attr("title").orEmpty()
                thumbnail_url = row.selectFirst(".update-thumb img")?.absUrl("src")
            }
        }

    // ============================== Filters ===============================

    private class GenreFilter : Filter.Select<String>(
        "Genre",
        arrayOf("All") + GENRES.map { it.first },
    ) {
        fun selectedName(): String? {
            val idx = state ?: 0
            return if (idx > 0 && idx <= GENRES.size) GENRES[idx - 1].first else null
        }
    }

    private class StatusFilter : Filter.Select<String>(
        "Status",
        STATUSES.map { it.first }.toTypedArray(),
    ) {
        fun selectedSlug(): String? {
            val idx = state ?: 0
            return if (idx > 0 && idx < STATUSES.size) STATUSES[idx].second else null
        }
    }

    companion object {
        private const val PAGE_SIZE = 18

        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        private val STATUSES = listOf(
            "All" to "",
            "Ongoing" to "ongoing",
            "Completed" to "completed",
            "Hiatus" to "hiatus",
            "On Hold" to "on_hold",
            "Canceled" to "canceled",
        )

        internal val GENRES = listOf(
            "+100 Chapter" to "+100 Chapter",
            "Academy" to "Academy",
            "Action" to "Action",
            "Actionfantasy" to "Actionfantasy",
            "Adaptation" to "Adaptation",
            "Adult" to "Adult",
            "Adventure" to "Adventure",
            "Ai" to "Ai",
            "Aliens" to "Aliens",
            "Animals" to "Animals",
            "Anthology" to "Anthology",
            "Apocalypse" to "Apocalypse",
            "Authentic Martial Arts" to "Authentic Martial Arts",
            "Award Winning" to "Award Winning",
            "Badguy" to "Badguy",
            "Based on a Novel" to "Based on a Novel",
            "Battle Action" to "Battle Action",
            "BDSM" to "BDSM",
            "BL" to "BL",
            "Borderline H" to "Borderline H",
            "Boys Love" to "Boys Love",
            "Bullying" to "Bullying",
            "Cheating/infidelity" to "Cheating/infidelity",
            "Cider" to "Cider",
            "Cohabitation" to "Cohabitation",
            "College" to "College",
            "College life" to "College life",
            "Comedy" to "Comedy",
            "Comic" to "Comic",
            "Comingofage" to "Comingofage",
            "Cooking" to "Cooking",
            "Crazy MC" to "Crazy MC",
            "Crime" to "Crime",
            "Crossdressing" to "Crossdressing",
            "Cultivation" to "Cultivation",
            "Curse" to "Curse",
            "Dark Fantasy" to "Dark Fantasy",
            "Delinquents" to "Delinquents",
            "Demon" to "Demon",
            "Demons" to "Demons",
            "Difference in Status" to "Difference in Status",
            "Drama" to "Drama",
            "Dungeons" to "Dungeons",
            "Ecchi" to "Ecchi",
            "Elementals" to "Elementals",
            "Elf" to "Elf",
            "Explicit Sex" to "Explicit Sex",
            "Fantasia" to "Fantasia",
            "Fantasy" to "Fantasy",
            "Fight" to "Fight",
            "Folklore" to "Folklore",
            "Full Color" to "Full Color",
            "Game" to "Game",
            "Gang" to "Gang",
            "Gender Bender" to "Gender Bender",
            "Genderswap" to "Genderswap",
            "Genius" to "Genius",
            "Genius MC" to "Genius MC",
            "Ghosts" to "Ghosts",
            "Girls Love" to "Girls Love",
            "GL" to "GL",
            "Gore" to "Gore",
            "Hardcore" to "Hardcore",
            "Harem" to "Harem",
            "Hentai" to "Hentai",
            "Hidden" to "Hidden",
            "Historical" to "Historical",
            "Horror" to "Horror",
            "Humiliation" to "Humiliation",
            "Hunters" to "Hunters",
            "Idols" to "Idols",
            "Illusion" to "Illusion",
            "Incest" to "Incest",
            "Isekai" to "Isekai",
            "Josei" to "Josei",
            "Legendary" to "Legendary",
            "Live" to "Live",
            "Long Strip" to "Long Strip",
            "Love Triangle" to "Love Triangle",
            "Lovecomedy" to "Lovecomedy",
            "Mafia" to "Mafia",
            "Magic" to "Magic",
            "Magical" to "Magical",
            "Manhua" to "Manhua",
            "Manhwa" to "Manhwa",
            "Married Woman" to "Married Woman",
            "Martial Arts" to "Martial Arts",
            "Martial Arts/historical Drama" to "Martial Arts/historical Drama",
            "Martialarts/historicaldrama" to "Martialarts/historicaldrama",
            "Mature" to "Mature",
            "Mecha" to "Mecha",
            "Medical" to "Medical",
            "Milf" to "Milf",
            "Military" to "Military",
            "Monster Girls" to "Monster Girls",
            "Monsters" to "Monsters",
            "Mother" to "Mother",
            "Mother and Daughter" to "Mother and Daughter",
            "Murim" to "Murim",
            "Music" to "Music",
            "Mystery" to "Mystery",
            "Myth" to "Myth",
            "Necromancer" to "Necromancer",
            "NTR" to "NTR",
            "Office Workers" to "Office Workers",
            "Omegaverse" to "Omegaverse",
            "One shot" to "One shot",
            "Orient" to "Orient",
            "Orientalromance" to "Orientalromance",
            "Original Novel" to "Original Novel",
            "Overpowered" to "Overpowered",
            "Overpoweredmc" to "Overpoweredmc",
            "Period" to "Period",
            "Philosophical" to "Philosophical",
            "Politics" to "Politics",
            "Post-Apocalyptic" to "Post-Apocalyptic",
            "Psychological" to "Psychological",
            "Regression" to "Regression",
            "Reincarnation" to "Reincarnation",
            "Return" to "Return",
            "Revenge" to "Revenge",
            "Reverse Harem" to "Reverse Harem",
            "Robots" to "Robots",
            "Romance" to "Romance",
            "Royal family" to "Royal family",
            "School" to "School",
            "School Life" to "School Life",
            "Sci-Fi" to "Sci-Fi",
            "Science Fiction" to "Science Fiction",
            "Seinen" to "Seinen",
            "Sentimental" to "Sentimental",
            "Serial" to "Serial",
            "Short Story" to "Short Story",
            "Shoujo" to "Shoujo",
            "Shounen" to "Shounen",
            "Sisters" to "Sisters",
            "Slice of Life" to "Slice of Life",
            "Smut" to "Smut",
            "Sport" to "Sport",
            "Sports" to "Sports",
            "Stepmother" to "Stepmother",
            "Superhero" to "Superhero",
            "Supernatural" to "Supernatural",
            "Superpower" to "Superpower",
            "Survival" to "Survival",
            "Swapping" to "Swapping",
            "Swordsman" to "Swordsman",
            "System" to "System",
            "Teacher" to "Teacher",
            "Threesome" to "Threesome",
            "Thriller" to "Thriller",
            "Time Travel" to "Time Travel",
            "Tower" to "Tower",
            "Traditional Games" to "Traditional Games",
            "Tragedy" to "Tragedy",
            "Transmigration" to "Transmigration",
            "Uncensored" to "Uncensored",
            "Urban" to "Urban",
            "Vampires" to "Vampires",
            "Video Games" to "Video Games",
            "Villain" to "Villain",
            "Villainess" to "Villainess",
            "Violence" to "Violence",
            "Virtual Reality" to "Virtual Reality",
            "Web Comic" to "Web Comic",
            "Webtoon" to "Webtoon",
            "Webtoons" to "Webtoons",
            "Wholesome" to "Wholesome",
            "Workplace" to "Workplace",
            "Wuxia" to "Wuxia",
            "Youth" to "Youth",
            "Yuri" to "Yuri",
            "Zombies" to "Zombies",
        )
    }
}

// ========================= Top-level helpers ==========================

private fun JsonObject.toSManga(): SManga? {
    val title = get("title")?.takeIf { !it.isJsonNull }?.asString ?: return null
    val slug = get("normalized_title")?.takeIf { !it.isJsonNull }?.asString ?: return null
    val status = when (get("status")?.takeIf { !it.isJsonNull }?.asString?.lowercase()) {
        "ongoing" -> SManga.ONGOING
        "completed" -> SManga.COMPLETED
        "hiatus", "on_hold" -> SManga.ON_HIATUS
        "canceled", "cancelled" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    return SManga.create().apply {
        this.title = title.trim()
        url = "https://kuramanga.com/$slug"
        thumbnail_url = get("thumb")?.takeIf { !it.isJsonNull }?.asString
        this.status = status
        genre = genresFrom(this@toSManga.get("genres"))
        author = get("author")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
        description = get("summary")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
    }
}

private fun genresFrom(el: com.google.gson.JsonElement?): String {
    if (el == null || el.isJsonNull) return ""
    return when {
        el.isJsonArray ->
            el.asJsonArray.mapNotNull { it.takeUnless { e -> e.isJsonNull }?.asString }.distinct().joinToString()
        else ->
            el.asString.split(",").map { it.trim() }.distinct().joinToString()
    }
}

private fun parseChapterDate(text: String?): Long {
    if (text.isNullOrBlank()) return 0L
    val rel = parseRelativeTime(text)
    if (rel != 0L) return rel
    return runCatching { dateFormat.parse(text.trim())?.time }.getOrNull() ?: 0L
}

private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

private fun parseRelativeTime(text: String?): Long {
    if (text.isNullOrBlank()) return 0L
    val t = text.lowercase()
    val now = System.currentTimeMillis()
    if (t.contains("yesterday")) return now - DAY_MS
    val m = Regex("""(\d+)\s*(minute|hour|day|week|month|year)s?""").find(t)
        ?: return 0L
    val n = m.groupValues[1].toLong()
    val mult = when (m.groupValues[2]) {
        "minute" -> MINUTE_MS
        "hour" -> HOUR_MS
        "day" -> DAY_MS
        "week" -> WEEK_MS
        "month" -> MONTH_MS
        "year" -> YEAR_MS
        else -> 0L
    }
    return now - n * mult
}

private fun parseChapterNumber(name: String, url: String): Float? {
    Regex("""chapter\s*([\d.,]+)""", RegexOption.IGNORE_CASE).find(name)?.let {
        return it.groupValues[1].replace(",", ".").toFloatOrNull()
    }
    Regex("""/chapter-([\d]+)""").find(url)?.let {
        return it.groupValues[1].toFloatOrNull()
    }
    return null
}

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 86_400_000L
private const val WEEK_MS = 604_800_000L
private const val MONTH_MS = 2_592_000_000L
private const val YEAR_MS = 31_536_000_000L
