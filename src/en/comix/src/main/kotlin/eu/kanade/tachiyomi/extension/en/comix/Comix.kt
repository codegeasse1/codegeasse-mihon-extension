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

    // Rely on Mihon's default User-Agent to sync perfectly with the WebView and avoid Cloudflare loops
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
            if (mustContain.all { key.contains(it, ignoreCase = true) }) return queries.get(key)
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
        GET("$baseUrl/?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val q = queries(response.asJsoup())
        val result = findQuery(q, "\"list\"") ?: findQuery(q, "\"manga\"")
        val list = asMangaArray(result)
        val mangas = (0 until list.length()).map { list.getJSONObject(it).toSManga() }
        
        val meta = (result as? JSONObject)?.optJSONObject("meta")
        val hasNextPage = meta?.optBoolean("hasNext", false) ?: (list.length() >= 30)
        return MangasPage(mangas, hasNextPage)
    }

    // ---- Latest -----------------------------------------------------------

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val q = queries(response.asJsoup())
        val result = findQuery(q, "\"list\"") ?: findQuery(q, "\"manga\"")
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
        if (response.code == 404) return MangasPage(emptyList(), false)
        
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
        
        // Aggressively hunt for the chapters array, even if it's nested in InfiniteQuery pagination
        for (key in q.keys()) {
            val value = q.get(key)
            var items: JSONArray? = null
            
            if (value is JSONArray) {
                items = value
            } else if (value is JSONObject) {
                // Check if it's paginated (React InfiniteQuery)
                if (value.has("pages")) {
                    val pagesArr = value.optJSONArray("pages")
                    if (pagesArr != null && pagesArr.length() > 0) {
                        val combined = JSONArray()
                        for (p in 0 until pagesArr.length()) {
                            val pObj = pagesArr.get(p)
                            val pItems = when (pObj) {
                                is JSONArray -> pObj
                                is JSONObject -> pObj.optJSONArray("items") ?: pObj.optJSONArray("data") ?: pObj.optJSONArray("chapters")
                                else -> null
                            }
                            if (pItems != null) {
                                for (i in 0 until pItems.length()) combined.put(pItems.get(i))
                            }
                        }
                        if (combined.length() > 0) items = combined
                    }
                } else {
                    items = value.optJSONArray("items") ?: value.optJSONArray("data") ?: value.optJSONArray("chapters")
                }
            }
            
            // Validate that we found chapters, not a manga array
            if (items != null && items.length() > 0) {
                val firstItem = items.optJSONObject(0)
                if (firstItem != null && !firstItem.has("synopsis") && !firstItem.has("poster")) {
                    if (firstItem.has("chap") || firstItem.has("chapter") || firstItem.has("vol") || firstItem.has("id")) {
                        chaptersArray = items
                        break
                    }
                }
            }
        }
        
        // Backup DOM Scraper just in case the JSON payload completely changes
        if (chaptersArray == null) {
            val domChapters = document.select("a[href*=-chapter-]")
            if (domChapters.isNotEmpty()) {
                return domChapters.map { el ->
                    SChapter.create().apply {
                        name = el.text().trim()
                        chapter_number = Regex("""[\d.]+""").find(name)?.value?.toFloatOrNull() ?: -1f
                        setUrlWithoutDomain(el.attr("href"))
                    }
                }.sortedByDescending { it.chapter_number }
            }
            throw Exception("Could not find chapters in initial-data payload. Please report this to the developer.")
        }
        
        val chapters = mutableListOf<SChapter>()
        for (i in 0 until chaptersArray.length()) {
            val obj = chaptersArray.getJSONObject(i)
            chapters.add(SChapter.create().apply {
                val chapNum = obj.optString("chap").ifBlank { obj.optString("chapter") }
                val volNum = obj.optString("vol").ifBlank { obj.optString("volume") }
                val titleStr = obj.optString("title").ifBlank { obj.optString("name") }.trim()
                
                var nameStr = ""
                if (volNum.isNotBlank() && volNum != "null" && volNum != "0") nameStr += "Vol. $volNum "
                if (chapNum.isNotBlank() && chapNum != "null") nameStr += "Ch. $chapNum "
                if (titleStr.isNotBlank() && titleStr != "null") {
                    nameStr += if (nameStr.isEmpty()) titleStr else " - $titleStr"
                }
                
                name = nameStr.trim().ifEmpty { 
                    if (chapNum.isNotBlank() && chapNum != "null") "Chapter $chapNum" else "Oneshot" 
                }
                
                chapter_number = chapNum.toFloatOrNull() ?: -1f
                
                val id = obj.optString("id")
                val hid = obj.optString("hid")
                val safeChapNum = if (chapNum.isNotBlank() && chapNum != "null") chapNum else "0"
                
                url = if (obj.has("url") && obj.getString("url").isNotBlank()) {
                    obj.getString("url")
                } else if (id.isNotBlank() && id != "null") {
                    "$mangaUrl/$id-chapter-$safeChapNum"
                } else if (hid.isNotBlank() && hid != "null") {
                    "$mangaUrl/$hid-chapter-$safeChapNum"
                } else {
                    mangaUrl
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
            if (key.contains("\"chapter", ignoreCase = true) || key.contains("\"images\"") || key.contains("\"pages\"")) {
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
        
        throw Exception("No pages found in initial-data payload. Site structure may have changed.")
    }

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException("Not used")
}
