package eu.kanade.tachiyomi.extension.en.theblank

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import codegeasse.utils.applicationContext
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import rx.Observable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class TheBlank : HttpSource() {

    override val name = "The Blank"
    override val baseUrl = "https://theblank.net"
    override val lang = "en"
    override val supportsLatest = true

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override val client = network.client.newBuilder()
        .rateLimit(3)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)
        .add("Accept", "*/*")
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36")

    // ============================== Helpers ==============================

    private fun JsonObject.getString(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.getInt(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
    private fun JsonObject.getArray(key: String): JsonArray? = this[key]?.jsonArray
    private fun JsonObject.getObject(key: String): JsonObject? = this[key]?.jsonObject

    private fun getInertiaProps(requestUrl: String): JsonObject {
        val request = GET(requestUrl, headers)
        val document = client.newCall(request).execute().asJsoup()
        var appDiv = document.selectFirst("div#app")?.attr("data-page")

        if (appDiv.isNullOrBlank() || document.title().contains("Just a moment", true)) {
            appDiv = runInWebViewForDataPage(requestUrl)
        }

        val root = json.parseToJsonElement(appDiv!!).jsonObject
        return root.getObject("props") ?: root
    }

    // ============================== Popular ==============================
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = Observable.fromCallable {
        val reqUrl = popularMangaRequest(page).url.toString()
        val props = getInertiaProps(reqUrl)

        val trending = props.getArray("trendingSerie") ?: props.getObject("latestChapters")?.getArray("data") ?: emptyList()

        val mangas = trending.mapNotNull { it.jsonObject }.map { obj ->
            SManga.create().apply {
                val rawUrl = obj.getString("link") ?: "/serie/${obj.getString("slug")}"
                this.url = rawUrl.removePrefix(baseUrl)
                this.title = obj.getString("title") ?: "Unknown"
                this.thumbnail_url = obj.getString("image")?.let { if (it.startsWith("http")) it else "$baseUrl$it" }
            }
        }
        
        val meta = props.getObject("latestChapters")?.getObject("meta")
        val currentPage = meta?.getInt("current_page") ?: 1
        val lastPage = meta?.getInt("last_page") ?: 1

        MangasPage(mangas, currentPage < lastPage)
    }

    // ============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException()

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = Observable.fromCallable {
        val reqUrl = latestUpdatesRequest(page).url.toString()
        val props = getInertiaProps(reqUrl)

        val latest = props.getObject("latestChapters")?.getArray("data") ?: emptyList()

        val mangas = latest.mapNotNull { it.jsonObject }.map { obj ->
            SManga.create().apply {
                val rawUrl = obj.getString("link") ?: "/serie/${obj.getString("slug")}"
                this.url = rawUrl.removePrefix(baseUrl)
                this.title = obj.getString("title") ?: "Unknown"
                this.thumbnail_url = obj.getString("image")?.let { if (it.startsWith("http")) it else "$baseUrl$it" }
            }
        }
        
        val meta = props.getObject("latestChapters")?.getObject("meta")
        val currentPage = meta?.getInt("current_page") ?: 1
        val lastPage = meta?.getInt("last_page") ?: 1

        MangasPage(mangas, currentPage < lastPage)
    }

    // ============================== Search & Filters =====================
    
    override fun getFilterList() = FilterList(
        Filter.Header("Text search ignores filters!"),
        Filter.Separator(),
        SortFilter(),
        GenreFilter(),
        TypeFilter(),
        StatusFilter(),
    )

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("search")
            addQueryParameter("page", page.toString())

            if (query.isNotBlank()) {
                addQueryParameter("q", query.trim())
            } else {
                filters.filterIsInstance<SortFilter>().firstOrNull()?.let {
                    val sortValue = sortValues[it.state?.index ?: 2].second
                    addQueryParameter("sort", sortValue)
                }

                filters.filterIsInstance<GenreFilter>().firstOrNull()?.state?.forEach {
                    if (it.state == Filter.TriState.STATE_INCLUDE) addQueryParameter("genres[]", it.value)
                }
                
                filters.filterIsInstance<TypeFilter>().firstOrNull()?.state?.forEach {
                    if (it.state == Filter.TriState.STATE_INCLUDE) addQueryParameter("types[]", it.value)
                }
                
                filters.filterIsInstance<StatusFilter>().firstOrNull()?.state?.forEach {
                    if (it.state) addQueryParameter("status[]", it.value)
                }
            }
        }.build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = Observable.fromCallable {
        val reqUrl = searchMangaRequest(page, query, filters).url.toString()
        val props = getInertiaProps(reqUrl)

        val searchResults = props.getObject("series")?.getArray("data") ?: props.getArray("data") ?: props.getArray("series") ?: emptyList()

        val mangas = searchResults.mapNotNull { it.jsonObject }.map { obj ->
            SManga.create().apply {
                val rawUrl = obj.getString("link") ?: "/serie/${obj.getString("slug")}"
                this.url = rawUrl.removePrefix(baseUrl)
                this.title = obj.getString("title") ?: "Unknown"
                this.thumbnail_url = obj.getString("image")?.let { if (it.startsWith("http")) it else "$baseUrl$it" }
            }
        }

        val meta = props.getObject("series")?.getObject("meta")
        val currentPage = meta?.getInt("current_page") ?: 1
        val lastPage = meta?.getInt("last_page") ?: 1

        MangasPage(mangas, currentPage < lastPage)
    }

    // ============================== Details ==============================
    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga = throw UnsupportedOperationException()

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = Observable.fromCallable {
        val reqUrl = baseUrl + manga.url
        val props = getInertiaProps(reqUrl)
        
        val serie = props.getObject("serie") ?: props.getObject("data") ?: return@fromCallable manga
        
        manga.apply {
            this.title = serie.getString("title") ?: title
            this.thumbnail_url = serie.getString("image")?.let { if (it.startsWith("http")) it else "$baseUrl$it" } ?: thumbnail_url
            this.description = serie.getString("description") ?: serie.getString("synopsis") ?: ""
            this.status = when (serie.getString("serie_status")?.lowercase()) {
                "ongoing" -> SManga.ONGOING
                "finished", "completed" -> SManga.COMPLETED
                "onhold", "hiatus" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
            this.genre = serie.getArray("genres_slugs")?.mapNotNull { it.jsonPrimitive.contentOrNull?.replaceFirstChar { c -> c.uppercase() } }?.joinToString()
        }
    }

    // ============================= Chapters ==============================
    override fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException()

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        val reqUrl = baseUrl + manga.url
        val props = getInertiaProps(reqUrl)
        
        val chaptersList = mutableListOf<SChapter>()
        val serie = props.getObject("serie") ?: props.getObject("data")
        val chaptersArray = serie?.getArray("chapters") ?: serie?.getObject("chapters")?.getArray("data") ?: props.getArray("chapters") ?: props.getObject("chapters")?.getArray("data")
        
        if (chaptersArray != null && chaptersArray.isNotEmpty()) {
            chaptersArray.mapNotNull { it.jsonObject }.forEach { obj ->
                val slug = obj.getString("slug") ?: return@forEach
                val chapNum = obj.getString("chapter_number") ?: obj.getString("chapterNumber")
                val title = obj.getString("title") ?: obj.getString("name") ?: "Chapter $chapNum"
                val dateStr = obj.getString("createdAt") ?: obj.getString("created_at")
                
                // Safely build chapter URL to prevent 404s
                val cleanMangaUrl = manga.url.substringBefore("?").trimEnd('/')
                val mangaSlug = cleanMangaUrl.substringAfterLast("/")
                
                chaptersList.add(SChapter.create().apply {
                    this.url = "/serie/$mangaSlug/chapter/$slug"
                    this.name = title
                    this.date_upload = parseDate(dateStr)
                    this.chapter_number = chapNum?.toFloatOrNull() ?: -1f
                })
            }
        }
        
        if (chaptersList.isEmpty()) {
            throw Exception("No chapters found. Cloudflare might be blocking the payload.")
        }
        
        return@fromCallable chaptersList.distinctBy { it.url }.sortedByDescending { it.chapter_number }
    }

    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        return try {
            SimpleDateFormat("dd/MM/yyyy", Locale.US).parse(dateStr)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    // =============================== Pages ===============================
    override fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> = throw UnsupportedOperationException()

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable.fromCallable {
        val reqUrl = baseUrl + chapter.url
        
        // 1. Get the page count from the JSON payload
        val request = GET(reqUrl, headers)
        val document = client.newCall(request).execute().asJsoup()
        var appDiv = document.selectFirst("div#app")?.attr("data-page")

        if (appDiv.isNullOrBlank() || document.title().contains("Just a moment", true)) {
            appDiv = runInWebViewForDataPage(reqUrl)
        }

        val root = json.parseToJsonElement(appDiv!!).jsonObject
        val props = root.getObject("props") ?: root
        val data = props.getObject("data") ?: props.getObject("chapter") ?: props

        val pageCount = data.getInt("page_count") ?: props.getInt("page_count") ?: 0

        // 2. Use the WebView Interceptor to bypass the cryptographic DRM and catch the lazy-loaded URLs
        val signedUrls = collectSignedUrlsFromWebView(reqUrl, pageCount)

        val imageUrls = mutableListOf<String>()

        if (signedUrls.isNotEmpty()) {
            imageUrls.addAll(signedUrls)
        } else {
            // Fallback for unprotected static images if CF is off
            fun extractStrings(element: JsonElement) {
                when (element) {
                    is kotlinx.serialization.json.JsonPrimitive -> {
                        val str = element.contentOrNull
                        if (str != null && str.matches(".*\\.(jpg|jpeg|png|webp)(\\?.*)?$".toRegex(RegexOption.IGNORE_CASE))) {
                            imageUrls.add(str)
                        }
                    }
                    is JsonArray -> element.forEach { extractStrings(it) }
                    is JsonObject -> element.forEach { (_, v) -> extractStrings(v) }
                }
            }
            extractStrings(data)
        }

        var distinctUrls = imageUrls.distinct()
        distinctUrls = distinctUrls.filterNot { it.contains("banners/", ignoreCase = true) }

        if (distinctUrls.isEmpty()) {
            throw Exception("Failed to load pages. Ensure Cloudflare is bypassed.")
        }

        val sortedUrls = if (distinctUrls.any { it.contains("/page/") }) {
            val pageRegex = """/page/(\d+)""".toRegex(RegexOption.IGNORE_CASE)
            distinctUrls.sortedBy { url ->
                pageRegex.find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }
        } else {
            distinctUrls
        }

        sortedUrls.mapIndexed { index, imgUrl ->
            val fullUrl = if (imgUrl.startsWith("http")) imgUrl else "$baseUrl$imgUrl"
            Page(index, imageUrl = fullUrl)
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================= WebView Bypassers =============================

    /**
     * Extracts the raw Inertia JSON data if the normal OkHttp request gets Cloudflare blocked.
     */
    @SuppressLint("SetJavaScriptEnabled")
    @Synchronized
    private fun runInWebViewForDataPage(targetUrl: String): String {
        val handler = Handler(Looper.getMainLooper())
        val payloadResult = WebViewPayloadResult()
        val pool = ('a'..'z') + ('A'..'Z')
        val interfaceName = (1..(10..20).random()).map { pool.random() }.joinToString("")
        val active = AtomicBoolean(true)
        val started = Semaphore(0)
        
        var webView: WebView? = null
        var injectScript: Runnable? = null

        val script = """
            (function () {
                const appData = document.querySelector('div#app')?.getAttribute('data-page');
                if (appData) {
                    window.$interfaceName.passPayload(appData);
                    return true;
                }
                return false;
            })();
        """.trimIndent()

        handler.post {
            val view = WebView(applicationContext)
            webView = view

            with(view.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = headers["User-Agent"]
            }

            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(view, true)
            }

            view.addJavascriptInterface(payloadResult, interfaceName)
            
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    if (active.get() && payloadResult.payload == null) {
                        runCatching { view.evaluateJavascript(script, null) }
                    }
                }
            }

            val retry = object : Runnable {
                override fun run() {
                    if (!active.get() || payloadResult.payload != null) return
                    runCatching { view.evaluateJavascript(script, null) }
                    if (active.get() && payloadResult.payload == null) {
                        handler.postDelayed(this, 100L)
                    }
                }
            }
            injectScript = retry

            view.loadUrl(targetUrl)
            handler.post(retry)
            started.release()
        }

        started.acquire()
        val success = payloadResult.await(90L, TimeUnit.SECONDS)
        
        active.set(false)
        handler.post {
            injectScript?.let(handler::removeCallbacks)
            webView?.stopLoading()
            webView?.destroy()
        }

        if (!success) throw Exception("Timed out waiting for Inertia JSON payload")
        return payloadResult.payload ?: throw Exception("Failed to capture Inertia payload")
    }

    /**
     * Intercepts the signed cryptographic image URLs generated by the site's Vue frontend.
     * Automatically scrolls the page down to trigger lazy loading.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun collectSignedUrlsFromWebView(targetUrl: String, expectedCount: Int): List<String> {
        val interceptedUrls = mutableSetOf<String>()
        val handler = Handler(Looper.getMainLooper())
        val started = Semaphore(0)
        
        var webView: WebView? = null
        
        handler.post {
            val view = WebView(applicationContext)
            webView = view
            
            runCatching {
                view.layoutParams = ViewGroup.LayoutParams(1080, 1920)
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
                )
                view.layout(0, 0, 1080, 1920)
            }
            
            with(view.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                userAgentString = headers["User-Agent"]
            }
            
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(view, true)
            }
            
            view.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url.toString()
                    // Target the uniquely generated DRM image URLs
                    if (url.contains("/page/") && url.contains("sig=") && url.contains("token=")) {
                        synchronized(interceptedUrls) {
                            interceptedUrls.add(url)
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }
            
            view.loadUrl(targetUrl)
            started.release()
        }
        
        started.acquire()
        
        val maxWaitMillis = 20000L // 20 seconds maximum wait for slow connections
        val interval = 500L
        var waited = 0L
        
        // Emulate a user scrolling down the page to force lazy-loaded images to request their tokens
        val scrollScript = "window.scrollBy(0, 1500);"
        
        while (waited < maxWaitMillis) {
            synchronized(interceptedUrls) {
                if (expectedCount > 0 && interceptedUrls.size >= expectedCount) {
                    break // All pages have been intercepted and signed!
                }
            }
            handler.post {
                webView?.evaluateJavascript(scrollScript, null)
            }
            Thread.sleep(interval)
            waited += interval
        }
        
        val finalUrls = synchronized(interceptedUrls) { interceptedUrls.toList() }
        
        handler.post {
            webView?.stopLoading()
            webView?.destroy()
        }
        
        return finalUrls
    }

    private class WebViewPayloadResult {
        private val signal = Semaphore(0)

        @Volatile
        var payload: String? = null
            private set

        @JavascriptInterface
        @Suppress("UNUSED")
        fun passPayload(data: String) {
            if (payload == null) {
                payload = data
                signal.release()
            }
        }

        fun await(timeout: Long, unit: TimeUnit): Boolean {
            while (payload == null) {
                if (!signal.tryAcquire(timeout, unit)) return false
            }
            return true
        }
    }

    // ============================== Filter Classes ==============================
    
    class TriStateFilter(name: String, val value: String) : Filter.TriState(name)
    class CheckBoxFilter(name: String, val value: String) : Filter.CheckBox(name)
    
    class SortFilter : Filter.Sort("Sort", sortValues.map { it.first }.toTypedArray(), Selection(2, false))
    class GenreFilter : Filter.Group<TriStateFilter>("Genres", genres.map { TriStateFilter(it.first, it.second) })
    class TypeFilter : Filter.Group<TriStateFilter>("Types", types.map { TriStateFilter(it.first, it.second) })
    class StatusFilter : Filter.Group<CheckBoxFilter>("Status", statuses.map { CheckBoxFilter(it.first, it.second) })

    companion object {
        private val sortValues = listOf(
            "New Series" to "date",
            "Trending" to "trending",
            "Recently Updated" to "recently",
            "Most Views" to "views",
            "A-Z" to "alphabetical"
        )

        private val genres = listOf(
            "Action" to "action",
            "Adventure" to "adventure",
            "Ai" to "ai",
            "Animated" to "animated",
            "Anthology" to "anthology",
            "Cohabitation" to "cohabitation",
            "College" to "college",
            "Comedy" to "comedy",
            "Doujinshi" to "doujinshi",
            "Drama" to "drama",
            "Fantasy" to "fantasy",
            "Folklore" to "folklore",
            "Harem" to "harem",
            "Historical" to "historical",
            "Horror" to "horror",
            "Isekai" to "isekai",
            "Josei" to "josei",
            "Love triangle" to "love-triangle",
            "Martial arts" to "martial-arts",
            "Mature" to "mature",
            "Murim" to "murim",
            "Mystery" to "mystery",
            "Office workers" to "office-workers",
            "Psychological" to "psychological",
            "Robots" to "robots",
            "Romance" to "romance",
            "School life" to "school-life",
            "Sci-fi" to "sci-fi",
            "Seinen" to "seinen",
            "Shoujo" to "shoujo",
            "Shounen" to "shounen",
            "Slice of life" to "slice-of-life",
            "Smut" to "smut",
            "Sports" to "sports",
            "Supernatural" to "supernatural",
            "Superpower" to "superpower",
            "System" to "system",
            "Thriller" to "thriller",
            "Uncensored" to "uncensored",
            "Violence" to "violence",
            "Workplace" to "workplace"
        )

        private val types = listOf(
            "Comic" to "comic",
            "Doujin" to "doujin",
            "Josei" to "josei",
            "Manga" to "manga",
            "Manhua" to "manhua",
            "Manhwa" to "manhwa",
            "Pornhwa" to "pornhwa",
            "Webtoon" to "webtoon"
        )

        private val statuses = listOf(
            "Ongoing" to "ongoing",
            "Finished" to "finished",
            "Dropped" to "dropped",
            "On Hold" to "onhold",
            "Upcoming" to "upcoming"
        )
    }
}
