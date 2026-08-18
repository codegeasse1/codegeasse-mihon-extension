package eu.kanade.tachiyomi.extension.ko.toonkor

import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

/*
 * Toonkor (툰코) — Korean webtoon aggregator. Mirrors rotate frequently; the
 * site announces its current mirror in its page headers (og:url / canonical).
 * tkor145.com was the live mirror when this was written; tkor131.com (what
 * keiyoushi pins) also still serves content paths. If the pinned domain goes
 * stale, update baseUrl to whatever the site's canonical header reports.
 *
 *     Popular : /웹툰/연재?fil=인기     (cards: div.section-item-inner)
 *     Latest  : /웹툰/연재
 *     Search  : /bbs/search.php?sfl=wr_subject||wr_content&stx=<q>
 *     Manga   : /<korean-title-slug>  (table.bt_view1 details; chapters are
 *               table.web_list rows with td.content__title[data-role] URLs)
 *     Chapter : /<slug>_<N>화.html     (script var toon_img = '<base64>'
 *               decodes to <img src="..."> tags; images are absolute CDN URLs)
 *
 * Contains adult/mixed content (isNsfw). Note: the site's root path returns
 * HTTP 400 for non-browser clients, but all content paths below work fine.
 */
class Toonkor : HttpSource() {

    override val name = "Toonkor"
    override val baseUrl = "https://tkor145.com"
    override val lang = "ko"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", BROWSER_UA)
        .set("Referer", "$baseUrl/")
        .set("Accept-Language", "ko-KR,ko;q=0.9,en-US,en;q=0.8")

    // =========================== Browse & Search =========================

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl$WEBTOONS_PATH$ALL_STATUS_PATH$SORT_POPULAR", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body?.string() ?: return MangasPage(emptyList(), false))
        val mangas = doc.select("div.section-item-inner").map { element ->
            SManga.create().apply {
                element.select("div.section-item-title a").let {
                    title = it.select("h3").text()
                    setUrlWithoutDomain(it.attr("abs:href"))
                }
                thumbnail_url = element.select("img").attr("abs:src")
            }
        }
        return MangasPage(mangas, false)
    }

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl$WEBTOONS_PATH$ALL_STATUS_PATH$SORT_LATEST", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val filterList = filters.ifEmpty { getFilterList() }

        val type = filterList.filterIsInstance<TypeFilter>().firstOrNull()
        val status = filterList.filterIsInstance<StatusFilter>().firstOrNull()
        val sort = filterList.filterIsInstance<SortFilter>().firstOrNull()

        val requestPath = when {
            query.isNotEmpty() -> "/bbs/search.php?sfl=wr_subject%7C%7Cwr_content&stx=$query"
            else -> "${type?.toUriPart() ?: ""}${status?.toUriPart() ?: ""}${sort?.toUriPart() ?: ""}"
        }

        return GET(baseUrl + requestPath, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // =========================== Manga Details ===========================

    override fun getMangaUrl(manga: SManga): String =
        if (manga.url.startsWith("http")) manga.url else baseUrl + manga.url

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body?.string() ?: throw IOException("Empty response body"))
        return SManga.create().apply {
            with(doc.select("table.bt_view1")) {
                title = select("td.bt_title").text()
                author = select("td.bt_label span.bt_data").text()
                description = select("td.bt_over").text()
                thumbnail_url = select("td.bt_thumb img").firstOrNull()?.attr("abs:src")
            }
        }
    }

    // ============================== Chapters =============================

    override fun getChapterUrl(chapter: SChapter): String =
        if (chapter.url.startsWith("http")) chapter.url else baseUrl + chapter.url

    override fun chapterListRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = Jsoup.parse(response.body?.string() ?: return emptyList())
        return doc.select("table.web_list tr:has(td.content__title)").map { element ->
            SChapter.create().apply {
                element.select("td.content__title").let {
                    url = it.attr("data-role")
                    name = it.text()
                }
                date_upload = dateFormat.tryParse(element.select("td.episode__index").text())
            }
        }
    }

    // =============================== Pages ===============================

    override fun pageListRequest(chapter: SChapter): Request =
        GET(getChapterUrl(chapter), headers)

    override fun pageListParse(response: Response): List<Page> {
        val doc = Jsoup.parse(response.body?.string() ?: return emptyList())
        val encoded = doc.select("script:containsData(toon_img)").firstOrNull()?.data()
            ?.substringAfter("'")?.substringBefore("'") ?: return emptyList()

        val decoded = String(Base64.decode(encoded, Base64.DEFAULT))

        return pageListRegex.findAll(decoded).mapIndexed { i, matchResult ->
            val imageUrl = matchResult.destructured.component1().let { if (it.startsWith("http")) it else baseUrl + it }
            Page(i, imageUrl = imageUrl)
        }.toList()
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Note: can't combine with text search!"),
        Filter.Separator(),
        TypeFilter(),
        StatusFilter(),
        SortFilter(),
    )

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
        private val pageListRegex = Regex("""src="([^"]*)"""")

        private fun SimpleDateFormat.tryParse(s: String): Long =
            runCatching { parse(s.trim()).time }.getOrDefault(0L)
    }
}
