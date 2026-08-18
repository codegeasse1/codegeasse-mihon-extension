package eu.kanade.tachiyomi.extension.en.yaoimangaonline

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import java.net.URLEncoder

/*
 * Yaoi Manga Online (https://yaoimangaonline.com) — a Wordpress/Herald blog-style
 * site. Adult yaoi doujinshi/manhwa/oneshots (isNsfw). Everything is plain HTML:
 *
 *     Popular : /page/N/                      (latest posts; blog listing)
 *     Search  : /page/N/?s=<query>            (Wordpress search)
 *     Category: /<cat>/page/N/                (e.g. /yaoi-doujinshi/page/1/)
 *     Tag     : /tag/<tag>/page/N/            (or ?tag=<tag> combined with a category)
 *     Details : post page -> h1.entry-title, .herald-post-thumbnail img,
 *               .entry-content > p (description), .meta-tags (genres),
 *               .entry-content > p:contains(Mangaka:) (author)
 *     Chapters: .mpp-toc a — each post is one series; its TOC links the
 *               individual chapter sub-pages (post multi-page).
 *     Pages   : chapter sub-page -> .entry-content img (full page images,
 *               same-domain /wp-content/uploads/... so no hotlink protection)
 *
 * Every listing is the same Herald "lay-i" post grid, so one parser is reused.
 */
class YaoiMangaOnline : HttpSource() {

    override val name = "Yaoi Manga Online"
    override val baseUrl = "https://yaoimangaonline.com"
    override val lang = "en"
    override val supportsLatest = false

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", BROWSER_UA)
        .set("Referer", "$baseUrl/")
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")

