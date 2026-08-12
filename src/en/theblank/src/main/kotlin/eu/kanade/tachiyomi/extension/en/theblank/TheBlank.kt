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
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import org.jsoup.nodes.Document
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

    // ============================== Helpers ==============================

    private fun JsonObject.getString(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.getInt(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
    private fun JsonObject.getArray(key: String): JsonArray? = this[key]?.jsonArray
    private fun JsonObject.getObject(key: String): JsonObject? = this[key]?.jsonObject

    private fun getInertiaProps(url: String): JsonObject {
        val request = GET(url, headers)
        val document = client.newCall(request).execute().asJsoup()
        var appDiv = document.selectFirst("div#app")?.attr("data-page")

        if (appDiv.isNullOrBlank() || document.title().contains("Just a moment", true)) {
            val payload = runInWebView(url)
            appDiv = payload
        }

        val root = json.parseToJsonElement(appDiv!!).jsonObject
        return root.getObject("props") ?: root
    }

    // ============================== Popular ==============================
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = Observable.fromCallable {
        val url = popularMangaRequest(page).url.toString()
        val props = getInertiaProps(url)

        // The Blank homepage stores popular under trendingSerie
        val trending = props.getArray("trendingSerie") ?: props.getObject("latestChapters")?.getArray("data") ?: emptyList()

        val mangas = trending.mapNotNull { it.jsonObject }.map { obj ->
            SManga.create().apply {
                url = obj.getString("link") ?: "/serie/${obj.getString("slug")}"
                title = obj.getString("title") ?: "Unknown"
                thumbnail_url = obj.getString("image")?.let { if (it.startsWith("http")) it else "$baseUrl$it" }
            }
        }
        
        // Homepage usually has meta info for pagination in latestChapters
        val meta = props.getObject("latestChapters")?.getObject("meta")
        val currentPage = meta?.getInt("current_page") ?: 1
        val lastPage = meta?.getInt("last_page") ?: 1

        MangasPage(mangas, currentPage < lastPage)
    }

    // ============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException()

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = Observable.fromCallable {
        val url = latestUpdatesRequest(page).url.toString()
        val props = getInertiaProps(url)

        val latest = props.getObject("latestChapters")?.getArray("data") ?: emptyList()

        val mangas = latest.mapNotNull { it.jsonObject }.map { obj ->
            SManga.create().apply {
                url = obj.getString("link") ?: "/serie/${obj.getString("slug")}"
                title = obj.getString("title") ?: "Unknown"
                thumbnail_url = obj.getString("image")?.let { if (it.startsWith("http")) it else "$baseUrl$it" }
            }
        }
        
        val meta = props.getObject("latestChapters")?.getObject("meta")
        val currentPage = meta?.getInt("current_page") ?: 1
        val lastPage = meta?.getInt("last_page") ?: 1

        MangasPage(mangas, currentPage < lastPage)
    }

    // ============================== Search ===============================
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return GET("$baseUrl/search?q=${query.trim()}&page=$page", headers) 
    }

    override fun searchMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = Observable.fromCallable {
        val url = searchMangaRequest(page, query, filters).url.toString()
        val props = getInertiaProps(url)

        val searchResults = props.getObject("series")?.getArray("data") ?: props.getArray("data") ?: props.getArray("series") ?: emptyList()

        val mangas = searchResults.mapNotNull { it.jsonObject }.map { obj ->
            SManga.create().apply {
                url = obj.getString("link") ?: "/serie/${obj.getString("slug")}"
                title = obj.getString("title") ?: "Unknown"
                thumbnail_url = obj.getString("image")?.let { if (it.startsWith("http")) it else "$baseUrl$it" }
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
        val url = baseUrl + manga.url
        val props = getInertiaProps(url)
        
        val serie = props.getObject("serie") ?: props.getObject("data") ?: throw Exception("Serie data not found")
        
        manga.apply {
            title = serie.getString("title") ?: title
            thumbnail_url = serie.getString("image")?.let { if (it.startsWith("http")) it else "$baseUrl$it" } ?: thumbnail_url
            description = serie.getString("description") ?: serie.getString("synopsis") ?: ""
            status = when (serie.getString("serie_status")?.lowercase()) {
                "ongoing" -> SManga.ONGOING
                "finished", "completed" -> SManga.COMPLETED
                "onhold", "hiatus" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
            genre = serie.getArray("genres_slugs")?.mapNotNull { it.jsonPrimitive.contentOrNull?.replaceFirstChar { c -> c.uppercase() } }?.joinToString()
        }
    }

    // ============================= Chapters ==============================
    override fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException()

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        val url = baseUrl + manga.url
        val props = getInertiaProps(url)
        
        val serie = props.getObject("serie") ?: props.getObject("data") ?: throw Exception("Serie data not found")
        val chaptersArr = serie.getArray("chapters") ?: emptyList()
        
        chaptersArr.mapNotNull { it.jsonObject }.map { obj ->
            SChapter.create().apply {
                url = manga.url + "/chapter/" + obj.getString("slug")
                name = obj.getString("title") ?: "Chapter ${obj.getInt("chapterNumber") ?: ""}"
                date_upload = parseDate(obj.getString("createdAt"))
                chapter_number = obj.getInt("chapterNumber")?.toFloat() ?: -1f
            }
        }.sortedByDescending { it.chapter_number }
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
        val url = baseUrl + chapter.url
        val props = getInertiaProps(url)
        
        val chapterData = props.getObject("chapter") ?: props
        val images = chapterData.getArray("images") ?: props.getArray("images") ?: emptyList()
        
        images.mapNotNull { it.jsonPrimitive.contentOrNull }.mapIndexed { index, imgUrl ->
            val fullUrl = if (imgUrl.startsWith("http")) imgUrl else "$baseUrl$imgUrl"
            Page(index, imageUrl = fullUrl)
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================= WebView Bypasser =============================

    @SuppressLint("SetJavaScriptEnabled")
    @Synchronized
    private fun runInWebView(url: String): String {
        val handler = Handler(Looper.getMainLooper())
        val payloadResult = WebViewPayloadResult()
        val pool = ('a'..'z') + ('A'..'Z')
        val interfaceName = (1..(10..20).random()).map { pool.random() }.joinToString("")
        val emptyResponse = WebResourceResponse("text/plain", "utf-8", Buffer().inputStream())
        val active = AtomicBoolean(true)
        val started = Semaphore(0)
        val startupError = AtomicReference<Throwable?>()

        // The Inertia Data Scraper
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

        var webView: WebView? = null
        var injectScript: Runnable? = null

        handler.post {
            try {
                if (!active.get()) return@post

                val view = WebView(applicationContext)
                webView = view

                runCatching {
                    view.layoutParams = ViewGroup.LayoutParams(1080, 1920)
                    view.measure(
                        View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                    )
                    view.layout(0, 0, 1080, 1920)
                }

                with(view.settings) {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    blockNetworkImage = true // Speeds up loading by ignoring visual assets
                    userAgentString = headers["User-Agent"]
                }

                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(view, true)
                }

                view.addJavascriptInterface(payloadResult, interfaceName)

                view.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        val requestUrl = request.url?.toString()?.toHttpUrlOrNull()
                            ?: return super.shouldInterceptRequest(view, request)

                        // Block heavy assets to speed up execution
                        val path = requestUrl.encodedPath.lowercase()
                        if (path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") ||
                            path.endsWith(".webp") || path.endsWith(".gif") || path.endsWith(".mp4") ||
                            path.endsWith(".woff") || path.endsWith(".woff2")
                        ) {
                            return emptyResponse
                        }

                        return super.shouldInterceptRequest(view, request)
                    }

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
                            handler.postDelayed(this, 100L) // Poll every 100ms
                        }
                    }
                }
                injectScript = retry

                view.loadUrl(url)
                handler.post(retry)
            } catch (error: Throwable) {
                startupError.set(error)
            } finally {
                started.release()
            }
        }

        val completed = try {
            if (!started.tryAcquire(90L, TimeUnit.SECONDS)) {
                throw Exception("Timed out starting WebView")
            }
            startupError.get()?.let {
                throw Exception("Failed to start WebView", it)
            }
            payloadResult.await(90L, TimeUnit.SECONDS)
        } finally {
            active.set(false)
            handler.post {
                injectScript?.let(handler::removeCallbacks)
                val view = webView
                webView = null
                runCatching { view?.stopLoading() }
                runCatching { view?.destroy() }
            }
        }

        if (!completed) {
            throw Exception("Timed out waiting for Inertia JSON payload")
        }
        return payloadResult.payload ?: throw Exception("Failed to capture Inertia payload")
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
}
