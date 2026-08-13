package eu.kanade.tachiyomi.extension.en.comix

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
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
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import codegeasse.utils.applicationContext
import codegeasse.utils.firstInstanceOrNull
import codegeasse.utils.getPreferencesLazy
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.jsoup.nodes.Document
import rx.Observable
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Calendar
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class Comix : HttpSource(), ConfigurableSource {

    override val name = "Comix"
    override val baseUrl = "https://comix.to"
    override val lang = "en"

    private val apiUrl get() = "$baseUrl/api/v1"
    override val supportsLatest = true

    val supportsRelatedMangas = false
    val disableRelatedMangasBySearch = true

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private inline fun <reified T> String.parseAs(): T {
        return json.decodeFromString(this)
    }

    override val client = network.client.newBuilder()
        .addInterceptor(Descrambler.interceptor)
        .addInterceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            
            if (response.code != 404) return@addInterceptor response

            val url = request.url.toString()
            val fallbacks = listOf("/i5/", "/si/", "/i/", "/sii/", "/ii/")
                .map { url.replaceFirst(SCRAMBLE_PATH_FALLBACK_REGEX, it) }
                .filter { it != url }

            if (fallbacks.isEmpty()) return@addInterceptor response

            var lastResponse = response
            for (fallbackUrl in fallbacks) {
                lastResponse.close()
                lastResponse = chain.proceed(request.newBuilder().url(fallbackUrl).build())
                if (lastResponse.code != 404) break
            }
            lastResponse
        }
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)
        .add("Accept", "*/*")

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request {
        val imageUrl = page.imageUrl ?: return super.imageRequest(page)
        val urlWithoutFragment = imageUrl.substringBefore('#')
        val imageHost = urlWithoutFragment.toHttpUrlOrNull()?.host.orEmpty()
        val isScrambled = imageUrl.contains("#scrambled")
        val isV3 = urlWithoutFragment.toHttpUrlOrNull()?.queryParameterNames?.contains("v3") == true
        val isLegacyScramble = isScrambled && !isV3
        val baseUrlHost = baseUrl.toHttpUrl().host
        val requestHeaders = if (
            imageHost.isNotEmpty() &&
            !imageHost.endsWith(baseUrlHost) &&
            !isLegacyScramble
        ) {
            headersBuilder().removeAll("Origin").build()
        } else {
            headers
        }
        return GET(urlWithoutFragment, requestHeaders)
    }

    // ============================== Popular ==============================
    override fun popularMangaRequest(page: Int): Request {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("browse")
            addQueryParameter("order[score]", "desc")
            addQueryParameter("page", page.toString())
            applyBrowseContentPreferences()
        }.build()

        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response) = throw UnsupportedOperationException()

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = fetchMangaListFromBrowse(popularMangaRequest(page))

    private fun fetchMangaListFromBrowse(request: Request): Observable<MangasPage> = Observable.fromCallable {
        val document = client.newCall(request).execute().asJsoup()

        val contentRating = request.url.queryParameter("content_rating") ?: preferences.contentRating()
        val effectiveContentRating = contentRating
            .split(',')
            .lastOrNull { it.isNotBlank() }
            .orEmpty()
            .ifEmpty { "pornographic" }
            
        val expectedKeyword = JSONObject.quote(request.url.queryParameter("q") ?: request.url.queryParameter("keyword").orEmpty())
        
        val searchResponse = document.extractBrowseResponse() ?: runInWebView(
            document = document,
            initializationScript = """
                (function () {
                    const key = 'settings_v2';
                    let settings = {};
                    try { settings = JSON.parse(localStorage.getItem(key) || '{}'); } catch (e) {}
                    settings.state = { ...(settings.state || {}), contentFilter: '$effectiveContentRating' };
                    if (settings.version === undefined) settings.version = 0;
                    localStorage.setItem(key, JSON.stringify(settings));
                })();
            """.trimIndent(),
            buildScript = { interfaceName ->
                """
                    (function () {
                        const payloadKey = '__comixBrowsePayload';
                        const expectedKeyword = $expectedKeyword;
                        const capture = (parsed, allowEmpty = false) => {
                            try {
                                if (parsed && Array.isArray(parsed.items)) parsed = { result: parsed };
                                if (parsed && parsed.result && Array.isArray(parsed.result.items) && (allowEmpty || parsed.result.items.length > 0)) {
                                    window[payloadKey] = JSON.stringify(parsed);
                                    window.$interfaceName.passPayload(window[payloadKey]);
                                    return true;
                                }
                            } catch (e) {}
                            return false;
                        };

                        if (window[payloadKey]) return window[payloadKey];

                        try {
                            const raw = document.querySelector('script#initial-data')?.textContent;
                            const queries = raw && JSON.parse(raw).queries;
                            if (queries) Object.values(queries).some(capture);
                        } catch (e) {}

                        if (window[payloadKey]) return window[payloadKey];
                        if (window.__comixBrowseCaptureInstalled) return null;
                        window.__comixBrowseCaptureInstalled = true;

                        const captureText = text => { try { if (text) capture(JSON.parse(text), true); } catch (e) {} };

                        const shouldCaptureUrl = rawUrl => {
                            try {
                                const url = new URL(rawUrl || '', window.location.origin);
                                if (!url.pathname.includes('/api/v1/manga')) return false;
                                if (!expectedKeyword) return true;
                                return url.searchParams.get('keyword') === expectedKeyword;
                            } catch (e) { return false; }
                        };

                        const originalFetch = window.fetch;
                        if (typeof originalFetch === 'function') {
                            window.fetch = function () {
                                return originalFetch.apply(this, arguments).then(response => {
                                    try {
                                        const url = response && response.url || '';
                                        if (shouldCaptureUrl(url)) response.clone().text().then(captureText).catch(() => {});
                                    } catch (e) {}
                                    return response;
                                });
                            };
                        }

                        const originalOpen = XMLHttpRequest.prototype.open;
                        const originalSend = XMLHttpRequest.prototype.send;
                        XMLHttpRequest.prototype.open = function (method, url) {
                            this.__comixBrowseUrl = String(url || '');
                            return originalOpen.apply(this, arguments);
                        };
                        XMLHttpRequest.prototype.send = function () {
                            this.addEventListener('load', function () {
                                try { if (shouldCaptureUrl(this.__comixBrowseUrl)) captureText(this.responseText); } catch (e) {}
                            });
                            return originalSend.apply(this, arguments);
                        };

                        const originalParse = JSON.parse;
                        const proxiedParse = new Proxy(originalParse, {
                            apply(target, thisArg, args) {
                                const parsed = Reflect.apply(target, thisArg, args);
                                if (!expectedKeyword) capture(parsed);
                                return parsed;
                            }
                        });
                        JSON.parse = proxiedParse;
                        return window[payloadKey] || null;
                    })();
                """.trimIndent()
            },
        ).parseAs<SearchResponse>()

        val mangaList = searchResponse.result.items.map {
            it.toBasicSManga(preferences.posterQuality())
        }
        MangasPage(mangaList, searchResponse.result.hasNextPage())
    }

    private fun Document.extractBrowseResponse(): SearchResponse? {
        val initialData = selectFirst("script#initial-data")?.data() ?: return null
        val queries = runCatching {
            initialData.parseAs<JsonObject>()["queries"] as? JsonObject
        }.getOrNull() ?: return null

        return queries.values.firstNotNullOfOrNull { value ->
            runCatching { value.toString().parseAs<SearchResponse>() }
                .getOrNull()
                ?.takeIf { it.result.items.isNotEmpty() }
        }
    }

    // ============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("browse")
            addQueryParameter("order[chapter_updated_at]", "desc")
            addQueryParameter("page", page.toString())
            applyBrowseContentPreferences()
        }.build()

        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()
    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = fetchMangaListFromBrowse(latestUpdatesRequest(page))

    // ============================== Search ===============================
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val withFilters = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("browse")
            .apply {
                filters.filterIsInstance<Filters.UriFilter>().forEach { it.addToUri(this) }
                filters.firstInstanceOrNull<Filters.AuthorFilter>()?.state
                    ?.let { resolveTagIdsForNames("author", it) }
                    ?.forEach { addQueryParameter("authors[]", it) }
                filters.firstInstanceOrNull<Filters.ArtistFilter>()?.state
                    ?.let { resolveTagIdsForNames("artist", it) }
                    ?.forEach { addQueryParameter("artists[]", it) }
                filters.firstInstanceOrNull<Filters.TagsFilter>()?.state
                    ?.let { resolveTagIdsForNames("tag", it) }
                    ?.forEach { addQueryParameter("genres_in[]", it) }
            }
            .build()

        val url = withFilters.newBuilder().apply {
            if (withFilters.queryParameter("content_rating") == null) applyContentRatingPreference()
            if (withFilters.queryParameterValues("types[]").isEmpty()) applyTypesPreference()
            if (withFilters.queryParameterValues("demographics[]").isEmpty()) applyDemographicsPreference()
            applyBlockedGenresPreference()

            val hasTermSelection = build().queryParameterValues("genres_in[]").isNotEmpty() || build().queryParameterValues("genres_ex[]").isNotEmpty()
            if (!hasTermSelection) removeAllQueryParameters("genres_mode")

            if (query.isNotBlank()) {
                addQueryParameter("q", query)
                build().queryParameterNames.filter { it.startsWith("order[") }.forEach(::removeAllQueryParameters)
                addQueryParameter("sort", "relevance:desc")
            }
            addQueryParameter("page", page.toString())
        }.build()

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response) = throw UnsupportedOperationException()

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        titlePathFromQuery(query)?.let { titlePath ->
            return fetchMangaDetails(SManga.create().apply { url = titlePath }).map { MangasPage(listOf(it), false) }
        }
        return fetchMangaListFromBrowse(searchMangaRequest(page, query, filters))
    }

    private fun titlePathFromQuery(query: String): String? {
        val queryUrl = query.trim().takeIf { it.isNotEmpty() }?.toHttpUrlOrNull() ?: return null
        val host = queryUrl.host.removePrefix("www.")
        if (host != baseUrl.toHttpUrl().host.removePrefix("www.")) return null
        if (queryUrl.pathSegments.size < 2 || queryUrl.pathSegments[0] != "title") return null

        val mangaId = queryUrl.pathSegments[1].substringBefore("-")
        return mangaId.takeIf { it.isNotBlank() }?.let { "/$it" }
    }

    private fun HttpUrl.Builder.applyBrowseContentPreferences() {
        applyContentRatingPreference()
        applyTypesPreference()
        applyDemographicsPreference()
        applyBlockedGenresPreference()
    }

    private fun HttpUrl.Builder.applyContentRatingPreference() {
        Filters.getContentRatingsUpTo(preferences.contentRating()).takeIf { it.isNotEmpty() }?.let {
            addQueryParameter("content_rating", it.joinToString(","))
        }
    }

    private fun HttpUrl.Builder.applyTypesPreference() {
        val selected = preferences.defaultTypes()
        val all = Filters.getTypes().map { it.second }.toSet()
        if (selected.isEmpty() || selected == all) return
        selected.forEach { addQueryParameter("types[]", it) }
    }

    private fun HttpUrl.Builder.applyDemographicsPreference() {
        val selected = preferences.defaultDemographics()
        val all = Filters.getDemographics().map { it.second }.toSet()
        if (selected.isEmpty() || selected == all) return
        selected.forEach { addQueryParameter("demographics[]", it) }
    }

    private fun HttpUrl.Builder.applyBlockedGenresPreference() {
        val blocked = preferences.blockedGenres()
        if (blocked.isEmpty()) return
        val explicitlyIncluded = build().queryParameterValues("genres_in[]").toSet()
        blocked.asSequence().filter { it !in explicitlyIncluded }.forEach { addQueryParameter("genres_ex[]", it) }
    }

    private fun resolveTagIdsForNames(type: String, raw: String): List<String> = raw
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .flatMap { resolveTagIds(type, it) }

    private fun resolveTagIds(type: String, name: String): List<String> {
        val url = apiUrl.toHttpUrl().newBuilder()
            .addPathSegment("tags")
            .addPathSegment("search")
            .addQueryParameter("type", type)
            .addQueryParameter("q", name)
            .build()

        return runCatching {
            client.newCall(GET(url, headers)).execute().use { response ->
                response.body?.string()?.parseAs<TagSearchResponse>()?.result?.map { it.id.toString() } ?: emptyList()
            }
        }.getOrDefault(emptyList())
    }

    // ============================== Filters ==============================
    override fun getFilterList() = Filters().getFilterList()

    // ============================== Details ==============================
    override fun mangaDetailsRequest(manga: SManga): Request = GET(getMangaUrl(manga), headers)
    override fun mangaDetailsParse(response: Response) = throw UnsupportedOperationException()
    override fun getMangaUrl(manga: SManga): String = "$baseUrl/title${manga.url}"

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = Observable.fromCallable {
        val document = client.newCall(GET(getMangaUrl(manga), headers)).execute().asJsoup()
        val initialData = document.selectFirst("script#initial-data")?.data() ?: throw Exception("Could not find manga data in page")
        val root = initialData.parseAs<JsonObject>()
        val queries = root["queries"] as? JsonObject ?: throw Exception("Could not find queries in manga data")
        val detail = queries.entries.firstOrNull { (key, _) -> key.contains("\"detail\"") }?.value ?: throw Exception("Could not find manga detail in queries")

        detail.toString().parseAs<Manga>().toSManga(
            preferences.posterQuality(),
            preferences.alternativeNamesInDescription(),
            preferences.scorePosition(),
            preferences.showExtraInfo(),
            preferences.showTagsInGenres(),
        )
    }

    // ============================= Chapters ==============================
    override fun getChapterUrl(chapter: SChapter) = "$baseUrl/${chapter.url}"

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        val deduplicate = preferences.deduplicateChapters()
        val blacklist = preferences.scanlatorBlacklist()
        val mangaSlug = manga.url.removePrefix("/")

        val document = client.newCall(GET(getMangaUrl(manga), headers)).execute().asJsoup()
        
        // SUPERFAST OPTIMIZATION: Extract chapters natively if pagination is not needed
        val initialData = document.selectFirst("script#initial-data")?.data()
        var fastPathChapters: List<Chapter>? = null
        
        if (initialData != null) {
            runCatching {
                val queries = initialData.parseAs<JsonObject>()["queries"] as? JsonObject
                queries?.values?.forEach { value ->
                    val parsed = runCatching { value.toString().parseAs<ChapterDetailsResponse>() }.getOrNull()
                    val items = parsed?.result?.items
                    if (!items.isNullOrEmpty() && items.first().id != 0) {
                        if (parsed.result.hasNextPage() == false) {
                            fastPathChapters = items // Skipped the WebView entirely!
                        }
                    }
                }
            }
        }

        val allChapters = fastPathChapters ?: run {
            val payload = runInWebView(
                document = document,
                buildScript = { interfaceName ->
                    """
                        (function () {
                            const payloadKey = '__comixChapterPayload';
                            if (window[payloadKey]?.installed) return null;
                            const state = window[payloadKey] = { installed: true, submitted: false, seen: new Set(), nextClicks: new Set(), items: [] };
                            const submit = () => {
                                if (state.submitted) return;
                                state.submitted = true;
                                window.$interfaceName.passPayload(JSON.stringify(state.items));
                            };
                            const findNextButton = page => {
                                const buttons = [...document.querySelectorAll('.mchap-foot button')].filter(button => !button.disabled);
                                return buttons.find(button => {
                                    const label = [button.getAttribute('aria-label'), button.getAttribute('title'), button.textContent].filter(Boolean).join(' ');
                                    return /\bnext\b/i.test(label);
                                }) || buttons.find(button => Number(button.textContent?.trim()) === page + 1);
                            };
                            const capture = parsed => {
                                try {
                                    const items = parsed?.result?.items;
                                    if (state.submitted || !Array.isArray(items) || items.length === 0 || items[0]?.id === undefined) return false;
                                    const meta = parsed.result.meta || parsed.result.pagination || {};
                                    const page = meta.page || 1;
                                    const lastPage = meta.lastPage || meta.last_page || page;
                                    if (state.seen.has(page)) return true;
                                    state.seen.add(page);
                                    state.items.push(...items);
                                    if ((meta.hasNext || page < lastPage) && !state.nextClicks.has(page)) {
                                        state.nextClicks.add(page);
                                        window.$interfaceName.resetTimer();
                                        let tries = 0;
                                        const interval = setInterval(() => {
                                            const button = findNextButton(page);
                                            if (button) { button.click(); clearInterval(interval); }
                                            else if (++tries > 500) { clearInterval(interval); submit(); }
                                        }, 10);
                                    } else submit();
                                    return true;
                                } catch (e) { return false; }
                            };
                            const proxiedParse = new Proxy(JSON.parse, { apply(target, thisArg, args) { const parsed = Reflect.apply(target, thisArg, args); capture(parsed); return parsed; } });
                            proxiedParse.__comixChapterCaptureInstalled = true;
                            JSON.parse = proxiedParse;
                            try {
                                const raw = document.querySelector('script#initial-data')?.textContent;
                                const queries = raw && JSON.parse(raw).queries;
                                if (queries) Object.values(queries).some(capture);
                            } catch (e) {}
                            return null;
                        })();
                    """.trimIndent()
                },
            )
            payload.parseAs<List<Chapter>>()
        }

        val filteredChapters = if (blacklist.isNotEmpty()) {
            allChapters.filter { ch ->
                val scanlatorName = if (ch.group != null) ch.group.name else if (ch.isOfficial) "Official" else "Unknown"
                val nameNormalized = scanlatorName.trim().lowercase()
                val idStr = ch.group?.id?.toString()
                nameNormalized !in blacklist && idStr !in blacklist
            }
        } else {
            allChapters
        }

        val finalChapters: List<Chapter> = if (deduplicate) {
            val chapterMap = LinkedHashMap<Number, Chapter>()
            deduplicateChapters(chapterMap, filteredChapters)
            chapterMap.values.toList()
        } else {
            filteredChapters
        }

        finalChapters.map { it.toSChapter(mangaSlug) }
    }

    override fun chapterListRequest(manga: SManga): Request = throw UnsupportedOperationException()
    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException()

    private fun deduplicateChapters(chapterMap: LinkedHashMap<Number, Chapter>, items: List<Chapter>) {
        for (ch in items) {
            val current = chapterMap[ch.number]
            if (current == null) {
                chapterMap[ch.number] = ch
            } else {
                val better = when {
                    ch.isOfficial && !current.isOfficial -> true
                    !ch.isOfficial && current.isOfficial -> false
                    ch.group?.id == 10702 && current.group?.id != 10702 -> true
                    ch.group?.id != 10702 && current.group?.id == 10702 -> false
                    else -> if (ch.votes != current.votes) ch.votes > current.votes else ch.id > current.id
                }
                if (better) chapterMap[ch.number] = ch
            }
        }
    }

    // =============================== Pages ===============================
    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable.fromCallable {
        val request = GET(getChapterUrl(chapter), headers)
        val document = client.newCall(request).execute().asJsoup()
        
        // SUPERFAST OPTIMIZATION: Read directly from page JSON without launching the browser
        val initialData = document.selectFirst("script#initial-data")?.data()
        var pagesPayload: ChapterResponse.Pages? = null
        
        if (initialData != null) {
            runCatching {
                val queries = initialData.parseAs<JsonObject>()["queries"] as? JsonObject
                queries?.values?.forEach { value ->
                    val parsed = runCatching { value.toString().parseAs<ChapterResponse>() }.getOrNull()
                    if (parsed?.result?.pages != null) {
                        pagesPayload = parsed.result.pages
                    }
                }
            }
        }

        val pages = pagesPayload ?: run {
            val payload = runInWebView(
                document = document,
                buildScript = { interfaceName ->
                    """
                    (function () {
                        const payloadKey = '__comixPagePayload';
                        const capture = parsed => {
                            try {
                                if (parsed?.result?.pages) {
                                    window[payloadKey] = JSON.stringify(parsed);
                                    window.$interfaceName.passPayload(window[payloadKey]);
                                    return true;
                                }
                            } catch (e) {}
                            return false;
                        };
                        if (window[payloadKey]) return window[payloadKey];
                        try {
                            const raw = document.querySelector('script#initial-data')?.textContent;
                            if (raw) { Object.values(JSON.parse(raw).queries || {}).some(capture); }
                        } catch (e) {}
                        if (window[payloadKey]) return window[payloadKey];
                        if (JSON.parse.__comixPageCaptureInstalled) return null;
                        
                        const proxiedParse = new Proxy(JSON.parse, {
                            apply(target, thisArg, args) {
                                const parsed = Reflect.apply(target, thisArg, args);
                                capture(parsed);
                                return parsed;
                            }
                        });
                        proxiedParse.__comixPageCaptureInstalled = true;
                        JSON.parse = proxiedParse;
                        return window[payloadKey] || null;
                    })();
                    """.trimIndent()
                },
            )
            payload.parseAs<ChapterResponse>().result.pages
        }

        val base = pages.baseUrl.trimEnd('/')

        pages.items.mapIndexed { index, img ->
            val full = if (img.url.startsWith("http")) img.url else "$base/${img.url.trimStart('/')}"
            val isV3 = img.s == 1 || full.contains("?v3")
            val isLegacyScramble = !isV3 && (index + 1) % 4 == 0
            val url = when {
                isV3 -> full.toHttpUrl().newBuilder().apply {
                    if (!full.toHttpUrl().queryParameterNames.contains("v3")) addQueryParameter("v3", null)
                }.build().toString()
                isLegacyScramble -> "$full#scrambled"
                else -> full
            }
            Page(index, imageUrl = url)
        }
    }

    override fun pageListRequest(chapter: SChapter): Request = throw UnsupportedOperationException()
    override fun pageListParse(response: Response): List<Page> = throw UnsupportedOperationException()

    @SuppressLint("SetJavaScriptEnabled")
    @Synchronized
    private fun runInWebView(
        document: Document,
        initializationScript: String? = null,
        buildScript: (interfaceName: String) -> String,
    ): String {
        val handler = Handler(Looper.getMainLooper())
        val payloadResult = WebViewPayloadResult()
        val pool = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val interfaceName = (1..(10..20).random()).map { pool.random() }.joinToString("")
        val script = buildScript(interfaceName)
        val emptyResponse = WebResourceResponse("text/plain", "utf-8", Buffer().inputStream())
        val active = AtomicBoolean(true)
        val started = Semaphore(0)
        val startupError = AtomicReference<Throwable?>()

        var webView: WebView? = null
        var injectScript: Runnable? = null
        var lastUrl = document.location()
        
        handler.post {
            try {
                if (!active.get()) return@post

                val view = WebView(applicationContext)
                webView = view

                runCatching {
                    view.layoutParams = ViewGroup.LayoutParams(WEBVIEW_WIDTH, WEBVIEW_HEIGHT)
                    view.measure(View.MeasureSpec.makeMeasureSpec(WEBVIEW_WIDTH, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(WEBVIEW_HEIGHT, View.MeasureSpec.EXACTLY))
                    view.layout(0, 0, WEBVIEW_WIDTH, WEBVIEW_HEIGHT)
                }

                with(view.settings) {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    blockNetworkImage = true 
                    userAgentString = headers["User-Agent"]
                }

                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(view, true)
                }

                view.addJavascriptInterface(payloadResult, interfaceName)

                view.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val requestUrl = request.url?.toString()?.toHttpUrlOrNull() ?: return super.shouldInterceptRequest(view, request)
                        val path = requestUrl.encodedPath.lowercase()
                        
                        // Block heavy assets from stalling the invisible webview
                        if (path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") ||
                            path.endsWith(".webp") || path.endsWith(".gif") || path.endsWith(".woff2") ||
                            path.endsWith(".woff") || path.endsWith(".ttf") || path.endsWith(".mp4") || path.endsWith(".css")
                        ) {
                            return emptyResponse
                        }

                        val allowedHost = requestUrl.host.endsWith(baseUrl.toHttpUrl().host) ||
                            requestUrl.host.endsWith(".comix.to") || requestUrl.host == "comix.to" ||
                            requestUrl.host == "comix.ws" || requestUrl.host == "challenges.cloudflare.com"
                            
                        if (!allowedHost) return emptyResponse
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        if (url != null) lastUrl = url
                        if (active.get() && payloadResult.payload == null) {
                            runCatching { view.evaluateJavascript(script, null) }
                        }
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        if (url != null) lastUrl = url
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
                            handler.postDelayed(this, SCRIPT_RETRY_INTERVAL_MS) 
                        }
                    }
                }
                injectScript = retry

                val html = document.clone().apply {
                    initializationScript?.let { head().prependElement("script").append(it) }
                }.outerHtml()

                view.loadDataWithBaseURL(document.location(), html, "text/html", "utf-8", null)
                handler.post(retry)
            } catch (error: Throwable) {
                startupError.set(error)
            } finally {
                started.release()
            }
        }

        val completed = try {
            if (!started.tryAcquire(WEBVIEW_START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) throw Exception("Timed out starting WebView (url=$lastUrl)")
            startupError.get()?.let { throw Exception("Failed to start WebView (url=$lastUrl)", it) }
            payloadResult.await(WEBVIEW_TIMEOUT_SECONDS, TimeUnit.SECONDS)
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

        if (!completed) throw Exception("Timed out waiting for WebView payload (url=$lastUrl)")
        return payloadResult.payload ?: throw Exception("Failed to capture WebView payload")
    }

    private class WebViewPayloadResult {
        private val signal = Semaphore(0)
        @Volatile var payload: String? = null
            private set

        @JavascriptInterface
        @Suppress("UNUSED")
        fun passPayload(data: String) {
            if (payload == null) {
                payload = data
                signal.release()
            }
        }

        @JavascriptInterface
        @Suppress("UNUSED")
        fun resetTimer() {
            signal.release()
        }

        fun await(timeout: Long, unit: TimeUnit): Boolean {
            while (payload == null) {
                if (!signal.tryAcquire(timeout, unit)) return false
            }
            return true
        }
    }

    // ============================= Settings =============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_POSTER_QUALITY
            title = "Thumbnail Quality"
            summary = "Change the quality of the thumbnail. Current: %s."
            entryValues = arrayOf("small", "medium", "large")
            entries = arrayOf("Small", "Medium", "Large")
            setDefaultValue("large")
        }.let(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_CONTENT_RATING
            title = "Content rating"
            summary = "Maximum content rating shown in popular, latest, and search results. The Content rating filter in search overrides this. Current: %s."
            entries = arrayOf("Show all", "Safe only", "Up to Suggestive", "Up to Erotica", "Up to Pornographic")
            entryValues = arrayOf("", "safe", "suggestive", "erotica", "pornographic")
            setDefaultValue(DEFAULT_CONTENT_RATING)
        }.let(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_DEFAULT_TYPES
            title = "Default types"
            summary = "Types to include in popular, latest, and search results. The Type filter in search overrides this."
            entries = Filters.getTypes().map { it.first }.toTypedArray()
            entryValues = Filters.getTypes().map { it.second }.toTypedArray()
            setDefaultValue(Filters.getTypes().map { it.second }.toSet())
        }.let(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_DEFAULT_DEMOGRAPHICS
            title = "Default demographics"
            summary = "Demographics to include in popular, latest, and search results. The Demographic filter in search overrides this."
            entries = Filters.getDemographics().map { it.first }.toTypedArray()
            entryValues = Filters.getDemographics().map { it.second }.toTypedArray()
            setDefaultValue(Filters.getDemographics().map { it.second }.toSet())
        }.let(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_BLOCKED_GENRES
            title = "Blocked genres"
            summary = "Genres always excluded from results. The search filter can still include a blocked genre as a one-off override."
            entries = Filters.getGenres().map { it.first }.toTypedArray()
            entryValues = Filters.getGenres().map { it.second }.toTypedArray()
            setDefaultValue(emptySet<String>())
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = DEDUPLICATE_CHAPTERS
            title = "Deduplicate Chapters"
            summary = "Remove duplicate chapters from the chapter list.\nOfficial chapters (Comix-marked) are preferred, followed by the highest-voted or most recent.\nWarning: It can be slow on large lists."
            setDefaultValue(false)
        }.let(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_SCANLATOR_BLACKLIST
            title = "Scanlator Blacklist"
            summary = "Filter out chapters from specific groups. Comma-separated list of group names or group IDs (e.g., 'Violet Scans, 307')."
            dialogTitle = "Exclude groups"
            setDefaultValue("")
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = ALTERNATIVE_NAMES_IN_DESCRIPTION
            title = "Show Alternative Names in Description"
            setDefaultValue(false)
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_EXTRA_INFO
            title = "Show extra info in description"
            summary = "Append publication year, language, content rating, rank, ratings count, and follower count to the manga description."
            setDefaultValue(true)
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_TAGS_IN_GENRES
            title = "Show tags in genre chips"
            summary = "Include the site's narrative tag list (e.g. Demons, Vampires, Time Travel) alongside the curated genres in the manga details. Off by default."
            setDefaultValue(false)
        }.let(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SCORE_POSITION
            title = "Score display position"
            summary = "%s"
            entries = arrayOf("Top of description", "Bottom of description", "Don't show")
            entryValues = arrayOf("top", "bottom", "none")
            setDefaultValue("top")
        }.let(screen::addPreference)
    }

    private fun SharedPreferences.posterQuality() = getString(PREF_POSTER_QUALITY, "large")
    private fun SharedPreferences.deduplicateChapters() = getBoolean(DEDUPLICATE_CHAPTERS, false)
    private fun SharedPreferences.scanlatorBlacklist(): Set<String> = getString(PREF_SCANLATOR_BLACKLIST, "")?.split(",")?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
    private fun SharedPreferences.alternativeNamesInDescription() = getBoolean(ALTERNATIVE_NAMES_IN_DESCRIPTION, false)
    private fun SharedPreferences.scorePosition() = getString(PREF_SCORE_POSITION, "top") ?: "top"
    private fun SharedPreferences.showExtraInfo() = getBoolean(PREF_SHOW_EXTRA_INFO, true)
    private fun SharedPreferences.showTagsInGenres() = getBoolean(PREF_SHOW_TAGS_IN_GENRES, false)
    private fun SharedPreferences.defaultTypes(): Set<String> { val all = Filters.getTypes().map { it.second }.toSet(); return getStringSet(PREF_DEFAULT_TYPES, all) ?: all }
    private fun SharedPreferences.defaultDemographics(): Set<String> { val all = Filters.getDemographics().map { it.second }.toSet(); return getStringSet(PREF_DEFAULT_DEMOGRAPHICS, all) ?: all }
    private fun SharedPreferences.blockedGenres(): Set<String> = getStringSet(PREF_BLOCKED_GENRES, emptySet()) ?: emptySet()
    private fun SharedPreferences.contentRating(): String {
        if (contains(PREF_CONTENT_RATING)) return getString(PREF_CONTENT_RATING, DEFAULT_CONTENT_RATING) ?: DEFAULT_CONTENT_RATING
        if (contains(LEGACY_HIDE_NSFW_PREF) && !getBoolean(LEGACY_HIDE_NSFW_PREF, true)) return ""
        return DEFAULT_CONTENT_RATING
    }

    companion object {
        private const val PREF_POSTER_QUALITY = "pref_poster_quality"
        private const val PREF_CONTENT_RATING = "pref_content_rating"
        private const val PREF_DEFAULT_TYPES = "pref_default_types"
        private const val PREF_DEFAULT_DEMOGRAPHICS = "pref_default_demographics"
        private const val PREF_BLOCKED_GENRES = "pref_blocked_genres"
        private const val LEGACY_HIDE_NSFW_PREF = "nsfw_pref"
        private const val DEDUPLICATE_CHAPTERS = "pref_deduplicate_chapters"
        private const val PREF_SCANLATOR_BLACKLIST = "pref_scanlator_blacklist"
        private const val ALTERNATIVE_NAMES_IN_DESCRIPTION = "pref_alt_names_in_description"
        private const val PREF_SHOW_EXTRA_INFO = "pref_show_extra_info"
        private const val PREF_SHOW_TAGS_IN_GENRES = "pref_show_tags_in_genres"
        private const val PREF_SCORE_POSITION = "pref_score_position"

        private const val DEFAULT_CONTENT_RATING = "suggestive"
        private const val WEBVIEW_START_TIMEOUT_SECONDS = 120L
        private const val WEBVIEW_TIMEOUT_SECONDS = 90L
        private const val SCRIPT_RETRY_INTERVAL_MS = 10L
        private const val WEBVIEW_WIDTH = 1080
        private const val WEBVIEW_HEIGHT = 1920
        private val SCRAMBLE_PATH_FALLBACK_REGEX = Regex("/(?:i5|s?i+)/")
    }
}

