package eu.kanade.tachiyomi.extension.en.comix

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
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document

/**
 * Comix (comix.to)
 */
class Comix : HttpSource() {

    override val name = "Codegeasse Comix" // 👈 Matches the Gradle file perfectly

    override val baseUrl = "https://comix.to"

    override val lang = "en"

    override val supportsLatest = true

    // ---- Shared: pull the embedded react-query cache out of a page -------

    private fun queries(document: Document): JSONObject {
        val script = document.selectFirst("script#initial-data")
            ?: throw Exception("initial-data script not found - page structure may have changed")
        return JSONObject(script.data()).getJSONObject("queries")
    }

    private fun findQuery(queries: JSONObject, vararg mustContain: String): Any? {
        for (key in queries.keys()) {
            if (mustContain.all { key.contains(it) }) return queries.get(key)
        }
        return null
    }

    private fun asMangaArray(value: Any?): JSONArray = when (value) {
        is JSONArray -> value
        is JSONObject -> value.getJSONArray("items")
        else -> throw Exception("Expected a manga listing but found none in initial-data")
    }

    private fun JSONObject.toSManga(): SManga = SManga.create().apply {
        url = this@toSManga.getString("url")
        title = this@toSManga.getString("title")
        thumbnail_url = this@toSManga.optJSONObject("poster")?.optString("medium")
        description = this@toSManga.optString("synopsis").takeIf { it.isNotBlank() }
        status = when (this@toSManga.optString("status")) {
            "releasing" -> SManga.ONGOING
            "completed", "finished" -> SManga.COMPLETED
            "on_hiatus" -> SManga.ON_HIATUS
            "discontinued", "cancelled" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    // ---- Popular (home page "top"/"trending" query) -----------------------

    override fun popularMangaRequest(page: Int): Request = GET(baseUrl, headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val q = queries(response.asJsoup())
        val list = asMangaArray(findQuery(q, "\"top\"", "\"trending\""))
        val mangas = (0 until list.length()).map { list.getJSONObject(it).toSManga() }
        return MangasPage(mangas, false)
    }

    // ---- Latest (home page "list"/"hot" query, ordered by chapter update) -

    override fun latestUpdatesRequest(page: Int): Request =
        if (page <= 1) GET(baseUrl, headers) else GET("$baseUrl/?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val q = queries(response.asJsoup())
        val result = findQuery(q, "\"list\"", "\"hot\"", "chapter_updated_at")
        val list = asMangaArray(result)
        val mangas = (0 until list.length()).map { list.getJSONObject(it).toSManga() }
        val meta = (result as? JSONObject)?.optJSONObject("meta")
        val hasNextPage = meta?.optBoolean("hasNext", false) ?: false
        return MangasPage(mangas, hasNextPage)
    }

    // ---- Search -------------------------------------------------------------

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/search?q=$query&page=$page", headers)

    override fun searchMangaParse(response: Response): MangasPage {
        val q = queries(response.asJsoup())
        val result = findQuery(q, "\"search\"") ?: findQuery(q, "\"list\"")
        val list = asMangaArray(result)
        val mangas = (0 until list.length()).map { list.getJSONObject(it).toSManga() }
        val meta = (result as? JSONObject)?.optJSONObject("meta")
        val hasNextPage = meta?.optBoolean("hasNext", false) ?: false
        return MangasPage(mangas, hasNextPage)
    }

    override fun getFilterList(): FilterList = FilterList()

    // ---- Manga details --------------------------------------------------------

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
            description = document.selectFirst("meta[property=og:description]")?.attr("content")
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
        }
    }

    // ---- Chapters ---------------------------------------------------------

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select("a.mchap-row__primary").map { el ->
            SChapter.create().apply {
                val chapterLabel = el.selectFirst("span.mchap-row__ch")?.text().orEmpty()
                name = chapterLabel.ifBlank { el.text() }
                chapter_number = Regex("""[\d.]+""").find(chapterLabel)
                    ?.value?.toFloatOrNull() ?: -1f
                setUrlWithoutDomain(el.attr("href"))
            }
        }
    }

    // ---- Pages --------------------------------------------------------------

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        return document.select("img.rpage-page__img").mapIndexed { index, element ->
            Page(index, imageUrl = element.attr("abs:src"))
        }
    }

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException("Not used")
}