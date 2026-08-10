package eu.kanade.tachiyomi.extension.en.comix

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
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document

/**
 * Comix (comix.to)
 *
 * The site is a client-hydrated SPA: every page embeds its initial API
 * response in a `<script type="application/json" id="initial-data">` tag
 * as a map of react-query cache keys -> results. Listing pages are parsed
 * straight out of that JSON rather than by scraping rendered HTML, since
 * the HTML itself is built client-side after load.
 *
 * Grounded in real captured markup: the homepage's initial-data payload
 * (popular/latest listings), a chapter-row link snippet
 * (`a.mchap-row__primary` / `span.mchap-row__ch`), and a reader-page image
 * snippet (`img.rpage-page__img`).
 *
 * NOT verified against real markup (only inferred) — check these before
 * relying on them:
 *  - The manga detail page's actual layout (mangaDetailsParse below reads
 *    OG meta tags as a safe fallback, but the real page likely has richer
 *    markup worth using instead).
 *  - Whether `/search?q=` is really the search route and whether it embeds
 *    initial-data the same way.
 *  - Pagination beyond page 1 for popular/latest — the homepage JSON only
 *    ever contains page 1. Page 2+ assumes `/?page=N` re-embeds fresh
 *    initial-data server-side, which is a guess, not a confirmed behavior.
 */
class Comix : HttpSource() {

    override val name = "Comix"

    override val baseUrl = "https://comix.to"

    override val lang = "en"

    override val supportsLatest = true

    // ---- Shared: pull the embedded react-query cache out of a page -------

    /**
     * The initial-data script contains: {"page": "...", "queries": { "<json-key>": <result>, ... }}
     * Keys are stringified arrays like ["manga","top",{"type":"trending",...}] — we don't try to
     * parse them as JSON (quoting makes that awkward); we just substring-match on the raw key text.
     */
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

    /** Listing queries are either a bare JSON array, or {"items": [...], "meta": {...}}. */
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
        // The homepage embed is a fixed top-50 snapshot with no further pages.
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
    // UNVERIFIED: confirm the real search URL and that it embeds initial-data
    // the same way before relying on this.

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
    // UNVERIFIED beyond OG tags: the real detail page almost certainly has
    // richer markup (genre tags, author, alt titles) worth switching to once
    // you have a captured copy of that page's HTML.

    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        description = document.selectFirst("meta[property=og:description]")?.attr("content")
        thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
    }

    // ---- Chapters ---------------------------------------------------------
    // Grounded in the real snippet: <a class="mchap-row__primary" href="...">
    //   <span class="mchap-row__ch">Ch.81</span>
    // </a>

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
    // Grounded in the real snippet: <img class="rpage-page__img" src="...">
    // Unlike sites that render pages into a <canvas> or blob: URL, these are
    // plain direct image URLs from a CDN, so no special handling is needed.

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        return document.select("img.rpage-page__img").mapIndexed { index, element ->
            Page(index, imageUrl = element.attr("abs:src"))
        }
    }

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException("Not used")
}