// ============================== Interceptor ==============================

object Descrambler {

    private const val GRID_COLS = 5
    private const val GRID_ROWS = 5
    private const val NUM_TILES = GRID_COLS * GRID_ROWS

    private const val ENC_MULTIPLIER = 1000005
    private const val ENC_INCREMENT = 1234567891
    private const val LCG_MULTIPLIER = 1664525
    private const val LCG_INCREMENT = 1013904223

    private val JPEG_MEDIA = "image/jpeg".toMediaType()

    val interceptor = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        if (!response.isSuccessful) return@Interceptor response

        val rawScrambleSeed = response.header("x-scramble-seed")
        val rawScrambleGrid = response.header("x-scramble-grid")
        val rawScrambleAlgo = response.header("x-scramble-algo")
        val rawScrambleHash = response.header("x-scramble-hash")
        val rawEncSeed = response.header("x-enc-seed")
        val rawEncAlgo = response.header("x-enc-algo")

        val encSeed = rawEncSeed?.toLongOrNull()?.toInt()
        val encLen = response.header("x-enc-len")?.toIntOrNull()
        val scrambleSeed = rawScrambleSeed?.toLongOrNull()?.toInt()
        val scrambleHash = decodeScrambleHash(rawScrambleHash)

        val needsXor = encSeed != null && encSeed != 0 && encLen != null
        val shouldDescrambleGrid = rawScrambleGrid == "5x5" &&
            (rawScrambleAlgo == null || rawScrambleAlgo == "1" || rawScrambleAlgo == "2" || rawScrambleAlgo == "3") &&
            scrambleSeed != null && scrambleSeed != 0

