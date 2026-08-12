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
            ?: return JSONObject()
            
        val root = try { JSONObject(script.data()) } catch (e: Exception) { JSONObject() }
        // Fallback to root if "queries" object is missing
        return root.optJSONObject("queries") ?: root
    }

    private fun findQuery(queries: JSONObject, vararg mustContain: String): Any? {
        for (key in queries.keys()) {
            if (mustContain.all { key.contains(it, ignoreCase = true) }) return queries.get(key)
        }
        return null
    }

    private fun extractItems(value: Any?): JSONArray? {
        if (value is JSONArray) return value
        if (value is JSONObject) {
            // Handle React InfiniteQuery pagination (pages -> items)
            if (value.has("pages")) {
                val pagesArr = value.optJSONArray("pages")
                if (pagesArr != null && pagesArr.length() > 0) {
                    val combined = JSONArray()
                    for (i in 0 until pagesArr.length()) {
                        val pObj = pagesArr.get(i)
                        val pItems = when (pObj) {
                            is JSONArray -> pObj
                            is JSONObject -> pObj.optJSONArray("items") ?: pObj.optJSONArray("data") ?: pObj.optJSONArray("chapters") ?: pObj.optJSONArray("list")
                            else -> null
                        }
                        if (pItems != null) {
                            for (j in 0 until pItems.length()) combined.put(pItems.get(j))
                        }
                    }
                    if (combined.length() > 0) return combined
                }
            }
            return value.optJSONArray("items") ?: value.optJSONArray("data") ?: value.optJSONArray("chapters") ?: value.optJSONArray("list")
        }
        return null
    }

    private fun asMangaArray(value: Any?): JSONArray = extractItems(value) ?: JSONArray()

    private fun JSONObject.toSManga(): SManga = SManga.create().apply {
        url = this@toSManga.optString("url").ifBlank { "/title/${this@toSManga.optString("hid")}" }
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

    private fun isChapterArray(arr: JSONArray): Boolean {
        if (arr.length() == 0) return false
        val first = arr.optJSONObject(0) ?: return false
        
        // Exclude Manga/Series/User objects
        if (first.has("synopsis") || first.has("latestChapter") || first.has("avatar") || first.has("email")) return false
        
        // Exclude Genre/Tag objects (This prevents the 403 Action Genre error)
        if (first.has("slug")) return false 
        
        // A valid chapter must at least have an ID
        if (!first.has("id") && !first.has("hid")) return false
        
        return true
    }

    private fun findChaptersArrayRecursively(obj: Any?): JSONArray? {
        if (obj is JSONObject) {
            // Check for React InfiniteQuery pagination (pages -> items)
            if (obj.has("pages")) {
                val pagesArr = obj.optJSONArray("pages")
                if (pagesArr != null && pagesArr.length() > 0) {
                    val combined = JSONArray()
                    for (i in 0 until pagesArr.length()) {
                        val pageObj = pagesArr.get(i)
                        val items = extractItems(pageObj)
                        if (items != null) {
                            for (j in 0 until items.length()) combined.put(items.get(j))
                        }
                    }
                    if (combined.length() > 0 && isChapterArray(combined)) return combined
                }
            }

            // Check standard object arrays
            listOf("items", "data", "chapters", "list").forEach { key ->
                val arr = obj.optJSONArray(key)
                if (arr != null && arr.length() > 0 && isChapterArray(arr)) return arr
            }

            // Recurse into all keys
            for (key in obj.keys()) {
                val result = findChaptersArrayRecursively(obj.get(key))
                if (result != null) return result
            }
        } else if (obj is JSONArray) {
            if (obj.length() > 0 && isChapterArray(obj)) return obj
            
            for (i in 0 until obj.length()) {
                val result = findChaptersArrayRecursively(obj.get(i))
                if (result != null) return result
            }
        }
        return null
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val q = queries(document)
        val mangaUrl = response.request.url.encodedPath 
        
        var chaptersArray: JSONArray? = null
        
        // 1. Primary search: strictly look at queries with "chapter" in the name
        for (key in q.keys()) {
            if (key.contains("chapter", ignoreCase = true)) {
                val items = extractItems(q.get(key))
                if (items != null && items.length() > 0 && isChapterArray(items)) {
                    chaptersArray = items
                    break
                }
            }
        }

        // 2. Fallback search: scan the entire JSON payload
        if (chaptersArray == null) {
            chaptersArray = findChaptersArrayRecursively(q)
        }
        
        val chapters = mutableListOf<SChapter>()
        
        if (chaptersArray != null) {
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
                    
                    // Prioritize numeric ID for the API fetch to work cleanly
                    url = if (id.isNotBlank() && id != "null" && id.matches(Regex("""^\d+$"""))) {
                        "$mangaUrl/$id-chapter-$safeChapNum"
                    } else if (hid.isNotBlank() && hid != "null") {
                        "$mangaUrl/$hid-chapter-$safeChapNum"
                    } else if (obj.has("url") && obj.getString("url").isNotBlank()) {
                        obj.getString("url")
                    } else {
                        mangaUrl
                    }
                })
            }
        } else {
            // 3. Last resort DOM Scraper
            val domChapters = document.select("a[href*=-chapter-], a.mchap-row__primary")
            if (domChapters.isNotEmpty()) {
                chapters.addAll(domChapters.map { el ->
                    SChapter.create().apply {
                        name = el.selectFirst("span.mchap-row__ch")?.text()?.ifBlank { el.text() } ?: el.text().trim()
                        chapter_number = Regex("""[\d.]+""").find(name)?.value?.toFloatOrNull() ?: -1f
                        setUrlWithoutDomain(el.attr("href"))
                    }
                })
            }
        }
        
        // Return whatever we found safely without throwing a 500 error
        return chapters.sortedByDescending { it.chapter_number }
    }

    // ---- Pages ------------------------------------------------------------

    override fun pageListRequest(chapter: SChapter): Request {
        val url = chapter.url
        val chapterId = Regex("""/([^/]+)-chapter-""").find(url)?.groupValues?.get(1)
        
        // Ensure we only pass a clean numeric ID to the API to prevent 403 errors
        if (chapterId != null && chapterId.matches(Regex("""^\d+$"""))) {
            val apiHeaders = headersBuilder()
                .set("Accept", "application/json, text/plain, */*")
                .set("Referer", baseUrl + url)
                .build()
            return GET("$baseUrl/api/v1/chapters/$chapterId", apiHeaders)
        }
        
        return GET(baseUrl + url, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val bodyStr = response.body?.string() ?: ""
        val contentType = response.header("Content-Type") ?: ""

        // 1. Process the API JSON Response
        if (contentType.contains("json", ignoreCase = true) || bodyStr.trim().startsWith("{")) {
            val root = try { JSONObject(bodyStr) } catch(e: Exception) { JSONObject() }
            val urls = findImageUrlsRecursively(root)
            if (urls.isNotEmpty()) {
                return urls.mapIndexed { i, url -> Page(i, imageUrl = url) }
            }
            throw Exception("No pages found in API response. Layout may have changed.")
        }

        // 2. Fallback to HTML parsing
        val document = org.jsoup.Jsoup.parse(bodyStr, response.request.url.toString())
        val domImgs = document.select("img.rpage-page__img, .page-image img, img.chapter-img")
        if (domImgs.isNotEmpty()) {
            return domImgs.mapIndexed { index, element ->
                Page(index, imageUrl = element.attr("abs:src"))
            }
        }
        
        val script = document.selectFirst("script#initial-data")
        if (script != null) {
            try {
                val root = JSONObject(script.data())
                val queries = root.optJSONObject("queries") ?: root
                
                val extractedUrls = findImageUrlsRecursively(queries)
                if (extractedUrls.isNotEmpty()) {
                    return extractedUrls.mapIndexed { i, url -> Page(i, imageUrl = url) }
                }
            } catch (e: Exception) {}
        }
        
        throw Exception("No pages found. Cloudflare verification might be required.")
    }

    private fun findImageUrlsRecursively(obj: Any?): List<String> {
        if (obj is JSONObject) {
            listOf("images", "pages", "blocks", "chapter_images", "data", "list", "chapter").forEach { arrKey ->
                val arr = obj.optJSONArray(arrKey)
                if (arr != null) {
                    val urls = extractUrlsFromArray(arr, strict = false)
                    if (urls.isNotEmpty()) return urls
                }
            }
            
            for (key in obj.keys()) {
                val found = findImageUrlsRecursively(obj.get(key))
                if (found.isNotEmpty()) return found
            }
        } else if (obj is JSONArray) {
            val stringUrls = extractUrlsFromArray(obj, strict = true)
            if (stringUrls.isNotEmpty()) return stringUrls
            
            for (i in 0 until obj.length()) {
                val found = findImageUrlsRecursively(obj.get(i))
                if (found.isNotEmpty()) return found
            }
        }
        return emptyList()
    }

    private fun extractUrlsFromArray(arr: JSONArray, strict: Boolean): List<String> {
        val urls = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val item = arr.get(i)
            val url = if (item is JSONObject) {
                item.optString("url").ifBlank { item.optString("src") }.ifBlank { item.optString("image") }
            } else if (item is String) {
                item
            } else {
                ""
            }
            
            if (url.startsWith("http")) {
                if (!strict || url.contains("wowpic") || url.contains("comix.to") || url.matches(Regex(".*\\.(jpg|png|webp|avif|jpeg).*"))) {
                    urls.add(url)
                }
            }
        }
        return urls
    }

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException("Not used")
}