    // =========================== Browse ===========================

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/page/$page/", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(POST_SELECTOR).mapNotNull { element ->
            val url = element.absUrl("href")
            if (url.isBlank()) return@mapNotNull null
            SManga.create().apply {
                title = element.attr("title").ifBlank {
                    element.closest(".post")?.selectFirst(".entry-title")?.text() ?: ""
                }
                setUrlWithoutDomain(url)
                thumbnail_url = element.selectFirst("img")?.let { img ->
                    img.absUrl("src").ifBlank { img.absUrl("data-src") }
                }
            }
        }
        val hasNextPage = document.selectFirst(".herald-pagination > .next") != null
        return MangasPage(mangas, hasNextPage)
    }

    // ====================== Latest (unused) =======================

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException()

    // =========================== Search ===========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var category: String? = null
        var tag: String? = null
        filters.forEach {
            when (it) {
                is CategoryFilter -> category = it.selected()
                is TagFilter -> tag = it.selected()
                else -> {}
            }
        }

        val path = buildString {
            category?.let { append('/').append(it) }
            if (tag != null && category == null) append("/tag/").append(tag)
            append("/page/").append(page)
        }
        val url = buildString {
            append(baseUrl, path, "/")
            val params = mutableListOf<String>()
            if (tag != null && category != null) params.add("tag=$tag")
            if (query.isNotBlank()) params.add("s=${URLEncoder.encode(query, "UTF-8")}")
            if (params.isNotEmpty()) append('?').append(params.joinToString("&"))
        }
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    override fun getFilterList(): FilterList = FilterList(CategoryFilter(), TagFilter())

    // =========================== Details ==========================

    override fun mangaDetailsParse(response: Response): SManga = SManga.create().apply {
        val document = response.asJsoup()
        title = document.selectFirst("h1.entry-title")?.text()
            ?.substringBeforeLast("by")
            ?.trim()
            ?.ifBlank { null }
            ?: document.selectFirst("h1.entry-title")?.text()
            ?: ""
        thumbnail_url = document.selectFirst(".herald-post-thumbnail img")?.absUrl("src")
        description = document
            .select(".entry-content > p:not(:has(img)):not(:contains(You need to login))")
            .joinToString("\n\n") { it.wholeText().trim() }
        genre = document.select(".meta-tags > a").joinToString { it.text() }
        author = document.select(".entry-content > p:contains(Mangaka:)").firstOrNull()
            ?.text()
            ?.substringAfter("Mangaka:")
            ?.substringBefore("Language:")
            ?.trim()
            ?: ""
    }

    // =========================== Chapters =========================

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val chapters = document.select(".mpp-toc a").mapNotNull { element ->
            val url = element.absUrl("href")
            if (url.isBlank()) return@mapNotNull null
            SChapter.create().apply {
                name = element.ownText().ifBlank { element.text() }
                setUrlWithoutDomain(url)
                chapter_number = parseChapterNumber(name)
            }
        }
        return chapters.ifEmpty {
            listOf(
                SChapter.create().apply {
                    name = "Chapter"
                    url = response.request.url.encodedPath
                },
            )
        }.reversed()
    }

    // ============================ Pages ===========================

    override fun getChapterUrl(chapter: SChapter): String =
        if (chapter.url.startsWith("http")) chapter.url else baseUrl + chapter.url

    override fun pageListRequest(chapter: SChapter): Request =
        GET(getChapterUrl(chapter), headers)

    override fun pageListParse(response: Response): List<Page> = response.asJsoup()
        .select(".entry-content img")
        .mapIndexedNotNull { index, img ->
            val imageUrl = img.absUrl("src")
            if (imageUrl.isBlank()) null
            else Page(index, url = response.request.url.toString(), imageUrl = imageUrl)
        }

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl ?: page.url, headers)

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // =========================== Filters ==========================

    private class CategoryFilter : Filter.Select<String>(
        "Category",
        arrayOf("All") + CATEGORIES.map { it.first }.toTypedArray(),
    ) {
        fun selected(): String? = if (state == 0) null else CATEGORIES[state - 1].second
    }

    private class TagFilter : Filter.Select<String>(
        "Tag",
        arrayOf("All") + TAGS.map { it.first }.toTypedArray(),
    ) {
        fun selected(): String? = if (state == 0) null else TAGS[state - 1].second
    }

    private companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        /** A post's cover link inside the listing card. Movies/anime posts are not manga. */
        private const val POST_SELECTOR =
            ".post:not(.category-gay-movies):not(.category-yaoi-anime) > div > a"

        private val CATEGORIES = listOf(
            "Yaoi DJ" to "yaoi-doujinshi",
            "Yaoi Manga" to "yaoi-manga",
            "Yaoi Webtoons" to "yaoi-webtoons",
            "Bara" to "bara-manga",
            "Oneshots" to "yaoi-oneshots",
            "Gay Novels" to "gay-novels",
        )

        private val TAGS = listOf(
            "Ahegao" to "ahegao",
            "Bara" to "bara",
            "BDSM" to "bdsm",
            "Big Penis" to "big-penis-yaoi",
            "Blowjob" to "blowjob-yaoi",
            "Bondage" to "bondage",
            "Chinese" to "chinese",
            "Comedy" to "comedy-yaoi",
            "Completed" to "completed-yaoi-manga",
            "Cross-dressing" to "cross-dressing-yaoi",
            "Drama" to "drama-yaoi",
            "English" to "english-yaoi",
            "Fantasy" to "fantasy-yaoi",
            "Full Color" to "full-color-yaoi",
            "Hard Yaoi" to "hard-yaoi",
            "Hentai" to "hentai",
            "Japanese" to "japanese-yaoi",
            "Korean" to "korean",
            "Masturbation" to "masturbation-yaoi",
            "Omegaverse" to "omegaverse",
            "Rape" to "rape",
            "Romance" to "romance-yaoi",
            "School Life" to "school-life",
            "Sex toy" to "sex-toy-yaoi",
            "Shounen Ai" to "shounen-ai",
            "Smut" to "smut",
            "Supernatural" to "supernatural",
            "Threesome" to "threesome",
            "Uncensored" to "uncensored-yaoi",
            "Webtoon" to "webtoon",
            "Yaoi" to "yaoi",
        )

        private fun parseChapterNumber(name: String): Float =
            Regex("(?:\\d+(?:\\.\\d+)?)").find(name)?.value?.toFloatOrNull() ?: -1f
    }
}