        if (!needsXor && !shouldDescrambleGrid) return@Interceptor response

        val body = response.body ?: return@Interceptor response
        val bodyMediaType = body.contentType()
        val originalBytes = body.bytes()
        
        // SUPERFAST OPTIMIZATION: Only decode the full array if XOR encryption exists
        val bytes = if (needsXor) decodeEncodedBytes(originalBytes, encSeed!!, encLen!!, rawEncAlgo) else originalBytes

        // SUPERFAST OPTIMIZATION: If we don't have to cut up a grid puzzle, stream bytes directly into the reader
        if (needsXor && !shouldDescrambleGrid && bytes.hasImageSignature()) {
            val mediaType = when {
                bytes.isJpeg() -> JPEG_MEDIA
                bytes.isPng() -> "image/png".toMediaType()
                bytes.isWebP() -> "image/webp".toMediaType()
                else -> bodyMediaType
            }
            return@Interceptor response.newBuilder()
                .removeHeader("Content-Encoding")
                .removeHeader("Content-Length")
                .header("Content-Type", mediaType.toString())
                .body(bytes.toResponseBody(mediaType))
                .build()
        }

        if (shouldDescrambleGrid) {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return@Interceptor response.newBuilder()
                    .code(500)
                    .message("Failed to decode image")
                    .body("Failed to decode image".toResponseBody("text/plain".toMediaType()))
                    .build()

            val descrambled = descramble(bitmap, scrambleSeed!! xor scrambleHash, rawScrambleAlgo)
            bitmap.recycle()

            val output = Buffer()
            descrambled.compress(Bitmap.CompressFormat.JPEG, 90, output.outputStream())
            descrambled.recycle()

            return@Interceptor response.newBuilder()
                .removeHeader("Content-Length")
                .removeHeader("Content-Type")
                .body(output.asResponseBody(JPEG_MEDIA, output.size))
                .build()
        }

