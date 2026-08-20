package eu.kanade.tachiyomi.extension.en.asiaon

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/*
 * AsiaOn (https://asiaon.top) — "Sexiest models", a WordPress/WPGraphQL gallery of
 * cosplay / Xiuren / model / AI photo sets. The Next.js frontend is almost fully
 * client-rendered, but two server endpoints give us everything:
 *
 *     Listing  : GET  https://api.asiaon.top/api/posts?page=N
 *                -> { nodes, pageInfo: { hasNextPage, ... } }  — 16 posts/page,
 *                   UNLIMITED pagination: keep following pageInfo.hasNextPage
 *                   (verified well past page 500; there is no hard page cap).
 *     Search   : POST https://api.asiaon.top/graphql
 *                posts(where:{search:$q}, first:16, after:$after) — cursor paginated,
 *                   also unlimited via pageInfo.hasNextPage.
 *     Details  : graphql post(id:$uri, idType:URI) { ... content }
 *     Pages    : the post `content` HTML holds the entire gallery (<img> tags).
 *
 * Images are served directly from Google's lh3 CDN (works without Referer).
 * The site is behind Cloudflare, so we send only a browser User-Agent.
 */
class Asiaon : HttpSource() {

    override val name = "AsiaOn"
    override val baseUrl = "https://asiaon.top"
    override val lang = "en"
    override val supportsLatest = true

    private val listUrl = "https://api.asiaon.top/api/posts"
    private val graphqlUrl = "https://api.asiaon.top/graphql"

    override fun headersBuilder(): Headers.Builder =
        super.headersBuilder().set("User-Agent", BROWSER_UA)

    // ========================== Search & Browse ===========================

    override fun popularMangaRequest(page: Int): Request =
        GET("$listUrl?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage = parsePosts(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$listUrl?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parsePosts(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val q = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val after = resolveSearchCursor(query.trim(), page)
        // query/page ride along in the URL so searchMangaParse can update the cache
        return graphqlRequest(SEARCH_QUERY, searchVariables(query.trim(), after))
            .newBuilder()
            .url(graphqlUrl + "?search=$q&page=$page")
            .build()
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val query = response.request.url.queryParameter("search") ?: ""
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val data = response.asGraphqlData()
        val posts = data.optObject("posts") ?: JsonObject()
        val endCursor = posts.optObject("pageInfo")?.optString("endCursor")
        searchCursorState[query] = SearchCursorState(page, endCursor)
        return parsePostsJson(posts)
    }

    // ========================== Manga Details =============================

    override fun getMangaUrl(manga: SManga): String = manga.url

    override fun mangaDetailsRequest(manga: SManga): Request {
        val uri = URLEncoder.encode(manga.url, "UTF-8").replace("+", "%20")
        return graphqlRequest(DETAILS_QUERY, uriVariables(manga.url))
            .newBuilder()
            .url(graphqlUrl + "?uri=$uri")
            .build()
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val uri = response.request.url.queryParameter("uri") ?: ""
        val post = response.asGraphqlData().optObject("post") ?: JsonObject()
        val title = post.optString("title").orEmpty()
        val cover = post.optObject("featuredImage")?.optObject("node")?.optString("sourceUrl")
        val genre = mutableListOf<String>().apply {
            addAll(post.optArray("categories")?.categoryNames().orEmpty())
            addAll(post.optArray("tags")?.categoryNames().orEmpty())
        }.distinct().joinToString()
        val description = post.optString("content")
            ?.replace(Regex("<[^>]+>"), " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        return SManga.create().apply {
            this.title = title
            url = uri
            thumbnail_url = cover
            status = SManga.ONGOING
            this.genre = genre
            this.description = description?.take(DESCRIPTION_LIMIT)
        }
    }

    // ============================= Chapters ===============================

    override fun chapterListRequest(manga: SManga): Request =
        graphqlRequest(CHAPTERS_QUERY, uriVariables(manga.url))

    override fun chapterListParse(response: Response): List<SChapter> {
        val post = response.asGraphqlData().optObject("post") ?: JsonObject()
        val uri = post.optString("uri") ?: return emptyList()
        val date = post.optString("date")?.let(::parseDate) ?: 0L
        return listOf(SChapter.create().apply {
            url = uri
            name = "Gallery"
            chapter_number = 1f
            date_upload = date
        })
    }

    override fun getChapterUrl(chapter: SChapter): String = chapter.url

    // =============================== Pages ================================

    override fun pageListRequest(chapter: SChapter): Request =
        graphqlRequest(PAGES_QUERY, uriVariables(chapter.url))

