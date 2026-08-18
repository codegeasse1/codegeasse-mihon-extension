package eu.kanade.tachiyomi.extension.ko.newtoki

import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

/*
 * NewToki (뉴토끼, https://newtoki1.org) — Korean webtoon aggregator on the
 * shared "newtoki468" platform (same family as keiyoushi's manatoki source).
 * Contains a dedicated adult section (/webtoon?toon=성인웹툰), so nsfw.
 *
 *     List    : /webtoon?page=N            (cards: .list-row .list-item)
 *     Search  : /webtoon?stx=<q>&page=N
 *     Manga   : /webtoon/<id>              (.view-content span b title,
 *               .view-img img cover, 작가:/분류: info rows)
 *     Chapter : /webtoon/<id>/<cid>        (.list-body .list-item rows with
 *               .item-subject, .wr-num, .wr-date)
 *     Pages   : var manamoa_img = '<base64>' decodes to <img> tags, else
 *               .view-content img
 *
 * NOTE: newtoki1.org is Cloudflare-protected; dynamic paths may return 403
 * for non-browser clients depending on their network. Mirrors rotate — if the
 * pinned domain goes stale, update baseUrl.
 */
class NewToki : HttpSource() {

    override val name = "NewToki"
    override val baseUrl = "https://newtoki1.org"
    override val lang = "ko"
    override val supportsLatest = true

    // Slow requests to ~2 per second to reduce 403s on image loading.
    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor { chain ->
            try {
                Thread.sleep(500)
            } catch (e: InterruptedException) {
                // ignore
            }
            chain.proceed(chain.request())
        }
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", BROWSER_UA)
        .set("Referer", "$baseUrl/")
        .set("Accept-Language", "ko-KR,ko;q=0.9,en-US,en;q=0.8")

    // ============================== Popular ==============================

    override fun popularMangaRequest(page: Int): Request = latestUpdatesRequest(page)

    override fun popularMangaParse(response: Response): MangasPage = latestUpdatesParse(response)

    // ============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/webtoon?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body?.string() ?: return MangasPage(emptyList(), false))

        val mangas = doc.select(".list-row .list-item").mapNotNull { element ->
            val a = element.selectFirst("a") ?: return@mapNotNull null
            SManga.create().apply {
                setUrlWithoutDomain(a.attr("abs:href"))
                title = element.selectFirst(".in-lable a font")?.text()
                    ?: a.attr("title").ifBlank {
                        element.selectFirst(".item-subject, .in-subject, .wr-subject")?.text().orEmpty()
                    }
                if (title.isBlank()) return@mapNotNull null
                thumbnail_url = element.selectFirst(".img-item img")?.attr("abs:src")
            }
        }

        val hasNextPage = doc.selectFirst(".pagination li.active + li > a") != null
        return MangasPage(mangas, hasNextPage)
    }

    // ============================== Search ===============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/webtoon?stx=$encodedQuery&page=$page", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = latestUpdatesParse(response)

    // =========================== Manga Details ===========================

    override fun getMangaUrl(manga: SManga): String =
        if (manga.url.startsWith("http")) manga.url else baseUrl + manga.url

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body?.string() ?: throw IOException("Empty response body"))
        return SManga.create().apply {
            title = doc.selectFirst(".view-content span b")?.text()
                ?: throw Exception("Title not found")
            thumbnail_url = doc.selectFirst(".view-img img")?.attr("abs:src")

            for (element in doc.select(".view-content")) {
                val text = element.text()
                if (text.contains("작가 :")) {
                    author = text.substringAfter("작가 :").substringBefore("•").trim()
                } else if (text.contains("분류 :")) {
                    genre = text.substringAfter("분류 :").substringBefore("•").trim()
                }
            }

            status = SManga.UNKNOWN
        }
    }

    // ============================== Chapters =============================

    override fun getChapterUrl(chapter: SChapter): String =
        if (chapter.url.startsWith("http")) chapter.url else baseUrl + chapter.url

    override fun chapterListRequest(manga: SManga): Request =
        GET(getMangaUrl(manga), headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = Jsoup.parse(response.body?.string() ?: return emptyList())
        val elements = doc.select(".list-body .list-item")
        val total = elements.size

        return elements.mapIndexed { index, element ->
            SChapter.create().apply {
                val a = element.selectFirst(".item-subject")
                    ?: throw Exception("Chapter URL not found")
                setUrlWithoutDomain(a.attr("abs:href"))
                val num = element.selectFirst(".wr-num")?.text()?.toIntOrNull()
                name = if (num != null) "Chapter $num" else "Chapter ${total - index}"
                chapter_number = num?.toFloat() ?: (total - index).toFloat()
                val dateStr = element.selectFirst(".wr-date")?.text()
                date_upload = dateFormat.tryParse(dateStr)
            }
        }
    }

    // =============================== Pages ===============================

    override fun pageListRequest(chapter: SChapter): Request =
        GET(getChapterUrl(chapter), headers)

    override fun pageListParse(response: Response): List<Page> {
        val html = response.body?.string() ?: return emptyList()

        val match = IMG_SCRIPT_REGEX.find(html)
        if (match != null) {
            val base64Str = match.groupValues[1]
            val decodedHtml = runCatching {
                String(Base64.decode(base64Str, Base64.DEFAULT))
            }.getOrNull() ?: return emptyList()

            val doc = Jsoup.parseBodyFragment(decodedHtml, baseUrl)
            return doc.select("img").mapIndexed { i, img ->
                Page(i, imageUrl = img.attr("abs:src"))
            }
        }

        val doc = Jsoup.parseBodyFragment(html, baseUrl)
        return doc.select(".view-content img").mapIndexed { i, img ->
            Page(i, imageUrl = img.attr("abs:src"))
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        private val dateFormat = SimpleDateFormat("yyyy.MM.dd", Locale.ROOT)
        private val IMG_SCRIPT_REGEX = Regex("""var\s+manamoa_img\s*=\s*'([^']+)'""")

        private fun SimpleDateFormat.tryParse(s: String?): Long =
            runCatching { parse(s?.trim().orEmpty()).time }.getOrDefault(0L)
    }
}