        response.newBuilder()
            .removeHeader("Content-Encoding")
            .removeHeader("Content-Length")
            .removeHeader("Content-Type")
            .body(bytes.toResponseBody(bodyMediaType))
            .build()
    }

    // SUPERFAST OPTIMIZATION: Check headers only to find the key, then mutate array IN PLACE without allocations
    private fun decodeEncodedBytes(bytes: ByteArray, seed: Int, length: Int, algo: String?): ByteArray {
        if (algo != "2") return decodeWithLcg(bytes, seed, length)

        val headerLength = minOf(bytes.size, 16)
        val header = bytes.copyOfRange(0, headerLength)

        val variants = listOf(
            Triple(seed or 1, false, false), 
            Triple(seed, false, false),
            Triple(seed or 1, true, false),
            Triple(seed, false, true)
        )

        var correctVariant = variants.first()
        for (variant in variants) {
            val testHeader = header.copyOf()
            if (variant.third) {
                decodeWithLcg(testHeader, variant.first, headerLength)
            } else {
                decodeWithXorshift(testHeader, variant.first, headerLength, variant.second)
            }
            if (testHeader.hasImageSignature()) {
                correctVariant = variant
                break
            }
        }

        return if (correctVariant.third) {
            decodeWithLcg(bytes, correctVariant.first, length)
        } else {
            decodeWithXorshift(bytes, correctVariant.first, length, correctVariant.second)
        }
    }

    private fun decodeWithXorshift(bytes: ByteArray, initialState: Int, length: Int, highByte: Boolean): ByteArray {
        var state = initialState
        val limit = minOf(bytes.size, length)
        for (i in 0 until limit) {
            state = nextXorshiftState(state)
            val key = if (highByte) state ushr 24 else state and 0xFF
            bytes[i] = (bytes[i].toInt() xor key).toByte()
        }
        return bytes
    }

    private fun decodeWithLcg(bytes: ByteArray, seed: Int, length: Int): ByteArray {
        var state = seed
        val limit = minOf(bytes.size, length)
        for (i in 0 until limit) {
            state = state * LCG_MULTIPLIER + LCG_INCREMENT
            bytes[i] = (bytes[i].toInt() xor (state ushr 24)).toByte()
        }
        return bytes
    }

    private fun nextXorshiftState(state: Int): Int {
        var next = state
        next = next xor (next shl 13)
        next = next xor (next ushr 17)
        return next xor (next shl 5)
    }

    private fun decodeScrambleHash(hash: String?): Int = when (hash?.trim()) {
        "03632" -> 58414
        "02900" -> 117532
        else -> 0
    }

    private fun ByteArray.hasImageSignature() = isJpeg() || isPng() || isWebP()
    private fun ByteArray.isJpeg() = size >= 2 && this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte()
    private fun ByteArray.isPng() = size >= 8 && this[0] == 0x89.toByte() && this[1] == 'P'.code.toByte() && this[2] == 'N'.code.toByte() && this[3] == 'G'.code.toByte()
    private fun ByteArray.isWebP() = size >= 12 && this[0] == 'R'.code.toByte() && this[1] == 'I'.code.toByte() && this[2] == 'F'.code.toByte() && this[3] == 'F'.code.toByte() && this[8] == 'W'.code.toByte() && this[9] == 'E'.code.toByte() && this[10] == 'B'.code.toByte() && this[11] == 'P'.code.toByte()

    private fun descramble(bitmap: Bitmap, seed: Int, algo: String?): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val tileW = width / GRID_COLS
        val tileH = height / GRID_ROWS
        val order = if (algo == "3") buildOrder(seed, NUM_TILES) else buildOrderLcg(seed, NUM_TILES)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        for (dstIdx in 0 until NUM_TILES) {
            val srcIdx = order[dstIdx]
            val srcCol = srcIdx % GRID_COLS
            val srcRow = srcIdx / GRID_COLS
            val dstCol = dstIdx % GRID_COLS
            val dstRow = dstIdx / GRID_COLS
            val srcRect = Rect(srcCol * tileW, srcRow * tileH, (srcCol + 1) * tileW, (srcRow + 1) * tileH)
            val dstRect = Rect(dstCol * tileW, dstRow * tileH, (dstCol + 1) * tileW, (dstRow + 1) * tileH)
            canvas.drawBitmap(bitmap, srcRect, dstRect, null)
        }
        return output
    }

    private fun buildOrder(seed: Int, n: Int): IntArray {
        val arr = IntArray(n) { it }
        var state = seed or 1
        for (i in n - 1 downTo 1) {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            val j = (state.toLong() and 0xFFFFFFFFL) % (i + 1)
            val tmp = arr[i]
            arr[i] = arr[j.toInt()]
            arr[j.toInt()] = tmp
        }
        return IntArray(n).also { inverse ->
            for (i in arr.indices) {
                inverse[arr[i]] = i
            }
        }
    }

    private fun buildOrderLcg(seed: Int, n: Int): IntArray {
        val arr = IntArray(n) { it }
        var state = seed
        for (i in n - 1 downTo 1) {
            state = state * LCG_MULTIPLIER + LCG_INCREMENT
            val j = (state.toLong() and 0xFFFFFFFFL) % (i + 1)
            val tmp = arr[i]
            arr[i] = arr[j.toInt()]
            arr[j.toInt()] = tmp
        }
        return IntArray(n).also { inverse ->
            for (i in arr.indices) {
                inverse[arr[i]] = i
            }
        }
    }
}