    override fun pageListParse(response: Response): List<Page> {
        val post = response.asGraphqlData().optObject("post") ?: return emptyList()
        val content = post.optString("content").orEmpty()
        val pages = mutableListOf<Page>()
        IMG_SRC_REGEX.findAll(content).forEach { m ->
            val url = m.groupValues[1].trim()
            if (url.startsWith("http")) {
                pages.add(Page(pages.size, url, url))
            }
        }
        return pages
    }

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl!!, headers)

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException()

    // ============================== Helpers ===============================

    private fun parsePosts(response: Response): MangasPage {
        val json = runCatching { JsonParser.parseString(response.body?.string().orEmpty()) }
            .getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return MangasPage(emptyList(), false)
        return parsePostsJson(json)
    }

    private fun parsePostsJson(json: JsonObject): MangasPage {
        val nodes = json.optArray("nodes") ?: JsonArray()
        val mangas = nodes.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val node = element.asJsonObject
            val uri = node.optString("uri")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SManga.create().apply {
                url = uri
                title = node.optString("title").orEmpty()
                thumbnail_url = node.optObject("featuredImage")
                    ?.optObject("node")
                    ?.optString("sourceUrl")
                genre = node.optArray("categories")?.categoryNames().orEmpty().joinToString()
            }
        }
        val hasNext = json.optObject("pageInfo")?.optString("hasNextPage")?.toBooleanStrictOrNull() ?: false
        return MangasPage(mangas, hasNext)
    }

    private fun resolveSearchCursor(query: String, page: Int): String? {
        if (page <= 1) {
            searchCursorState.remove(query)
            return null
        }
        val state = searchCursorState[query]
        if (state != null && state.lastPage == page - 1) return state.lastCursor
        // Jumped pages or fresh session: walk the cursor chain up to page-1.
        var cursor: String? = null
        var walked = 0
        while (walked < page - 1) {
            val data = executeGraphql(graphqlRequest(SEARCH_QUERY, searchVariables(query, cursor)))
            val posts = data.optObject("posts") ?: return null
            cursor = posts.optObject("pageInfo")?.optString("endCursor") ?: return null
            walked++
        }
        return cursor
    }

    private fun graphqlRequest(query: String, variables: JsonObject): Request {
        val body = JsonObject().apply {
            addProperty("query", query)
            add("variables", variables)
        }
        return POST(graphqlUrl, headers, body.toString().toRequestBody(JSON_MEDIA_TYPE))
    }

    private fun executeGraphql(request: Request): JsonObject =
        client.newCall(request).execute().use { it.asGraphqlData() }

    private fun Response.asGraphqlData(): JsonObject {
        val text = body?.string().orEmpty()
        val obj = runCatching { JsonParser.parseString(text) }
            .getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw Exception("Invalid GraphQL response")
        obj.optArray("errors")?.firstOrNull()?.asJsonObject?.optString("message")?.let {
            throw Exception("GraphQL error: $it")
        }
        return obj.optObject("data") ?: JsonObject()
    }

    private fun uriVariables(uri: String): JsonObject =
        JsonObject().apply { addProperty("uri", uri) }

    private fun searchVariables(query: String, after: String?): JsonObject =
        JsonObject().apply {
            addProperty("q", query)
            if (after != null) addProperty("after", after)
        }

    private fun JsonObject.optObject(name: String): JsonObject? =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.optArray(name: String): JsonArray? =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonObject.optString(name: String): String? =
        get(name)?.takeIf { !it.isJsonNull }?.asString

    private fun JsonArray.categoryNames(): List<String> =
        mapNotNull { it.asJsonObject.optString("name")?.takeIf(String::isNotBlank) }

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val IMG_SRC_REGEX = Regex("""<img[^>]*?src="([^"]+)"""")

        private const val DESCRIPTION_LIMIT = 3000

        private const val SEARCH_QUERY =
            "query SearchPosts(\$q: String!, \$after: String) {" +
                " posts(where: {search: \$q}, first: 16, after: \$after) {" +
                "  nodes { databaseId title uri featuredImage { node { sourceUrl } } categories { nodes { name } } }" +
                "  pageInfo { hasNextPage endCursor }" +
                " }" +
                "}"

        private const val DETAILS_QUERY =
            "query Post(\$uri: ID!) {" +
                " post(id: \$uri, idType: URI) {" +
                "  databaseId title uri date featuredImage { node { sourceUrl } }" +
                "  categories { nodes { name } } tags { nodes { name } } content" +
                " }" +
                "}"

        private const val CHAPTERS_QUERY =
            "query Post(\$uri: ID!) { post(id: \$uri, idType: URI) { databaseId uri date } }"

        private const val PAGES_QUERY =
            "query Post(\$uri: ID!) { post(id: \$uri, idType: URI) { content } }"

        private data class SearchCursorState(var lastPage: Int, var lastCursor: String?)

        private val searchCursorState = HashMap<String, SearchCursorState>()
    }
}

private fun parseDate(value: String): Long? =
    runCatching { DATE_FORMAT_WITH_TZ.parse(value)?.time }.getOrNull()
        ?: runCatching { DATE_FORMAT_NO_TZ.parse(value)?.time }.getOrNull()

private val DATE_FORMAT_WITH_TZ = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
    .apply { timeZone = TimeZone.getTimeZone("UTC") }

private val DATE_FORMAT_NO_TZ = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    .apply { timeZone = TimeZone.getTimeZone("UTC") }
