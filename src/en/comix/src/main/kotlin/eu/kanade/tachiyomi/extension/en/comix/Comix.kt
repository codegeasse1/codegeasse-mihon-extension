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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import org.jsoup.nodes.Element
import rx.Observable
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Calendar
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
        .addInterceptor { chain ->
            if (chain.request().url.host == baseUrlHost) sitePacer.waitForSlot() else imagePacer.waitForSlot()
            chain.proceed(chain.request())
        }
        .addInterceptor(Descrambler.interceptor)
        .addInterceptor(::altHostFallback)
        .addInterceptor { chain ->
            val request = chain.request()
            if (request.url.fragment?.startsWith(ALT_HOSTS_FRAGMENT) == true || request.url.host == baseUrlHost) {
                return@addInterceptor chain.proceed(request)
            }

            var response = proceedWithRetry(chain, request)
            if (response.isSuccessful) return@addInterceptor response

            val url = request.url.toString()
            val fallbacks = listOf("/hi/", "/fcf/", "/i5/", "/si/", "/i/", "/sii/", "/ii/")
                .map { url.replaceFirst(SCRAMBLE_PATH_FALLBACK_REGEX, it) }
                .filter { it != url }

            if (fallbacks.isEmpty()) return@addInterceptor response

            for (fallbackUrl in fallbacks) {
                response.close()
                response = proceedWithRetry(chain, request.newBuilder().url(fallbackUrl).build())
                if (response.isSuccessful) break
            }
            response
        }
        .build()

    private val baseUrlHost get() = baseUrl.toHttpUrl().host

    // The site's X-Rt flag, raised after a few API calls without user input
    // and blocked on by the server; held at zero inside the WebView.
    private val X_RT_OVERRIDE_SCRIPT = """
        (function () {
            try {
                var origSetRequestHeader = XMLHttpRequest.prototype.setRequestHeader;
                XMLHttpRequest.prototype.setRequestHeader = function (name, value) {
                    if (String(name).toLowerCase() === "x-rt" && value === "1") value = "0";
                    return origSetRequestHeader.call(this, name, value);
                };
            } catch (e) {}
        })();
    """.trimIndent()

    // Minimum spacing between requests; Comix blocks clients that fetch too fast
    private class RequestPacer(private val intervalMs: Long) {
        private var nextNanos = 0L

        fun waitForSlot() {
            val waitNanos = synchronized(this) {
                val now = System.nanoTime()
                val start = maxOf(now, nextNanos)
                nextNanos = start + intervalMs * 1_000_000
                start - now
            }
            if (waitNanos > 0) Thread.sleep(waitNanos / 1_000_000)
        }
    }

    // Retry failed images on alt hosts, like the site's reader
    private fun altHostFallback(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val altHosts = request.url.altHosts()
        if (altHosts.isEmpty()) return chain.proceed(request)

        val urls = listOf(request.url) + altHosts.mapIndexed { i, host ->
            request.url.newBuilder()
                .host(host)
                .setQueryParameter("r", (i + 1).toString())
                .build()
        }
        var failed: Response? = null
        var error: IOException? = null
        for (url in urls) {
            if (failed != null) {
                failed.close()
                failed = null
                runCatching { Thread.sleep(2000) }
            }
            try {
                val response = chain.proceed(request.newBuilder().url(url).build())
                if (response.isSuccessful) return response
                failed = response
            } catch (e: IOException) {
                if (chain.call().isCanceled()) throw e
                error = e
            }
        }
        return failed ?: throw error!!
    }

    private fun HttpUrl.altHosts(): List<String> = fragment
        ?.takeIf { it.startsWith(ALT_HOSTS_FRAGMENT) }
        ?.removePrefix(ALT_HOSTS_FRAGMENT)
        ?.split(',')
        .orEmpty()

    private fun proceedWithRetry(chain: Interceptor.Chain, request: Request): Response {
        var response = chain.proceed(request)
        if (response.isSuccessful) return response

        for (attempt in 1..10) {
            if (response.code !in SERVER_ERROR_CODES && response.code != 404) break

            response.close()
            runCatching { Thread.sleep(1500) }

            val retryUrl = request.url.newBuilder()
                .setQueryParameter("r", attempt.toString())
                .build()
            response = chain.proceed(request.newBuilder().url(retryUrl).build())
            if (response.isSuccessful) break
        }

        return response
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)
        .add("Accept", "*/*")

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    // Comix image domains block any image requests with Referer/Origin, so both
    // headers are stripped for every page image (fragment is kept: it carries
    // the alt-host list, which never leaves the device anyway).
    override fun imageRequest(page: Page): Request {
        val imageUrl = page.imageUrl ?: return super.imageRequest(page)
        val requestHeaders = headersBuilder()
            .removeAll("Origin")
            .removeAll("Referer")
            .build()
        return GET(imageUrl, requestHeaders)
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

        // v11: prefer the site’s own rendered chapter rows (?page=N pager,
        // plain HTTP, no token signing, immune to JS-bundle renames). Uses
        // the WebView API walk only when the rows are absent (SPA markup) or
        // a pager page fails. The DOM path only returns on a clean pager end.
        val allChapters: List<Chapter> = fetchChaptersViaDom(mangaSlug)
            ?: fetchChaptersViaWebView(document, mangaSlug)


        val filteredChapters = if (blacklist.isNotEmpty()) {
            allChapters.filter { ch ->
                val scanlatorName = ch.groupName ?: if (ch.isOfficial) "Official" else "Unknown"
                val nameNormalized = scanlatorName.trim().lowercase()
                val idStr = ch.groupId?.toString()
                nameNormalized !in blacklist && idStr !in blacklist
            }
        } else {
            allChapters
        }

        val finalChapters: List<Chapter> = if (deduplicate) {
            val chapterMap = LinkedHashMap<String, Chapter>()
            deduplicateChapters(chapterMap, filteredChapters)
            chapterMap.values.toList()
        } else {
            filteredChapters
        }

        finalChapters.map { it.toSChapter(mangaSlug) }
    }

    // v11 fast path: read the site’s own rendered chapter rows
    // (.mchap-item) through the ?page=N pager with plain HTTP. Returns null
    // when the rows are absent or any page fails, letting the caller fall
    // back to the WebView walk. Only a clean pager end yields a list.
    private fun fetchChaptersViaDom(mangaSlug: String): List<Chapter>? {
        val seenIds = LinkedHashSet<Int>()
        val out = ArrayList<Chapter>()
        var page = 1
        while (page <= MAX_CHAPTER_PAGES) {
            val url = if (page == 1) "$baseUrl/title/$mangaSlug" else "$baseUrl/title/$mangaSlug?page=$page"
            val doc = try {
                client.newCall(GET(url, headers)).execute().asJsoup()
            } catch (e: Exception) {
                return null
            }
            val rows = doc.select(".mchap-item")
            if (rows.isEmpty()) {
                if (page == 1) return null
                // Later empty page: clean end only when this still looks
                // like a title page; a challenge/error page means fallback.
                return if (looksLikeTitlePage(doc)) out else null
            }
            var added = 0
            for (row in rows) {
                val parsed = parseDomChapterRow(row) ?: continue
                if (seenIds.add(parsed.id)) { out.add(parsed); added++ }
            }
            if (added == 0) {
                if (page == 1) return null
                return if (looksLikeTitlePage(doc)) out else null
            }
            page++
        }
        return out
    }

    private fun looksLikeTitlePage(doc: Document): Boolean =
        doc.selectFirst("script#initial-data") != null || doc.select(".mchap-item").isNotEmpty()

    // v11: normalize one rendered chapter row (.mchap-item) into a Chapter.
    // Href shape: /title/{slug}/{chapterId}-chapter-{number}.
    private fun parseDomChapterRow(row: Element): Chapter? {
        val href = row.selectFirst(".mchap-row__primary")?.attr("href")
            ?: row.selectFirst("a")?.attr("href")
            ?: return null
        val m = Regex("""/(\d+)-chapter-(.+)$""").find(href) ?: return null
        val id = m.groupValues[1].toIntOrNull() ?: return null
        val numStr = m.groupValues[2]
        val num = numStr.toDoubleOrNull()
            ?: Regex("""(\d+(?:\.\d+)?)""").find(numStr)?.value?.toDoubleOrNull()
        val title = row.selectFirst(".mchap-row__title")?.text()?.trim().orEmpty()
        val groupEl = row.selectFirst(".mchap-row__group")
        var groupName = groupEl?.selectFirst("span")?.text()?.trim()
            ?: groupEl?.text()?.trim()?.takeIf { it.isNotEmpty() }
        if (groupName.isNullOrEmpty() && groupEl?.hasClass("is-official") == true) groupName = "Official"
        return Chapter(
            id = id,
            url = href,
            number = num,
            name = title,
            votes = 0,
            createdAt = "",
            groupId = null,
            groupName = groupName,
            isOfficial = groupName == "Official",
        )
    }

    private fun fetchChaptersViaWebView(document: Document, mangaSlug: String): List<Chapter> {
        // Chapters come from the site's own token-signed chapters API, which
        // plain HTTP cannot call. The site's own client code is reused from
        // inside the WebView: its chapters API method is located in the
        // site's own JS chunks and paged newest-uploads-first until the last
        // page (same approach as the official extension).
        val mangaId = mangaSlug.substringBefore('-')
        val mangaIdJson = JSONObject.quote(mangaId)
        val chapterPath = JSONObject.quote("/title/$mangaId-")
        val payload = runInWebView(
            document = document,
            initializationScript = X_RT_OVERRIDE_SCRIPT,
            isDataRequest = { it.isChapterListRequest() },
            buildScript = { interfaceName ->
                """
                (function () {
                    if (window.__comixChaptersInstalled) return null;
                    window.__comixChaptersInstalled = true;
                    var pass = function (text) { window.$interfaceName.passPayload(text); };
                    var fail = function (text) { window.$interfaceName.failPayload(String(text)); };
                    var drive = (function (knownIds, mangaId, chapterPath, maxPages, passFn, failFn) {
                        var known = {};
                        knownIds.forEach(function (id) { known[id] = true; });
                        var chapters = [];
                        var isChapter = function (item) {
                            return item &&
                                typeof item.id === "number" &&
                                typeof item.number === "number" &&
                                typeof item.url === "string" &&
                                item.url.indexOf(chapterPath) !== -1;
                        };
                        var isRoute = function (route) {
                            return typeof route === "function" &&
                                /^\(?[\w$]+\)?=>`\/manga\/\$\{[\w$]+\}\/chapters`$/.test(String(route));
                        };
                        var PAGE_TIMEOUT_MS = 25000;
                        var page = 1;
                        var siteLoaded = false;
                        var expecting = false;
                        var returnedNumbers = null;
                        var done = false;
                        var chaptersApi = null;
                        var finish = function (complete, error) {
                            done = true;
                            passFn(JSON.stringify({ chapters: chapters, complete: complete, error: error }));
                        };
                        var matchChaptersApi = function (exports) {
                            var found = null;
                            exports.forEach(function (value) {
                                var routesList = [];
                                if (value && typeof value === "object") {
                                    Object.values(value).forEach(function (v) { routesList.push(v); });
                                }
                                routesList.forEach(function (routes) {
                                    if (found || !routes || typeof routes !== "object") return;
                                    var routeKeys = Object.keys(routes);
                                    var key = null;
                                    routeKeys.forEach(function (name) {
                                        if (key === null && isRoute(routes[name])) key = name;
                                    });
                                    if (!key) return;
                                    exports.forEach(function (api) {
                                        if (found || !api || typeof api !== "object" || api === routes) return;
                                        if (typeof api[key] !== "function") return;
                                        var allKeys = routeKeys.every(function (name) { return name in api; });
                                        if (!allKeys || String(api[key]).indexOf("params") === -1) return;
                                        found = function (params) { return api[key](mangaId, params); };
                                    });
                                });
                            });
                            return found;
                        };
                        var findChaptersApi = function () {
                            var scripts = [];
                            try {
                                performance.getEntriesByType("resource").forEach(function (entry) {
                                    if (/\/assets\/.*\.js$/.test(entry.name)) scripts.push(entry.name);
                                });
                            } catch (e) { return Promise.resolve(null); }
                            var entry = null;
                            scripts.forEach(function (url) {
                                if (entry === null && /\/main-[^/]+\.js$/.test(url)) entry = url;
                            });
                            if (!entry) return Promise.resolve(null);
                            var directory = entry.slice(0, entry.lastIndexOf("/") + 1);
                            var seen = {};
                            var urls = scripts.filter(function (url) {
                                if (url.indexOf(directory) !== 0 || seen[url]) return false;
                                seen[url] = true;
                                return true;
                            });
                            var chain = Promise.resolve(null);
                            urls.forEach(function (url) {
                                chain = chain.then(function (api) {
                                    if (api) return api;
                                    return import(url).then(function (mod) {
                                        var exps = Object.values(mod);
                                        exps.forEach(function (e) { allExports.push(e); });
                                        return matchChaptersApi(allExports);
                                    }, function () { return null; });
                                });
                            });
                            return chain;
                        };
                        // v11: second discovery path — the site’s chapters client
                        // also ships as an env-*.js module imported by page module
                        // scripts, exposing a `chapters` function by name. This
                        // survives JS-bundle renames that break route matching.
                        var findChaptersApiViaEnv = function () {
                            var modules = [];
                            try {
                                var tags = document.querySelectorAll('script[type="module"][src]');
                                for (var mi = 0; mi < tags.length; mi++) modules.push(tags[mi].src);
                            } catch (e) { return Promise.resolve(null); }
                            if (modules.length === 0) return Promise.resolve(null);
                            var seen = {};
                            var chain = Promise.resolve(null);
                            modules.forEach(function (scriptUrl) {
                                chain = chain.then(function (api) {
                                    if (api) return api;
                                    return fetch(scriptUrl, { credentials: "same-origin" }).then(function (r) {
                                        if (!r.ok) return null;
                                        return r.text();
                                    }).then(function (source) {
                                        if (!source) return null;
                                        var re = /from\s*["']\.\/(env-[^"']+\.js)["']/g;
                                        var inner = Promise.resolve(null);
                                        var mm = null;
                                        while ((mm = re.exec(source)) !== null) {
                                            (function (rel) {
                                                if (seen[rel]) return;
                                                seen[rel] = true;
                                                inner = inner.then(function (found) {
                                                    if (found) return found;
                                                    var envUrl = new URL(rel, scriptUrl).href;
                                                    return import(envUrl).then(function (mod) {
                                                        var vals = Object.values(mod);
                                                        for (var k = 0; k < vals.length; k++) {
                                                            var cand = vals[k];
                                                            if (cand && typeof cand === "object" && typeof cand.chapters === "function") {
                                                                return function (params) { return cand.chapters(mangaId, params); };
                                                            }
                                                        }
                                                        return null;
                                                    }, function () { return null; });
                                                });
                                            })(mm[1]);
                                        }
                                        return inner;
                                    }, function () { return null; });
                                });
                            });
                            return chain;
                        };
                        var allExports = [];
                        var requestPage = function (target) {
                            var error = "Chapter page " + target + " never arrived";
                            var onError = function () {
                                setTimeout(function () {
                                    if (!done && page === target) finish(false, error);
                                }, 1000);
                            };
                            var gotApi = function (api) {
                                chaptersApi = api;
                                if (!chaptersApi) {
                                    finish(false, "Could not find the site's chapters API");
                                    return;
                                }
                                expecting = true;
                                var winner = false;
                                var timer = setTimeout(function () {
                                    if (!winner) {
                                        winner = true;
                                        error = "Chapter page " + target + " timed out";
                                        onError();
                                    }
                                }, PAGE_TIMEOUT_MS);
                                chaptersApi({ page: target, limit: 100, order: { created_at: "desc" } }).then(function (result) {
                                    if (winner) return;
                                    winner = true;
                                    clearTimeout(timer);
                                    var arrays = [];
                                    if (Array.isArray(result)) {
                                        arrays.push(result);
                                    } else if (result && typeof result === "object") {
                                        Object.values(result).forEach(function (v) {
                                            if (Array.isArray(v)) arrays.push(v);
                                        });
                                    }
                                    returnedNumbers = arrays.map(function (returned) {
                                        var values = {};
                                        returned.forEach(function (item) {
                                            if (item && typeof item === "object") {
                                                Object.values(item).forEach(function (value) {
                                                    if (typeof value === "number") values[value] = true;
                                                });
                                            }
                                        });
                                        return { count: returned.length, values: values };
                                    });
                                    error = "Chapter page " + target + " came back in a shape the extension does not recognise";
                                }, function (e) {
                                    if (winner) return;
                                    winner = true;
                                    clearTimeout(timer);
                                    var status = e && e.response && e.response.status;
                                    error = status === 502
                                        ? "Comix blocked chapter page " + target + " (HTTP 502). Try again later"
                                        : "Chapter page " + target + " failed: " + (status ? "HTTP " + status : (e && e.message) || e);
                                    onError();
                                });
                            };
                            if (chaptersApi) {
                                gotApi(chaptersApi);
                            } else {
                                findChaptersApi().then(function (api) {
                                    if (api) { gotApi(api); return; }
                                    findChaptersApiViaEnv().then(gotApi, function () { gotApi(null); });
                                }, function () {
                                    findChaptersApiViaEnv().then(gotApi, function () { gotApi(null); });
                                });
                            }
                        };
                        return function (holder) {
                            var items = holder.items;
                            var meta = holder.meta;
                            if (done || !meta || !Array.isArray(items) || !items.every(isChapter)) return;
                            if (items.length === 0 && meta.total !== 0) return;
                            if (typeof meta.page !== "number" || typeof meta.lastPage !== "number") {
                                if (chapters.length > 0) {
                                    finish(false, "Chapter list has no page numbers");
                                } else {
                                    done = true;
                                    failFn("Chapter list has no page numbers");
                                }
                                return;
                            }
                            if (!siteLoaded) {
                                siteLoaded = true;
                                requestPage(page);
                                return;
                            }
                            if (!expecting || meta.page !== page) return;
                            var matched = false;
                            if (returnedNumbers) {
                                returnedNumbers.forEach(function (returned) {
                                    if (matched || returned.count !== items.length) return;
                                    var all = true;
                                    items.forEach(function (item) {
                                        if (!returned.values[item.id]) all = false;
                                    });
                                    if (all) matched = true;
                                });
                            }
                            if (!matched) return;
                            expecting = false;
                            returnedNumbers = null;
                            chapters.push.apply(chapters, items);
                            var hasKnown = false;
                            items.forEach(function (item) { if (known[item.id]) hasKnown = true; });
                            if (hasKnown) {
                                finish(true);
                                return;
                            }
                            if (!meta.hasNext || page >= Math.min(meta.lastPage, maxPages)) {
                                var seen = {};
                                chapters.forEach(function (item) { seen[item.id] = true; });
                                var count = Object.keys(seen).length;
                                if (count === meta.total) {
                                    finish(true);
                                } else {
                                    finish(false, "Got " + count + " of " + meta.total + " chapters");
                                }
                                return;
                            }
                            page++;
                            requestPage(page);
                        };
                    })([], $mangaIdJson, $chapterPath, $MAX_CHAPTER_PAGES, pass, fail);
                        var capture = function (holder) {
                            setTimeout(function () { try { drive(holder); } catch (e) {} }, 0);
                        };
                        var defineProperty = Object.defineProperty;
                        defineProperty(Object.prototype, "items", {
                            configurable: true,
                            set: function (value) {
                                defineProperty(this, "items", {
                                    value: value,
                                    writable: true,
                                    enumerable: true,
                                    configurable: true
                                });
                                capture(this);
                            }
                        });
                        Object.defineProperty = function (target, key, descriptor) {
                            var result = defineProperty.apply(this, arguments);
                            if (key === "items" && descriptor && "value" in descriptor) capture(target);
                            return result;
                        };
                        return null;
                    })();
                    """.trimIndent()
                },
            )
        val listRoot = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
            ?: throw Exception("Could not read chapter list from page")
        val listComplete = (listRoot["complete"] as? JsonPrimitive)?.content == "true"
        val listError = (listRoot["error"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
        val allChapters = Chapter.parsePayload(listRoot["chapters"].toString())
        // v11: never surface a bogus single-chapter list. A walk that ends
        // incomplete with ~1 chapter hit a wrong holder or a block; failing
        // loudly keeps Mihon’s cached list and tells the user to retry.
        if (allChapters.isEmpty()) throw Exception(listError ?: "Chapter list is incomplete")
        if (!listComplete && allChapters.size <= 1) throw Exception((listError ?: "Only 1 chapter could be read") + " — pull to refresh to retry")
        return allChapters
    }

    override fun chapterListRequest(manga: SManga): Request = throw UnsupportedOperationException()
    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException()

    private fun deduplicateChapters(chapterMap: LinkedHashMap<String, Chapter>, items: List<Chapter>) {
        for (ch in items) {
            // Numbered chapters dedupe by number; unnumbered specials dedupe by id
            // so two different specials never collapse into one entry.
            val key = ch.number?.toString() ?: "id:${ch.id}"
            val current = chapterMap[key]
            if (current == null) {
                chapterMap[key] = ch
            } else {
                val better = when {
                    ch.isOfficial && !current.isOfficial -> true
                    !ch.isOfficial && current.isOfficial -> false
                    ch.groupId == 10702 && current.groupId != 10702 -> true
                    ch.groupId != 10702 && current.groupId == 10702 -> false
                    else -> if (ch.votes != current.votes) ch.votes > current.votes else ch.id > current.id
                }
                if (better) chapterMap[key] = ch
            }
        }
    }

    // =============================== Pages ===============================
    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable.fromCallable {
        val request = GET(getChapterUrl(chapter), headers)
        val document = client.newCall(request).execute().asJsoup()

        // SUPERFAST OPTIMIZATION: Read directly from page JSON without launching the browser
        val initialData = document.selectFirst("script#initial-data")?.data()
        var pagesPayload: ChapterPages? = null

        if (initialData != null) {
            runCatching {
                val root = Json.parseToJsonElement(initialData) as? JsonObject ?: return@runCatching
                val queries = root["queries"] as? JsonObject ?: return@runCatching
                queries.values.forEach { value ->
                    parsePagesHolder(value.toString())?.let { pagesPayload = it }
                }
            }
        }

        val pages = pagesPayload ?: run {
            // The chapter page loads its page list through the site's own
            // /api/v1/chapters API (token-signed in page JS). Hook the decoded
            // API responses exactly like the official extension: the first
            // holder with page-like items (no meta) wins.
            val payload = runInWebView(
                document = document,
                initializationScript = X_RT_OVERRIDE_SCRIPT,
                isDataRequest = { it.isChapterPagesRequest() },
                buildScript = { interfaceName ->
                    """
                    (function () {
                        if (window.__comixPagesInstalled) return null;
                        window.__comixPagesInstalled = true;
                        var pass = function (text) { window.$interfaceName.passPayload(text); };
                        var handle = function (holder) {
                            try {
                                var items = holder && holder.items;
                                if (!holder || holder.meta || !Array.isArray(items) || items.length === 0) return;
                                var ok = items.every(function (item) { return item && typeof item.url === "string"; });
                                if (ok) pass(JSON.stringify(holder));
                            } catch (e) {}
                        };
                        var capture = function (holder) {
                            setTimeout(function () { try { handle(holder); } catch (e) {} }, 0);
                        };
                        var defineProperty = Object.defineProperty;
                        defineProperty(Object.prototype, "items", {
                            configurable: true,
                            set: function (value) {
                                defineProperty(this, "items", {
                                    value: value,
                                    writable: true,
                                    enumerable: true,
                                    configurable: true
                                });
                                capture(this);
                            }
                        });
                        Object.defineProperty = function (target, key, descriptor) {
                            var result = defineProperty.apply(this, arguments);
                            if (key === "items" && descriptor && "value" in descriptor) capture(target);
                            return result;
                        };
                        try {
                            var rawEl = document.querySelector('script#initial-data');
                            var raw = rawEl && rawEl.textContent;
                            var queries = raw && JSON.parse(raw).queries;
                            if (queries) Object.values(queries).forEach(function (q) {
                                try { handle(q); } catch (e) {}
                                try { if (q && q.result) handle(q.result); } catch (e) {}
                                try { if (q && q.result && q.result.pages) handle(q.result.pages); } catch (e) {}
                            });
                        } catch (e) {}
                        return null;
                    })();
                    """.trimIndent()
                },
            )
            parsePagesHolder(payload)
                ?: throw Exception("Could not find page list in chapter data")
        }

        val base = pages.resolvedBase().trimEnd('/')

        pages.items.mapIndexedNotNull { index, img ->
            val raw = img.resolvedUrl()
            if (raw.isBlank()) return@mapIndexedNotNull null
            val full = (if (raw.startsWith("http")) raw else if (base.isNotBlank()) "$base/${raw.trimStart('/')}" else return@mapIndexedNotNull null).toHttpUrl()
            // V3 pages need the query flag so the server returns grid-scramble headers.
            val isV3 = img.s == 1 || "v3" in full.queryParameterNames
            val url = full.newBuilder().apply {
                if (isV3 && "v3" !in full.queryParameterNames) addQueryParameter("v3", null)
                val altHosts = pages.altHosts(full.host, index)
                if (altHosts.isNotEmpty()) fragment(ALT_HOSTS_FRAGMENT + altHosts.joinToString(","))
            }.build().toString()
            Page(index, imageUrl = url)
        }
    }

    /** Accepts a bare pages holder or a `{result:{pages}}` chapter query envelope. Never throws. */
    private fun parsePagesHolder(payload: String): ChapterPages? = runCatching {
        val el = Json.parseToJsonElement(payload)
        val result = (el as? JsonObject)?.get("result") as? JsonObject
        val candidates = listOfNotNull(
            el as? JsonObject,
            result,
            result?.get("pages") as? JsonObject,
        )
        candidates.firstNotNullOfOrNull { obj ->
            val items = obj["items"] as? JsonArray ?: return@firstNotNullOfOrNull null
            if (items.isEmpty()) return@firstNotNullOfOrNull null
            if (obj["meta"] is JsonObject) return@firstNotNullOfOrNull null
            runCatching { json.decodeFromString<ChapterPages>(obj.toString()) }.getOrNull()
                ?.takeIf { pages -> pages.items.any { it.resolvedUrl().isNotBlank() } }
        }
    }.getOrNull()

    override fun pageListRequest(chapter: SChapter): Request = throw UnsupportedOperationException()
    override fun pageListParse(response: Response): List<Page> = throw UnsupportedOperationException()

    @SuppressLint("SetJavaScriptEnabled")
    @Synchronized
    private fun runInWebView(
        document: Document,
        initializationScript: String? = null,
        isDataRequest: ((HttpUrl) -> Boolean)? = null,
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
        val timeoutAt = AtomicLong(System.nanoTime() + WEBVIEW_TIMEOUT_SECONDS * 1_000_000_000L)
        val dataRequests = AtomicInteger(0)

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

                        // v11: skip site beacons the extension never needs
                        if (requestUrl.encodedPath.startsWith("/api/v1/client-errors") ||
                            requestUrl.encodedPath.startsWith("/api/v1/threads/") ||
                            requestUrl.encodedPath.startsWith("/assets/uploads/")
                        ) {
                            return emptyResponse
                        }

                        // v11: answer the site human check inside the invisible
                        // WebView (same approach as the official extension):
                        // auto-solve rotate/click captchas when enabled,
                        // otherwise fail loudly so the user can solve it once
                        // in a visible WebView (cookies are shared).
                        if (requestUrl.encodedPath == WAF_CHALLENGE_PATH) {
                            val accept = try {
                                request.requestHeaders?.get("Accept").orEmpty()
                            } catch (e: Exception) {
                                ""
                            }
                            if (accept.contains("text/html")) {
                                timeoutAt.set(System.nanoTime() + DATA_REQUEST_WAIT_SECONDS * 1_000_000_000L)
                                if (!preferences.solveCaptchas()) {
                                    payloadResult.failNow("Human check required: open the manga in WebView once to solve it, or turn on Solve captchas in the extension settings")
                                    return emptyResponse
                                }
                                val solver = wafSolverScript()
                                if (solver.isBlank()) return emptyResponse
                                val solverPage = "<script>(" + solver + ")(function(m){parent." + interfaceName + ".failPayload(String(m));})</script>"
                                return WebResourceResponse("text/html", "utf-8", solverPage.byteInputStream())
                            }
                        }

                        val allowedHost = requestUrl.host.endsWith(baseUrl.toHttpUrl().host) ||
                            requestUrl.host.endsWith(".comix.to") || requestUrl.host == "comix.to" ||
                            requestUrl.host == "comix.ws" || requestUrl.host == "challenges.cloudflare.com"
                            
                        if (!allowedHost) return emptyResponse
                        // Comix blocks clients that page chapter lists too fast
                        if (requestUrl.isChapterListRequest()) chapterListPacer.waitForSlot()
                        if (isDataRequest?.invoke(requestUrl) == true) {
                            dataRequests.incrementAndGet()
                            timeoutAt.set(System.nanoTime() + DATA_REQUEST_WAIT_SECONDS * 1_000_000_000L)
                        }
                        // v11: solver activity (/generate, /verify) also keeps the run alive
                        if (requestUrl.encodedPath.startsWith(WAF_PATH)) {
                            timeoutAt.set(System.nanoTime() + DATA_REQUEST_WAIT_SECONDS * 1_000_000_000L)
                        }
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
            while (payloadResult.payload == null && payloadResult.error == null) {
                if (System.nanoTime() >= timeoutAt.get()) break
                payloadResult.awaitSlice(1000)
            }
            payloadResult.payload != null || payloadResult.error != null
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
            val message = when {
                isDataRequest == null -> "Timed out waiting for WebView payload (url=$lastUrl)"
                dataRequests.get() == 0 -> "Site never requested the data (url=$lastUrl)"
                else -> "Nothing read from ${dataRequests.get()} site requests (url=$lastUrl)"
            }
            throw Exception(message)
        }
        payloadResult.error?.let { throw Exception(it) }
        return payloadResult.payload ?: throw Exception("Failed to capture WebView payload")
    }

    private class WebViewPayloadResult {
        private val signal = Semaphore(0)
        @Volatile var payload: String? = null
            private set
        @Volatile var error: String? = null
            private set

        @JavascriptInterface
        @Suppress("UNUSED")
        fun passPayload(data: String) {
            if (payload == null) {
                payload = data
                signal.release()
            }
        }

        fun failNow(message: String) {
            if (payload == null && error == null) {
                error = message.ifBlank { "Site rejected the request" }
            }
            signal.release()
        }

        @JavascriptInterface
        @Suppress("UNUSED")
        fun failPayload(data: String) {
            if (payload == null && error == null) {
                error = data.ifBlank { "Site rejected the request" }
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

        fun awaitSlice(millis: Long) {
            runCatching { signal.tryAcquire(millis, TimeUnit.MILLISECONDS) }
        }
    }

    private fun HttpUrl.isChapterPagesRequest(): Boolean = pathSegments.size == 4 &&
        pathSegments[0] == "api" &&
        pathSegments[1] == "v1" &&
        pathSegments[2] == "chapters"

    private fun HttpUrl.isChapterListRequest(): Boolean = pathSegments.size == 5 &&
        pathSegments[0] == "api" &&
        pathSegments[1] == "v1" &&
        pathSegments[2] == "manga" &&
        pathSegments[4] == "chapters"

    // ============================= Settings =============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SOLVE_CAPTCHA
            title = "Solve captchas"
            summary = "Solve the site Verify-you-are-human check automatically inside chapter and page loads."
            setDefaultValue(true)
        }.let(screen::addPreference)

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
    @Volatile private var cachedWafSolver: String? = null

    // v11: the rotate/click captcha solver (ported from the official
    // extension solve_captcha.js asset). Served in place of the "@waf"
    // challenge frame so human checks can pass headlessly.
    private fun wafSolverScript(): String {
        cachedWafSolver?.let { return it }
        val text = runCatching {
            applicationContext.assets.open("solve_captcha.js").bufferedReader().use { it.readText() }
        }.getOrNull().orEmpty()
        cachedWafSolver = text
        return text
    }

    private fun SharedPreferences.solveCaptchas() = getBoolean(PREF_SOLVE_CAPTCHA, true)

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
        private const val DATA_REQUEST_WAIT_SECONDS = 30L
        private const val SCRIPT_RETRY_INTERVAL_MS = 100L
        private const val MAX_CHAPTER_PAGES = 1000
        private val chapterListPacer = RequestPacer(intervalMs = 2500)
        private val sitePacer = RequestPacer(intervalMs = 500)
        private val imagePacer = RequestPacer(intervalMs = 200)
        private const val PREF_SOLVE_CAPTCHA = "pref_solve_captcha"
        private const val WAF_CHALLENGE_PATH = "/@waf/challenge"
        private const val WAF_PATH = "/@waf/"
        private const val ALT_HOSTS_FRAGMENT = "alt="
        private val SERVER_ERROR_CODES = setOf(502, 503, 522, 523)
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

    fun getFilterList() = FilterList(SortFilter(getSortables()), ContentRatingFilter(), TypeFilter(), Filter.Separator(), Filter.Header("Tags â comma separated"), TagsFilter(), Filter.Header("Match: AND requires every selection, OR matches any"), MatchModeFilter(), GenreFilter(getGenres()), FormatFilter(getFormats()), Filter.Separator(), DemographicFilter(getDemographics()), StatusFilter(), MinChapterFilter(), Filter.Separator(), Filter.Header("Release Year"), YearFromFilter(), YearToFilter(), Filter.Separator(), Filter.Header("Author / Artist â comma separated"), AuthorFilter(), ArtistFilter())

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
    private fun getSortables() = arrayOf(Sortable("Best Match", "relevance"), Sortable("Latest update", "chapter_updated_at"), Sortable("Recently added", "created_at"), Sortable("Title", "title"), Sortable("Year", "year"), Sortable("Highest rated", "score"), Sortable("Most viewed Â· 7 days", "views_7d"), Sortable("Most viewed Â· 30 days", "views_30d"), Sortable("Most viewed Â· 90 days", "views_90d"), Sortable("Most viewed Â· all time", "views_total"), Sortable("Most followed", "follows_total"))
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

/**
 * A comix.to chapter row. The site has been observed returning chapter items
 * whose `number` is null, a non-numeric string, or missing entirely (oneshots,
 * side stories, season-break placeholders) â and the title page also fires
 * manga-list queries whose items carry `id` but no chapter number at all.
 * The old strict `@Serializable` model (required `number: Double`) turned any
 * single such row into a `MissingFieldException` that wiped the ENTIRE chapter
 * list. This model parses every row leniently instead: rows that are not
 * chapter-like are skipped, rows without a parseable number are kept with
 * `number = null` (display falls back to the row title).
 */
class Chapter(
    val id: Int,
    val url: String,
    val number: Double?,
    private val name: String,
    val votes: Int,
    private val createdAt: String,
    val groupId: Int?,
    val groupName: String?,
    val isOfficial: Boolean,
) {
    companion object {
        private val DATE_REGEX = Regex("""^(\d+)\s*(s|m|h|d|w|mo|mos|y|yr|yrs|min|mins|sec|secs|hr|hrs|day|days|week|weeks|month|months|year|years)$""")
        private val NUM_REGEX = Regex("""(\d+(?:\.\d+)?)""")
        private val CHAPTER_URL_REGEX = Regex("""-chapter-(\d+(?:\.\d+)?)""")

        private fun JsonElement?.asDoubleOrNull(): Double? =
            (this as? JsonPrimitive)?.content?.toDoubleOrNull()

        private fun JsonElement?.asIntOrNull(): Int? {
            val content = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content ?: return null
            return content.toIntOrNull() ?: content.toDoubleOrNull()?.toInt()
        }

        private fun JsonElement?.asStringOrNull(): String? =
            (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

        private fun JsonElement?.asBoolOrFalse(): Boolean =
            (this as? JsonPrimitive)?.content?.equals("true", ignoreCase = true) == true ||
                (this as? JsonPrimitive)?.content == "1"

        private fun JsonObject.isChapterLike(): Boolean =
            (this["number"] ?: this["chap"] ?: this["chapter"]) != null ||
                CHAPTER_URL_REGEX.containsMatchIn(this["url"].asStringOrNull().orEmpty()) ||
                (this["url"].asStringOrNull().isNullOrBlank() &&
                    NUM_REGEX.containsMatchIn(this["name"].asStringOrNull().orEmpty()))

        fun fromJsonObject(o: JsonObject): Chapter? {
            if (!o.isChapterLike()) return null
            val id = o["id"].asIntOrNull()
                ?: o["chapter_id"].asIntOrNull()
                ?: o["chapterId"].asIntOrNull()
                ?: return null
            val url = o["url"].asStringOrNull().orEmpty()
            val rawName = o["name"].asStringOrNull().orEmpty()
            val number = o["number"].asDoubleOrNull()
                ?: o["chap"].asDoubleOrNull()
                ?: o["chapter"].asDoubleOrNull()
                ?: rawName.takeIf { it.isNotBlank() }?.let { NUM_REGEX.find(it)?.value?.toDoubleOrNull() }
                ?: CHAPTER_URL_REGEX.find(url)?.groupValues?.get(1)?.toDoubleOrNull()
            val groupEl = o["group"] ?: o["scanlation_group"] ?: o["scanlationGroup"]
            val flatGroupName = o["group_name"].asStringOrNull() ?: o["groupName"].asStringOrNull()
            val (groupId, groupName) = when (groupEl) {
                is JsonObject -> groupEl["id"].asIntOrNull() to
                    (groupEl["name"].asStringOrNull() ?: groupEl["title"].asStringOrNull() ?: flatGroupName)
                is JsonPrimitive -> null to (groupEl.asStringOrNull() ?: flatGroupName)
                else -> null to flatGroupName
            }
            return Chapter(
                id = id,
                url = url,
                number = number,
                name = rawName,
                votes = o["votes"].asIntOrNull()
                    ?: o["vote_count"].asIntOrNull()
                    ?: o["voteCount"].asIntOrNull() ?: 0,
                createdAt = o["createdAtFormatted"].asStringOrNull()
                    ?: o["createdAt"].asStringOrNull()
                    ?: o["created_at"].asStringOrNull().orEmpty(),
                groupId = groupId,
                groupName = groupName,
                isOfficial = o["isOfficial"].asBoolOrFalse() || o["is_official"].asBoolOrFalse(),
            )
        }

        private fun parseItemsArray(arr: JsonArray): List<Chapter> =
            arr.mapNotNull { (it as? JsonObject)?.let(::fromJsonObject) }

        /** Accepts a bare `[...]` array or an `{result:{items:[...]}}` envelope. Never throws. */
        fun parsePayload(payload: String): List<Chapter> = runCatching {
            when (val el = Json.parseToJsonElement(payload)) {
                is JsonArray -> parseItemsArray(el)
                is JsonObject -> {
                    val result = el["result"]
                    val items = (result as? JsonObject)?.get("items") ?: el["items"]
                    (items as? JsonArray)?.let(::parseItemsArray) ?: emptyList()
                }
                else -> emptyList()
            }
        }.getOrDefault(emptyList())

            }
    fun toSChapter(mangaSlug: String) = SChapter.create().apply {
        val num = this@Chapter.number
        url = this@Chapter.url.indexOf("/title/").let { index ->
            if (index != -1) this@Chapter.url.substring(index + 1)
            else "title/$mangaSlug/$id-chapter-${num?.toString()?.removeSuffix(".0") ?: "0"}"
        }
        name = if (num != null) {
            buildString {
                append("Chapter ")
                append(num.toString().removeSuffix(".0"))
                this@Chapter.name.takeUnless { it.isEmpty() }?.let { append(": $it") }
            }
        } else {
            this@Chapter.name.ifEmpty { "Chapter $id" }
        }
        date_upload = parseRelativeDate(this@Chapter.createdAt)
        chapter_number = num?.toFloat() ?: 0f
        scanlator = this@Chapter.groupName ?: if (this@Chapter.isOfficial) "Official" else "Unknown"
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

@Serializable class ChapterPages(
    val baseUrl: String = "",
    @SerialName("base_url") val baseUrlAlt: String = "",
    val items: List<PageDto> = emptyList(),
    private val alt: List<String>? = null,
    private val node: String? = null,
) {
    fun resolvedBase(): String = baseUrl.ifBlank { baseUrlAlt }

    // Alt hosts in the site reader's retry order
    fun altHosts(host: String, index: Int): List<String> {
        if (alt.isNullOrEmpty()) return emptyList()
        val label = host.substringBefore('.')
        val start = index % alt.size
        val domains = alt.drop(start) + alt.take(start)
        val hosts = domains.map { "$label.$it" } + domains.map { "${node ?: label}.$it" }
        return List(6) { hosts[it % hosts.size] }
    }

    @Serializable class PageDto(
        val url: String = "",
        val src: String = "",
        val path: String = "",
        val link: String = "",
        val s: Int = 0,
    ) {
        fun resolvedUrl(): String = url.ifBlank { src }.ifBlank { path }.ifBlank { link }
    }
}