// ============================== Filters ==============================

class Filters {
    interface UriFilter {
        fun addToUri(builder: HttpUrl.Builder)
    }

    companion object {
        private val currentYear by lazy { Calendar.getInstance()[Calendar.YEAR] }
        private const val OLDEST_YEAR = 1928
        private fun getYearsArray(forFromFilter: Boolean): Array<Pair<String, String>> {
            val newest = currentYear + 1
            val years = (newest downTo OLDEST_YEAR).map { it.toString() to it.toString() }
            val any = "Any" to ""
            return if (forFromFilter) (years + any).toTypedArray() else (listOf(any) + years).toTypedArray()
        }

        fun getGenres() = arrayOf("Action" to "6", "Adult" to "87264", "Adventure" to "7", "Boys Love" to "8", "Comedy" to "9", "Crime" to "10", "Drama" to "11", "Ecchi" to "87265", "Fantasy" to "12", "Girls Love" to "13", "Harem" to "40", "Hentai" to "87266", "Historical" to "14", "Horror" to "15", "Isekai" to "16", "Magical Girls" to "17", "Mature" to "87267", "Mecha" to "18", "Medical" to "19", "Mystery" to "20", "Philosophical" to "21", "Psychological" to "22", "Romance" to "23", "Sci-Fi" to "24", "Slice of Life" to "25", "Smut" to "87268", "Sports" to "26", "Superhero" to "27", "Thriller" to "28", "Tragedy" to "29", "Wuxia" to "30")
        fun getFormats() = arrayOf("4-Koma" to "93164", "Adaptation" to "93167", "Anthology" to "93165", "Award Winning" to "93166", "Doujinshi" to "93168", "Full Color" to "93172", "Long Strip" to "93170", "Oneshot" to "93169", "Web Comic" to "93171")
        fun getDemographics() = arrayOf("Josei" to "3", "Seinen" to "4", "Shoujo" to "1", "Shounen" to "2")
        fun getTypes() = arrayOf("Manga" to "manga", "Manhwa" to "manhwa", "Manhua" to "manhua", "Other" to "other")
        fun getContentRatingsUpTo(maxRating: String): List<String> {
            if (maxRating.isEmpty()) return emptyList()
            val ratings = listOf("safe", "suggestive", "erotica", "pornographic")
            val index = ratings.indexOf(maxRating)
            return if (index == -1) emptyList() else ratings.take(index + 1)
        }
    }

