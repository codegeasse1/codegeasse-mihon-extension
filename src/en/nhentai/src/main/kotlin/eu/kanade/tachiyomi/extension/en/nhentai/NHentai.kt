package eu.kanade.tachiyomi.extension.en.nhentai

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Request
import okhttp3.Response
import java.net.URLEncoder

/*
 * nhentai (https://nhentai.net) — the classic doujinshi gallery. Uses the
 * official v2 JSON API:
 *
 *     Latest   : /api/v2/galleries?page=N  -> {result:[...], num_pages, per_page, total}
 *     Popular  : /api/v2/galleries/popular -> [ ... ]  (no pagination)
 *     Search   : /api/v2/search?query=Q&page=N
 *     Detail   : /api/v2/galleries/<id>    -> {title:{english,japanese,pretty}, cover, tags, pages:[{number,path}]}
 *     Pages    : https://i1.nhentai.net/<pages[n].path>   (e.g. galleries/<media_id>/<n>.webp)
 *     Thumbs   : https://t1.nhentai.net/<thumbnail path>
 */
class NHentai : HttpSource() {

    override val name = "nhentai"
    override val baseUrl = "https://nhentai.net"
    override val lang = "en"
    override val supportsLatest = true

    private val apiUrl = "https://nhentai.net/api/v2"

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", BROWSER_UA)

    // =========================== Browse & Search =========================

    override fun popularMangaRequest(page: Int): Request =
        GET("$apiUrl/galleries/popular", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val arr = responseJsonArray(response) ?: return MangasPage(emptyList(), false)
        val mangas = arr.mapNotNull { it.takeIf { el -> el.isJsonObject }?.asJsonObject?.let(::catalogToManga) }
        return MangasPage(mangas, false)
    }

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$apiUrl/galleries?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseCatalog(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$apiUrl/search?query=${URLEncoder.encode(query, "utf-8")}&page=$page", headers)

    override fun searchMangaParse(response: Response): MangasPage = parseCatalog(response)

    private fun parseCatalog(response: Response): MangasPage {
        val json = responseJsonObject(response) ?: return MangasPage(emptyList(), false)
        val result = json.get("result")?.takeIf { it.isJsonArray }?.asJsonArray ?: return MangasPage(emptyList(), false)
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val totalPages = json.get("num_pages")?.takeIf { it.isJsonPrimitive }?.asInt ?: page
        val mangas = result.mapNotNull { it.takeIf { el -> el.isJsonObject }?.asJsonObject?.let(::catalogToManga) }
        return MangasPage(mangas, page < totalPages)
    }

    private fun catalogToManga(obj: JsonObject): SManga? {
        val id = obj.stringOrNull("id") ?: return null
        val thumb = obj.stringOrNull("thumbnail") ?: return null
        return SManga.create().apply {
            url = "/g/$id/"
            title = obj.stringOrNull("english_title")
                ?: obj.stringOrNull("japanese_title")
                ?: return@apply
            thumbnail_url = thumbUrl(thumb)
        }
    }

    // =========================== Manga Details ===========================

    override fun getMangaUrl(manga: SManga): String =
        if (manga.url.startsWith("http")) manga.url else baseUrl + manga.url

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET("$apiUrl/galleries/${galleryIdOf(manga)}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val data = responseJsonObject(response) ?: return SManga.create()
        val id = data.stringOrNull("id") ?: return SManga.create()
        return SManga.create().apply {
            url = "/g/$id/"
            val titleObj = data.get("title")?.takeIf { it.isJsonObject }?.asJsonObject
            title = titleObj?.stringOrNull("english")
                ?: titleObj?.stringOrNull("pretty")
                ?: titleObj?.stringOrNull("japanese")
                ?: ""
            thumbnail_url = data.get("cover")?.takeIf { it.isJsonObject }?.asJsonObject
                ?.stringOrNull("path")?.let(::thumbUrl).orEmpty()
            genre = data.get("tags")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { el ->
                    val tag = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                    if (tag.stringOrNull("type") == "language") null else tag.stringOrNull("name")
                }
                ?.filter { it.isNotBlank() }
                ?.distinct()
                ?.joinToString(", ")
                .orEmpty()
        }
    }

    // ============================== Chapters =============================

    override fun getChapterUrl(chapter: SChapter): String =
        if (chapter.url.startsWith("http")) chapter.url else baseUrl + chapter.url

    override fun chapterListRequest(manga: SManga): Request =
        GET("$apiUrl/galleries/${galleryIdOf(manga)}", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val data = responseJsonObject(response) ?: return emptyList()
        val id = data.stringOrNull("id") ?: return emptyList()
        val date = data.get("upload_date")?.takeIf { it.isJsonPrimitive }?.asLong?.times(1000) ?: 0L
        return listOf(SChapter.create().apply {
            url = "/g/$id/"
            name = "Gallery"
            chapter_number = 1f
            date_upload = date
        })
    }

    // =============================== Pages ===============================

    override fun pageListRequest(chapter: SChapter): Request =
        GET("$apiUrl/galleries/${galleryIdOf(chapter)}", headers)

    override fun pageListParse(response: Response): List<Page> {
        val data = responseJsonObject(response) ?: return emptyList()
        val pages = data.get("pages")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return pages.mapNotNull { el ->
            val page = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val path = page.stringOrNull("path") ?: return@mapNotNull null
            val number = page.get("number")?.takeIf { it.isJsonPrimitive }?.asInt ?: return@mapNotNull null
            Page(number - 1, imageUrl = "https://i1.nhentai.net/$path")
        }.sortedBy { it.index }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================= Utilities =============================

    private fun galleryIdOf(manga: SManga): String =
        manga.url.trim('/').substringAfterLast('/')

    private fun galleryIdOf(chapter: SChapter): String =
        chapter.url.trim('/').substringAfterLast('/')

    private fun thumbUrl(path: String): String = "https://t1.nhentai.net/$path"

    private fun responseJsonObject(response: Response): JsonObject? =
        runCatching {
            val body = response.body?.string() ?: return null
            JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject
        }.getOrNull()

    private fun responseJsonArray(response: Response): JsonArray? =
        runCatching {
            val body = response.body?.string() ?: return null
            JsonParser.parseString(body).takeIf { it.isJsonArray }?.asJsonArray
        }.getOrNull()

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
