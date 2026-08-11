package eu.kanade.tachiyomi.extension.en.comix

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document

/**
 * Comix (comix.to)
 */
class Comix : HttpSource() {

    override val name = "Codegeasse Comix"

    override val baseUrl = "https://comix.to"

    override val lang = "en"

    override val supportsLatest = true

    // Removed hardcoded User-Agent so Mihon automatically syncs it with WebView.
    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Referer", "$baseUrl/")

    // ---- Shared: Pull embedded React-Query cache out of a page -------

    private fun queries(document: Document): JSONObject {
        val script = document.selectFirst("script#initial-data")
            ?: throw Exception("initial-data script not found - page structure may have changed. Check WebView.")
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
        is JSONObject -> value.optJSONArray("items") ?: JSONArray()
        else -> JSONArray()
    }

    private fun JSONObject.toSManga(): SManga = SManga.create().apply {
        url = this@toSManga.optString("url")
        title = this@toSManga.optString("title")
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

    // ---- Popular ----------------------------------------------------------

    override fun popularMangaRequest(page: Int): Request = 
        GET("$baseUrl/", headers) // Use home page directly to avoid 404 errors

    override fun popularMangaParse(response: Response): MangasPage {
        val q = queries(response.asJsoup())
        // Grab the trending/top array from the home page state
        val result = findQuery(q, "\"manga\"", "\"top\"", "\"trending\"") 
            ?: findQuery(q, "\"manga\"", "\"top\"") 
            ?: findQuery(q, "\"manga\"")
            
        val list = asMangaArray(result)
        val mangas = (0 until list.length()).map { list.getJSONObject(it).toSManga() }
        // The home page 'trending' section is usually a fixed list of 50 items, so no pagination here.
        return MangasPage(mangas, false)
    }

    // ---- Latest -----------------------------------------------------------

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val q = queries(response.asJsoup())
        // Grab the latest updates list array from the state
        val result = findQuery(q, "\"manga\"", "\"list\"") ?: findQuery(q, "\"list\"")
        val list = asMangaArray(result)
        val mangas = (0 until list.length()).map { list.getJSONObject(it).toSManga() }
        
        val meta = (result as? JSONObject)?.optJSONObject("meta")
        val hasNextPage = meta?.optBoolean("hasNext", false) ?: (list.length() >= 30)
        return MangasPage(mangas, hasNextPage)
    }

    // ---- Search -----------------------------------------------------------

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/search?q=$query&page=$page", headers)

    override fun searchMangaParse(response: Response): MangasPage {
        if (response.code == 404) {
            return MangasPage(emptyList(), false) // Gracefully handle 404 if /search doesn't exist
        }
        val q = queries(response.asJsoup())
        val result = findQuery(q, "\"search\"") ?: findQuery(q, "\"list\"") ?: findQuery(q, "\"manga\"")
        val list = asMangaArray(result)
        val mangas = (0 until list.length()).map { list.getJSONObject(it).toSManga() }
        
        val meta = (result as? JSONObject)?.optJSONObject("meta")
        val hasNextPage = meta?.optBoolean("hasNext", false) ?: (list.length() >= 30)
        return MangasPage(mangas, hasNextPage)
    }

    override fun getFilterList(): FilterList = FilterList()

    // ---- Manga details ----------------------------------------------------

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty().substringBefore(" - ")
            description = document.selectFirst("meta[property=og:description]")?.attr("content")
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")

            try {
                val q = queries(document)
                for (key in q.keys()) {
                    if (key.contains("\"manga\"")) {
                        val obj = q.optJSONObject(key)
                        if (obj != null && obj.has("title")) {
                            author = obj.optString("author", obj.optString("authors"))
                            artist = obj.optString("artist", obj.optString("artists"))
                            genre = obj.optJSONArray("genres")?.let { arr ->
                                (0 until arr.length()).map { arr.getString(it) }.joinToString(", ")
                            }
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    // ---- Chapters ---------------------------------------------------------

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val q = queries(document)
        val mangaUrl = response.request.url.encodedPath 
        
        var chaptersArray: JSONArray? = null
        
        for (key in q.keys()) {
            val value = q.get(key)
            val items = when (value) {
                is JSONArray -> value
                is JSONObject -> value.optJSONArray("items") ?: value.optJSONArray("chapters") ?: value.optJSONArray("data")
                else -> null
            }
            if (items != null && items.length() > 0) {
                val firstItem = items.getJSONObject(0)
                if (firstItem.has("chap") || firstItem.has("chapter") || firstItem.has("vol")) {
                    chaptersArray = items
                    break
                }
            }
        }
        
        if (chaptersArray == null) {
            throw Exception("Could not find chapters in initial-data payload.")
        }
        
        val chapters = mutableListOf<SChapter>()
        for (i in 0 until chaptersArray.length()) {
            val obj = chaptersArray.getJSONObject(i)
            chapters.add(SChapter.create().apply {
                val chapNum = obj.optString("chap", obj.optString("chapter"))
                val volNum = obj.optString("vol", obj.optString("volume"))
                val titleStr = obj.optString("title", obj.optString("name")).trim()
                
                var nameStr = ""
                if (volNum.isNotBlank() && volNum != "null" && volNum != "0") nameStr += "Vol. $volNum "
                if (chapNum.isNotBlank() && chapNum != "null") nameStr += "Ch. $chapNum "
                if (titleStr.isNotBlank() && titleStr != "null") {
                    nameStr += if (nameStr.isEmpty()) titleStr else " - $titleStr"
                }
                
                name = nameStr.trim().ifEmpty { "Chapter $chapNum" }
                chapter_number = chapNum.toFloatOrNull() ?: -1f
                
                val id = obj.optString("id")
                val safeChapNum = if (chapNum.isNotBlank() && chapNum != "null") chapNum else "0"
                url = if (obj.has("url") && obj.getString("url").isNotBlank()) {
                    obj.getString("url")
                } else {
                    "$mangaUrl/$id-chapter-$safeChapNum"
                }
            })
        }
        
        return chapters.sortedByDescending { it.chapter_number }
    }

    // ---- Pages ------------------------------------------------------------

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        
        val domImgs = document.select("img.rpage-page__img")
        if (domImgs.isNotEmpty()) {
            return domImgs.mapIndexed { index, element ->
                Page(index, imageUrl = element.attr("abs:src"))
            }
        }
        
        val q = queries(document)
        for (key in q.keys()) {
            if (key.contains("\"chapter\"") || key.contains("\"images\"") || key.contains("\"pages\"")) {
                val value = q.get(key)
                if (value is JSONObject) {
                    val imagesArr = value.optJSONArray("images") ?: value.optJSONArray("pages") ?: value.optJSONObject("chapter")?.optJSONArray("images")
                    if (imagesArr != null && imagesArr.length() > 0) {
                        val pages = mutableListOf<Page>()
                        for (i in 0 until imagesArr.length()) {
                            val imgObj = imagesArr.get(i)
                            val url = if (imgObj is JSONObject) {
                                imgObj.optString("url", imgObj.optString("src"))
                            } else {
                                imgObj.toString()
                            }
                            if (url.isNotBlank() && url != "null") {
                                pages.add(Page(i, imageUrl = url))
                            }
                        }
                        if (pages.isNotEmpty()) return pages
                    }
                }
            }
        }
        
        throw Exception("No pages found in initial-data payload.")
    }

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException("Not used")
}