    fun getFilterList() = FilterList(SortFilter(getSortables()), ContentRatingFilter(), TypeFilter(), Filter.Separator(), Filter.Header("Tags — comma separated"), TagsFilter(), Filter.Header("Match: AND requires every selection, OR matches any"), MatchModeFilter(), GenreFilter(getGenres()), FormatFilter(getFormats()), Filter.Separator(), DemographicFilter(getDemographics()), StatusFilter(), MinChapterFilter(), Filter.Separator(), Filter.Header("Release Year"), YearFromFilter(), YearToFilter(), Filter.Separator(), Filter.Header("Author / Artist — comma separated"), AuthorFilter(), ArtistFilter())

    private open class UriPartFilter(name: String, private val param: String, private val vals: Array<Pair<String, String>>, defaultValue: String? = null) : Filter.Select<String>(name, vals.map { it.first }.toTypedArray(), vals.indexOfFirst { it.second == defaultValue }.takeIf { it != -1 } ?: 0), UriFilter { override fun addToUri(builder: HttpUrl.Builder) { builder.addQueryParameter(param, vals[state].second) } }
    private open class UriMultiSelectOption(name: String, val value: String) : Filter.CheckBox(name)
    private open class UriMultiSelectFilter(name: String, private val param: String, vals: Array<Pair<String, String>>) : Filter.Group<UriMultiSelectOption>(name, vals.map { UriMultiSelectOption(it.first, it.second) }), UriFilter { override fun addToUri(builder: HttpUrl.Builder) { state.filter { it.state }.forEach { builder.addQueryParameter(param, it.value) } } }
    private open class UriTriSelectOption(name: String, val value: String) : Filter.TriState(name)
    private open class UriTriSelectFilter(name: String, private val param: String, vals: Array<Pair<String, String>>) : Filter.Group<UriTriSelectOption>(name, vals.map { UriTriSelectOption(it.first, it.second) }), UriFilter { override fun addToUri(builder: HttpUrl.Builder) { state.forEach { s -> when (s.state) { TriState.STATE_INCLUDE -> builder.addQueryParameter(param, s.value); TriState.STATE_EXCLUDE -> builder.addQueryParameter(param, "-${s.value}") } } } }

    private class DemographicFilter(demographics: Array<Pair<String, String>>) : UriMultiSelectFilter("Demographic", "demographics[]", demographics)
    private class TypeFilter : UriMultiSelectFilter("Type", "types[]", getTypes())
    private abstract class TermGroupFilter(title: String, options: Array<Pair<String, String>>) : Filter.Group<UriTriSelectOption>(title, options.map { UriTriSelectOption(it.first, it.second) }), UriFilter { override fun addToUri(builder: HttpUrl.Builder) { state.filter { it.state == TriState.STATE_INCLUDE }.forEach { builder.addQueryParameter("genres_in[]", it.value) }; state.filter { it.state == TriState.STATE_EXCLUDE }.forEach { builder.addQueryParameter("genres_ex[]", it.value) } } }
    private class GenreFilter(genres: Array<Pair<String, String>>) : TermGroupFilter("Genres", genres)
    private class FormatFilter(formats: Array<Pair<String, String>>) : TermGroupFilter("Formats", formats)
    class TagsFilter : Filter.Text("Tags")
    private class StatusFilter : UriMultiSelectFilter("Status", "statuses[]", arrayOf("Releasing" to "releasing", "Finished" to "finished", "On Hiatus" to "on_hiatus", "Discontinued" to "discontinued", "Not Yet Released" to "not_yet_released"))
    private class YearFromFilter : UriPartFilter("From", "year_from", getYearsArray(forFromFilter = true), "")
    private class YearToFilter : UriPartFilter("To", "year_to", getYearsArray(forFromFilter = false), "") { override fun addToUri(builder: HttpUrl.Builder) { if (state > 0) super.addToUri(builder) } }
    class AuthorFilter : Filter.Text("Author")
    class ArtistFilter : Filter.Text("Artist")
    private class MatchModeFilter : UriPartFilter("Match", "genres_mode", arrayOf("All (AND)" to "and", "Any (OR)" to "or"))
    private class ContentRatingFilter : UriPartFilter("Content rating", "content_rating", arrayOf("Use preference" to "", "Safe only" to "safe", "Up to Suggestive" to "suggestive", "Up to Erotica" to "erotica", "Up to Pornographic" to "pornographic")) { override fun addToUri(builder: HttpUrl.Builder) { if (state != 0) { val selected = when (state) { 1 -> "safe"; 2 -> "suggestive"; 3 -> "erotica"; 4 -> "pornographic"; else -> "" }; Filters.getContentRatingsUpTo(selected).takeIf { it.isNotEmpty() }?.let { builder.addQueryParameter("content_rating", it.joinToString(",")) } } } }
    private class MinChapterFilter : Filter.Text("Minimum Chapter Length"), UriFilter { override fun addToUri(builder: HttpUrl.Builder) { if (state.isNotEmpty()) { val value = state.toIntOrNull()?.takeIf { it > 0 } ?: throw IllegalArgumentException("Minimum chapter length must be a positive integer greater than 0"); builder.addQueryParameter("min_chap", value.toString()) } } }
    private class Sortable(val title: String, val value: String) { override fun toString(): String = title }
    private fun getSortables() = arrayOf(Sortable("Best Match", "relevance"), Sortable("Latest update", "chapter_updated_at"), Sortable("Recently added", "created_at"), Sortable("Title", "title"), Sortable("Year", "year"), Sortable("Highest rated", "score"), Sortable("Most viewed · 7 days", "views_7d"), Sortable("Most viewed · 30 days", "views_30d"), Sortable("Most viewed · 90 days", "views_90d"), Sortable("Most viewed · all time", "views_total"), Sortable("Most followed", "follows_total"))
    private class SortFilter(private val sortables: Array<Sortable>) : Filter.Sort("Sort By", sortables.map(Sortable::title).toTypedArray(), Selection(1, false)), UriFilter { override fun addToUri(builder: HttpUrl.Builder) { if (state != null) { val query = sortables[state!!.index].value; val value = if (state!!.ascending) "asc" else "desc"; builder.addQueryParameter("order[$query]", value) } } }
}

// ============================== Data Transfer Objects ==============================

@Serializable class Term(val title: String)
@Serializable class TagSearchResponse(val result: List<TagSearchHit> = emptyList())
@Serializable class TagSearchHit(val id: Int)

@Serializable class Manga(
    val hid: String, private val title: String, private val altTitles: List<String> = emptyList(), @SerialName("alt_titles") private val altTitlesOld: List<String> = emptyList(),
    private val synopsis: String? = null, private val type: String = "", private val poster: Poster? = null, private val status: String = "", private val contentRating: String = "safe",
    private val authors: List<Term>? = null, @SerialName("author") private val authorOld: List<Term>? = null, private val artists: List<Term>? = null, @SerialName("artist") private val artistOld: List<Term>? = null,
    private val genres: List<Term>? = null, @SerialName("genre") private val genreOld: List<Term>? = null, private val tags: List<Term>? = null, @SerialName("theme") private val themeOld: List<Term>? = null,
    private val demographics: List<Term>? = null, @SerialName("demographic") private val demographicOld: List<Term>? = null, private val formats: List<Term>? = null,
    private val ratedAvg: Double = 0.0, private val ratedCount: Long = 0L, private val followsTotal: Long = 0L, private val rank: Int = 0, private val year: Int? = null,
    private val originalLanguage: String? = null, private val url: String? = null
) {
    @Serializable class Poster(private val small: String? = null, private val medium: String? = null, private val large: String? = null) { fun from(quality: String?) = when (quality) { "large" -> large ?: medium ?: small ?: ""; "small" -> small ?: medium ?: large ?: ""; else -> medium ?: large ?: small ?: "" } }
    private val fancyScore: String get() { if (ratedAvg == 0.0) return ""; val score = ratedAvg.toBigDecimal(); val stars = score.div(BigDecimal(2)).setScale(0, RoundingMode.HALF_UP).toInt(); val scoreString = if (score.scale() == 0) score.toPlainString() else score.stripTrailingZeros().toPlainString(); return buildString { append("★".repeat(stars)); if (stars < 5) append("☆".repeat(5 - stars)); append(" $scoreString") } }
    fun toSManga(posterQuality: String?, altTitlesInDesc: Boolean = false, scorePosition: String, showExtraInfo: Boolean = true, showTags: Boolean = false) = SManga.create().apply {
        url = this@Manga.url?.substringAfter("/title") ?: "/$hid"
        title = this@Manga.title
        val actualAuthors = authors ?: authorOld
        val actualArtists = artists ?: artistOld
        author = actualAuthors?.joinToString { it.title }
        artist = actualArtists?.joinToString { it.title }
        description = buildString {
            if (scorePosition == "top") fancyScore.takeIf { it.isNotEmpty() }?.let { append(it); append("\n\n") }
            synopsis?.takeUnless { it.isEmpty() }?.let { append(it) }
            val actualAltTitles = altTitles.ifEmpty { altTitlesOld }
            if (altTitlesInDesc && actualAltTitles.isNotEmpty()) { append("\n\nAlternative Names:\n"); append(actualAltTitles.joinToString("\n")) }
            if (showExtraInfo) { val extras = buildExtraInfo(); if (extras.isNotEmpty()) { if (isNotEmpty()) append("\n\n"); append(extras.joinToString("\n")) } }
            if (scorePosition == "bottom") fancyScore.takeIf { it.isNotEmpty() }?.let { if (isNotEmpty()) append("\n\n"); append(it) }
        }
        initialized = true
        status = when (this@Manga.status) { "releasing" -> SManga.ONGOING; "on_hiatus" -> SManga.ON_HIATUS; "finished" -> SManga.COMPLETED; "discontinued" -> SManga.CANCELLED; else -> SManga.UNKNOWN }
        thumbnail_url = this@Manga.poster?.from(posterQuality)
        genre = getGenres(showTags)
    }
    fun toBasicSManga(posterQuality: String?) = SManga.create().apply { url = this@Manga.url?.substringAfter("/title") ?: "/$hid"; title = this@Manga.title; thumbnail_url = this@Manga.poster?.from(posterQuality) }
    private fun buildExtraInfo(): List<String> = buildList { year?.takeIf { it > 0 }?.let { add("Year: $it") }; originalLanguage?.takeIf { it.isNotBlank() }?.let { add("Language: ${it.uppercase()}") }; contentRating.takeIf { it.isNotBlank() }?.let { add("Content rating: ${it.replaceFirstChar(Char::uppercase)}") }; rank.takeIf { it > 0 }?.let { add("Rank: #$it") }; ratedCount.takeIf { it > 0 }?.let { add("Rated by: $it") }; followsTotal.takeIf { it > 0 }?.let { add("Followed by: $it") } }
    private fun getGenres(showTags: Boolean) = buildList { when (type) { "manhwa" -> add("Manhwa"); "manhua" -> add("Manhua"); "manga" -> add("Manga"); else -> add("Other") }; (genres ?: genreOld)?.map { it.title }?.let { addAll(it) }; (demographics ?: demographicOld)?.map { it.title }?.let { addAll(it) }; if (showTags) tags?.map { it.title }?.let { addAll(it) }; if (contentRating == "erotica" || contentRating == "pornographic") add("NSFW") }.distinct().joinToString()
}

@Serializable class SingleMangaResponse(val result: Manga)
@Serializable class Meta(val page: Int = 1, private val lastPage: Int = 1, @SerialName("last_page") private val lastPageOld: Int = 1, val hasNext: Boolean = false) { val actualLastPage: Int get() = maxOf(lastPage, lastPageOld) }
@Serializable class Pagination(val page: Int = 1, private val lastPage: Int = 1, @SerialName("last_page") private val lastPageOld: Int = 1) { val actualLastPage: Int get() = maxOf(lastPage, lastPageOld) }
@Serializable class SearchResponse(val result: Items) { @Serializable class Items(val items: List<Manga> = emptyList(), private val meta: Meta? = null, private val pagination: Pagination? = null) { fun hasNextPage(): Boolean = when { meta != null -> meta.page < meta.actualLastPage; pagination != null -> pagination.page < pagination.actualLastPage; else -> false } } }
@Serializable class ChapterDetailsResponse(val result: Items) { @Serializable class Items(val items: List<Chapter> = emptyList(), private val meta: Meta? = null, private val pagination: Pagination? = null) { fun hasNextPage(): Boolean = when { meta != null -> meta.page < meta.actualLastPage; pagination != null -> pagination.page < pagination.actualLastPage; else -> false } } }

@Serializable class Chapter(
    val id: Int, val url: String = "", val number: Double, private val name: String = "", val votes: Int = 0, private val createdAtFormatted: String = "", val group: ScanlationGroup? = null, val isOfficial: Boolean = false
) {
    @Serializable class ScanlationGroup(val id: Int? = null, val name: String)
    companion object { private val DATE_REGEX = Regex("""^(\d+)\s*(s|m|h|d|w|mo|mos|y|yr|yrs|min|mins|sec|secs|hr|hrs|day|days|week|weeks|month|months|year|years)$""") }
    fun toSChapter(mangaSlug: String) = SChapter.create().apply {
        url = this@Chapter.url.indexOf("/title/").let { index -> if (index != -1) this@Chapter.url.substring(index + 1) else "title/$mangaSlug/$id-chapter-${number.toString().removeSuffix(".0")}" }
        name = buildString { append("Chapter "); append(this@Chapter.number.toString().removeSuffix(".0")); this@Chapter.name.takeUnless { it.isEmpty() }?.let { append(": $it") } }
        date_upload = parseRelativeDate(this@Chapter.createdAtFormatted)
        chapter_number = this@Chapter.number.toFloat()
        scanlator = if (this@Chapter.group != null) this@Chapter.group.name else if (this@Chapter.isOfficial) "Official" else "Unknown"
    }
    private fun parseRelativeDate(dateStr: String): Long {
        if (dateStr.isEmpty()) return 0L
        val trimmed = dateStr.trim().lowercase().removeSuffix(" ago")
        val match = DATE_REGEX.find(trimmed) ?: return 0L
        val amount = match.groupValues[1].toIntOrNull() ?: return 0L
        val unit = match.groupValues[2]
        val calendar = Calendar.getInstance()
        when (unit) { "s", "sec", "secs" -> calendar.add(Calendar.SECOND, -amount); "m", "min", "mins" -> calendar.add(Calendar.MINUTE, -amount); "h", "hr", "hrs" -> calendar.add(Calendar.HOUR_OF_DAY, -amount); "d", "day", "days" -> calendar.add(Calendar.DAY_OF_YEAR, -amount); "w", "week", "weeks" -> calendar.add(Calendar.WEEK_OF_YEAR, -amount); "mo", "mos", "month", "months" -> calendar.add(Calendar.MONTH, -amount); "y", "yr", "yrs", "year", "years" -> calendar.add(Calendar.YEAR, -amount) }
        return calendar.timeInMillis
    }
}

@Serializable class ChapterResponse(val result: ChapterResult) {
    @Serializable class ChapterResult(val pages: Pages)
    @Serializable class Pages(val baseUrl: String, val items: List<PageDto>)
    @Serializable class PageDto(val url: String, val s: Int = 0)
}
