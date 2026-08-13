package eu.kanade.tachiyomi.extension.en.theblank

import android.app.Application
import android.util.Base64
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat

import codegeasse.crypto.SecretStream
import codegeasse.crypto.SecretStream.State
import codegeasse.crypto.X25519

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
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
import kotlinx.serialization.json.JsonNames

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody

import okio.Buffer
import okio.Timeout
import okio.buffer

import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec


class TheBlank : HttpSource(), ConfigurableSource {

    override val name = "The Blank"
    override val baseUrl = "https://theblank.net"
    override val lang = "en"
    override val supportsLatest = true

    private val baseHttpUrl = baseUrl.toHttpUrl()
    private val prefPremiumTitle = "Hide Premium chapters"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /*
     * IMPORTANT:
     *
     * The old code had:
     *
     *     .rateLimit(1, 2, TimeUnit.SECONDS)
     *
     * on this shared client.
     *
     * That meant thumbnails AND chapter images were limited
     * to roughly one request every two seconds.
     *
     * This has intentionally been removed so that Tachiyomi/
     * Mihon can load images concurrently.
     */
    override val client = network.client.newBuilder()
        .addInterceptor(::imageInterceptor)
        .build()

    // STRICT KEIYOUSHI HEADERS - NO USER AGENT OVERRIDES!
    override fun headersBuilder() = super.headersBuilder()
        .set("Origin", "https://${baseHttpUrl.host}")
        .set("Referer", "$baseUrl/")

    private var version: String? = null
    private var csrfToken: String? = null


    // ============================== Network Auth ==============================

    @Synchronized
    private fun apiRequest(
        url: HttpUrl,
        body: RequestBody? = null,
        includeXSRFToken: Boolean,
        includeCSRFToken: Boolean,
        includeVersion: Boolean,
    ): Request {

        var xsrfToken = client.cookieJar.loadForRequest(baseHttpUrl)
            .firstOrNull { it.name == "XSRF-TOKEN" }
            ?.value

        if (
            (includeXSRFToken && xsrfToken == null) ||
            (includeCSRFToken && csrfToken == null) ||
            (includeVersion && version == null)
        ) {

            val document = client.newCall(
                GET(baseHttpUrl, headers)
            ).execute().also {
                if (!it.isSuccessful) {
                    it.close()
                    throw Exception("HTTP Error ${it.code}")
                }
            }.asJsoup()

            version = json
                .decodeFromString<Version>(
                    document
                        .selectFirst("#app")!!
                        .attr("data-page")
                )
                .version

            csrfToken = document
                .selectFirst("meta[name=csrf-token]")!!
                .attr("content")

            xsrfToken = client.cookieJar
                .loadForRequest(baseHttpUrl)
                .first { it.name == "XSRF-TOKEN" }
                .value
        }

        val apiHeaders = headersBuilder().apply {

            set("Accept", "application/json")
            set("X-Requested-With", "XMLHttpRequest")

            if (includeVersion) {
                set("X-Inertia", "true")
                set("X-Inertia-Version", version!!)
            }

            if (includeXSRFToken) {
                set("X-XSRF-TOKEN", xsrfToken!!)
            }

            if (includeCSRFToken) {
                set("X-CSRF-TOKEN", csrfToken!!)
            }

        }.build()

        return if (body != null) {
            POST(url.toString(), apiHeaders, body)
        } else {
            GET(url, apiHeaders)
        }
    }


    // ============================== Search & Browse ==============================

    private val popularFilters =
        FilterList(
            SortFilter(
                "Sort",
                sortValues,
                Filter.Sort.Selection(3, false)
            )
        )

    private val latestFilters =
        FilterList(
            SortFilter(
                "Sort",
                sortValues,
                Filter.Sort.Selection(2, false)
            )
        )

    override fun popularMangaRequest(page: Int) =
        searchMangaRequest(page, "", popularFilters)

    override fun popularMangaParse(response: Response) =
        searchMangaParse(response)

    override fun latestUpdatesRequest(page: Int) =
        searchMangaRequest(page, "", latestFilters)

    override fun latestUpdatesParse(response: Response) =
        searchMangaParse(response)

    override fun getFilterList() =
        FilterList(
            Filter.Header("Text search ignores filters!"),
            Filter.Separator(),
            SortFilter("Sort", sortValues),
            GenreFilter(),
            TypeFilter(),
            StatusFilter(),
        )

    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request {

        if (query.isNotEmpty()) {

            val url = baseHttpUrl.newBuilder()
                .addPathSegments("api/v1/search/series")
                .addQueryParameter("q", query)
                .build()

            return apiRequest(
                url = url,
                includeXSRFToken = true,
                includeCSRFToken = false,
                includeVersion = false,
            )
        }

        val url = baseHttpUrl.newBuilder().apply {

            addPathSegment("library")

            if (page > 1) {
                addQueryParameter("page", page.toString())
            }

            filters
                .filterIsInstance<TriStateGroupFilter>()
                .forEach { group ->

                    when (group.name) {

                        "Genres",
                        "Genres/Thèmes" -> {

                            group.included
                                .takeIf { it.isNotEmpty() }
                                ?.also {
                                    addQueryParameter(
                                        "include_genres",
                                        it.joinToString(",")
                                    )
                                }

                            group.excluded
                                .takeIf { it.isNotEmpty() }
                                ?.also {
                                    addQueryParameter(
                                        "exclude_genres",
                                        it.joinToString(",")
                                    )
                                }
                        }

                        "Types" -> {

                            group.included
                                .takeIf { it.isNotEmpty() }
                                ?.also {
                                    addQueryParameter(
                                        "include_types",
                                        it.joinToString(",")
                                    )
                                }

                            group.excluded
                                .takeIf { it.isNotEmpty() }
                                ?.also {
                                    addQueryParameter(
                                        "exclude_types",
                                        it.joinToString(",")
                                    )
                                }
                        }
                    }
                }

            filters
                .filterIsInstance<CheckBoxGroup>()
                .firstOrNull()
                ?.also { status ->

                    if (status.checked.isNotEmpty()) {
                        addQueryParameter(
                            "status",
                            status.checked.joinToString(",")
                        )
                    }
                }

            filters
                .filterIsInstance<SortFilter>()
                .firstOrNull()
                ?.also { sort ->

                    addQueryParameter(
                        "orderby",
                        sort.sort
                    )

                    if (sort.ascending) {
                        addQueryParameter(
                            "order",
                            "asc"
                        )
                    }
                }

        }.build()

        return apiRequest(
            url = url,
            includeXSRFToken = true,
            includeCSRFToken = false,
            includeVersion = false,
        )
    }

    override fun searchMangaParse(response: Response): MangasPage {

        val bodyStr = response.body!!.string()

        if (response.request.url.queryParameter("q") != null) {

            val data =
                json.decodeFromString<List<BrowseManga>>(bodyStr)

            return MangasPage(
                mangas = data.map {
                    it.toSManga(::createThumbnailUrl)
                },
                hasNextPage = false,
            )

        } else {

            val data =
                json.decodeFromString<LibraryResponse>(bodyStr)
                    .series

            return MangasPage(
                mangas = data.data.map {
                    it.toSManga(::createThumbnailUrl)
                },
                hasNextPage = data.meta.current < data.meta.last,
            )
        }
    }


    // ============================== Manga Details ==============================

    override fun mangaDetailsRequest(manga: SManga): Request {

        val url =
            "$baseUrl/serie/${manga.url}".toHttpUrl()

        return apiRequest(
            url = url,
            includeXSRFToken = true,
            includeCSRFToken = false,
            includeVersion = true,
        )
    }

    override fun getMangaUrl(manga: SManga): String =
        "$baseUrl/serie/${manga.url}"

    override fun mangaDetailsParse(response: Response): SManga {

        val data =
            json.decodeFromString<MangaResponse>(
                response.body!!.string()
            ).props.serie

        return SManga.create().apply {

            url = data.slug

            title = data.title

            thumbnail_url =
                createThumbnailUrl(data.image)

            author = data.author

            artist = data.artist

            description = buildString {

                data.description?.also {
                    append(it.trim(), "\n\n")
                }

                data.releaseYear?.also {
                    append("Sortie: ", it, "\n\n")
                }

                data.alternativeName?.also {
                    append("Noms alternatifs: ", it)
                }

            }.trim()

            genre = buildList {

                data.type?.name?.also(::add)

                data.genres.mapTo(this) {
                    it.name
                }

            }.joinToString()

            status = when (
                data.status?.lowercase()
            ) {

                "ongoing",
                "upcoming" ->
                    SManga.ONGOING

                "finished" ->
                    SManga.COMPLETED

                "dropped" ->
                    SManga.CANCELLED

                "onhold" ->
                    SManga.ON_HIATUS

                else ->
                    SManga.UNKNOWN
            }
        }
    }


    /*
     * Thumbnail URLs deliberately use the URL fragment.
     *
     * Fragment is not sent to the server, but imageInterceptor()
     * can use it to identify thumbnail requests.
     */
    private fun createThumbnailUrl(
        imagePath: String?,
    ): String? {

        if (imagePath == null) {
            return null
        }

        return "$baseUrl$imagePath#$THUMBNAIL_FRAGMENT"
    }


    // ============================== Chapters ==============================

    override fun chapterListRequest(
        manga: SManga,
    ) = mangaDetailsRequest(manga)

    override fun chapterListParse(
        response: Response,
    ): List<SChapter> {

        val data =
            json.decodeFromString<MangaResponse>(
                response.body!!.string()
            ).props.serie

        val hidePremium =
            try {

                Injekt.get<Application>()
                    .getSharedPreferences(
                        "source_$id",
                        0x0000
                    )
                    .getBoolean(
                        HIDE_PREMIUM_PREF,
                        false
                    )

            } catch (e: Exception) {

                false
            }

        return data.chapters
            .filter {
                !(it.isPremium && hidePremium)
            }
            .map {

                SChapter.create().apply {

                    url =
                        "/serie/${data.slug}/chapter/${it.slug}"

                    name = buildString {

                        if (it.isPremium) {
                            append("\uD83D\uDD12 ")
                        }

                        append(it.title)
                    }

                    date_upload =
                        it.createdAt
                            .substringBefore(".")
                            .let { dateStr ->

                                dateFormat
                                    .parse(dateStr)
                                    ?.time
                                    ?: 0L
                            }
                }
            }
            .asReversed()
    }

    private val dateFormat =
        SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss",
            Locale.ROOT
        ).apply {

            timeZone =
                TimeZone.getTimeZone("UTC")
        }


    // ============================== Pages & Encryption ==============================

    override fun pageListRequest(
        chapter: SChapter,
    ): Request {

        return apiRequest(
            url = "$baseUrl${chapter.url}".toHttpUrl(),
            includeXSRFToken = true,
            includeCSRFToken = false,
            includeVersion = true,
        )
    }

    private val secureRandom =
        SecureRandom()

    private class ChapterSession(
        val chapterToken: String,
        val sharedSecret: ByteArray,
        val clientPubkeyB64: String,
    )

    private val sessions =
        ConcurrentHashMap<String, ChapterSession>()

    private val sessionLocks =
        ConcurrentHashMap<String, Any>()

    private fun sessionKey(
        serieSlug: String,
        chapterSlug: String,
    ): String =
        "${name.take(3).lowercase()}-$serieSlug--$chapterSlug"


    private fun handshakeFrom(
        props: PageListResponse.Props,
    ): ChapterSession {

        val serverPub =
            Base64.decode(
                props.serverPubkey,
                Base64.DEFAULT
            )

        require(
            serverPub.size == 32
        ) {
            "server pubkey must be 32 bytes"
        }

        val priv =
            ByteArray(32)
                .also(secureRandom::nextBytes)

        val clientPub =
            X25519.publicKey(priv)

        val shared =
            X25519.scalarMult(
                priv,
                serverPub
            )

        /*
         * Don't keep the private key longer than necessary.
         */
        priv.fill(0)

        return ChapterSession(
            chapterToken =
                props.chapterToken,

            sharedSecret =
                shared,

            clientPubkeyB64 =
                Base64.encodeToString(
                    clientPub,
                    Base64.NO_WRAP
                ),
        )
    }


    private fun ensureSession(
        serieSlug: String,
        chapterSlug: String,
    ): ChapterSession {

        val id =
            sessionKey(
                serieSlug,
                chapterSlug
            )

        sessions[id]?.let {
            return it
        }

        val lock =
            sessionLocks[id]
                ?: Any().let { fresh ->

                    sessionLocks.putIfAbsent(
                        id,
                        fresh
                    ) ?: fresh
                }

        synchronized(lock) {

            sessions[id]?.let {
                return it
            }

            val url =
                "$baseUrl/serie/$serieSlug/chapter/$chapterSlug"
                    .toHttpUrl()

            val req =
                apiRequest(
                    url = url,
                    includeXSRFToken = true,
                    includeCSRFToken = false,
                    includeVersion = true,
                )

            val props =
                client.newCall(req)
                    .execute()
                    .use { resp ->

                        if (!resp.isSuccessful) {

                            throw IOException(
                                "Could not rebuild chapter session: HTTP ${resp.code}"
                            )
                        }

                        json.decodeFromString<PageListResponse>(
                            resp.body!!.string()
                        ).props
                    }

            val sess =
                handshakeFrom(props)

            sessions[id] =
                sess

            return sess
        }
    }


    override fun pageListParse(
        response: Response,
    ): List<Page> {

        val props =
            json.decodeFromString<PageListResponse>(
                response.body!!.string()
            ).props

        val sess =
            handshakeFrom(props)

        val id =
            sessionKey(
                props.data.serie.slug,
                props.data.slug
            )

        sessions[id] =
            sess

        return (1..props.pageCount).map { idx ->

            Page(
                index = idx - 1,

                url =
                    "$id#$idx",

                imageUrl =
                    "$baseUrl/serie/${props.data.serie.slug}" +
                        "/chapter/${props.data.slug}" +
                        "/page/$idx#$id",
            )
        }
    }


    private fun hexNonce(
        byteCount: Int = 16,
    ): String {

        val b =
            ByteArray(byteCount)
                .also(secureRandom::nextBytes)

        return b.joinToString("") {
            "%02x".format(it)
        }
    }


    private fun hmacSha256Hex(
        key: String,
        msg: String,
    ): String {

        val mac =
            Mac.getInstance("HmacSHA256").apply {

                init(
                    SecretKeySpec(
                        key.toByteArray(
                            Charsets.US_ASCII
                        ),
                        "HmacSHA256"
                    )
                )
            }

        return mac
            .doFinal(
                msg.toByteArray(
                    Charsets.US_ASCII
                )
            )
            .joinToString("") {
                "%02x".format(it)
            }
    }


    override fun imageRequest(
        page: Page,
    ): Request {

        val parsed =
            page.imageUrl!!
                .toHttpUrl()

        val seg =
            parsed.pathSegments

        require(
            seg.size >= 6 &&
                seg[0] == "serie" &&
                seg[2] == "chapter" &&
                seg[4] == "page"
        ) {
            "unexpected page URL shape: ${parsed.encodedPath}"
        }

        val serieSlug =
            seg[1]

        val chapterSlug =
            seg[3]

        val pageIndex =
            seg[5].toInt()

        val session =
            ensureSession(
                serieSlug,
                chapterSlug
            )

        val sessionId =
            sessionKey(
                serieSlug,
                chapterSlug
            )

        val ts =
            (System.currentTimeMillis() / 1000)
                .toString()

        val nonce =
            hexNonce()

        val sig =
            hmacSha256Hex(
                session.chapterToken,
                "$pageIndex$ts$nonce"
            )

        val url =
            baseHttpUrl
                .newBuilder()
                .addPathSegment("serie")
                .addPathSegment(serieSlug)
                .addPathSegment("chapter")
                .addPathSegment(chapterSlug)
                .addPathSegment("page")
                .addPathSegment(pageIndex.toString())
                .addQueryParameter(
                    "token",
                    session.chapterToken
                )
                .addQueryParameter(
                    "ts",
                    ts
                )
                .addQueryParameter(
                    "nonce",
                    nonce
                )
                .addQueryParameter(
                    "sig",
                    sig
                )
                .fragment(sessionId)
                .build()

        val h =
            headersBuilder()
                .set(
                    "X-Client-Pubkey",
                    session.clientPubkeyB64
                )
                .build()

        return GET(
            url,
            h
        )
    }


    // ============================== Image Decryption ==============================

    /*
     * IMPORTANT:
     *
     * This interceptor is used for chapter images.
     *
     * Thumbnail requests have the #thumbnail fragment and are
     * returned immediately without any encryption/decryption work.
     */
    private fun imageInterceptor(
        chain: Interceptor.Chain,
    ): Response {

        val request =
            chain.request()

        val response =
            chain.proceed(request)

        /*
         * No fragment means this isn't one of our special image
         * requests. Return it untouched.
         */
        val sessionId =
            request.url.fragment
                ?: return response

        /*
         * Thumbnail images are normal images and must NOT go
         * through the chapter decryption process.
         */
        if (sessionId == THUMBNAIL_FRAGMENT) {
            return response
        }

        /*
         * If we don't have a session, don't attempt decryption.
         */
        val session =
            sessions[sessionId]
                ?: return response

        /*
         * Don't try to decrypt an HTTP error response.
         */
        if (!response.isSuccessful) {
            return response
        }

        val responseBody =
            response.body
                ?: return response

        val pageNameRaw =
            response.header("X-Page-Name")
                ?: return response

        val keyHintB64 =
            response.header("X-Key-Hint")
                ?: return response

        val keyHint =
            try {

                Base64.decode(
                    keyHintB64,
                    Base64.DEFAULT
                )

            } catch (e: IllegalArgumentException) {

                response.close()

                throw IOException(
                    "Invalid X-Key-Hint",
                    e
                )
            }

        require(
            keyHint.size >= 32
        ) {
            "X-Key-Hint must decode to >= 32 bytes"
        }

        /*
         * Derive the stream key.
         */
        val streamKey =
            run {

                val sha =
                    MessageDigest
                        .getInstance("SHA-256")
                        .run {

                            update(
                                session.sharedSecret
                            )

                            update(
                                pageNameRaw.toByteArray(
                                    Charsets.UTF_8
                                )
                            )

                            digest()
                        }

                ByteArray(32) { i ->

                    (
                        sha[i].toInt()
                            xor keyHint[i].toInt()
                    ).toByte()
                }
            }

        val networkSource =
            responseBody.source()

        /*
         * Skip the custom prefix.
         */
        if (!networkSource.request(
                PREFIX_LENGTH.toLong()
            )
        ) {

            response.close()

            throw IOException(
                "Image response is shorter than prefix"
            )
        }

        networkSource.skip(
            PREFIX_LENGTH.toLong()
        )

        /*
         * Read SecretStream header.
         */
        if (!networkSource.request(
                STREAM_HEADER_LENGTH.toLong()
            )
        ) {

            response.close()

            throw IOException(
                "Image response is missing stream header"
            )
        }

        val ssHeader =
            networkSource.readByteArray(
                STREAM_HEADER_LENGTH.toLong()
            )

        /*
         * Streaming decryption.
         *
         * We keep the existing encrypted chunk size because
         * changing it could break the server's SecretStream
         * framing.
         */
        val decryptedSource =
            object : okio.Source {

                private val secretStream =
                    SecretStream()

                private val state =
                    State().apply {

                        secretStream.initPull(
                            this,
                            ssHeader,
                            streamKey
                        )
                    }

                private val decryptedBuffer =
                    Buffer()

                private var isFinished =
                    false

                override fun read(
                    sink: Buffer,
                    byteCount: Long,
                ): Long {

                    if (byteCount == 0L) {
                        return 0L
                    }

                    while (
                        decryptedBuffer.size == 0L &&
                        !isFinished
                    ) {

                        /*
                         * Request enough encrypted data for
                         * one SecretStream chunk.
                         */
                        if (
                            !networkSource.request(
                                CHUNK_SIZE.toLong()
                            ) &&
                            networkSource.buffer.size == 0L
                        ) {

                            isFinished = true
                            break
                        }

                        val chunkSize =
                            minOf(
                                CHUNK_SIZE.toLong(),
                                networkSource.buffer.size
                            )

                        if (chunkSize <= 0L) {

                            isFinished = true
                            break
                        }

                        val encryptedData =
                            Buffer().apply {

                                networkSource.read(
                                    this,
                                    chunkSize
                                )
                            }.readByteArray()

                        val result =
                            secretStream.pull(
                                state,
                                encryptedData,
                                encryptedData.size
                            )
                                ?: throw IOException(
                                    "Decryption failed"
                                )

                        decryptedBuffer.write(
                            result.message
                        )

                        if (
                            result.tag.toInt() ==
                            SecretStream.TAG_FINAL
                        ) {

                            isFinished = true
                        }
                    }

                    if (
                        decryptedBuffer.size == 0L &&
                        isFinished
                    ) {
                        return -1L
                    }

                    return decryptedBuffer.read(
                        sink,
                        byteCount
                    )
                }

                override fun timeout(): Timeout =
                    networkSource.timeout()

                override fun close() {
                    networkSource.close()
                }
            }.buffer()

        return response
            .newBuilder()
            .body(
                decryptedSource.asResponseBody(
                    "image/jpg".toMediaType()
                )
            )
            .build()
    }


    override fun imageUrlParse(
        response: Response,
    ): String =
        throw UnsupportedOperationException()


    // ============================== Settings & Filters ==============================

    override fun setupPreferenceScreen(
        screen: PreferenceScreen,
    ) {

        try {

            SwitchPreferenceCompat(
                screen.context
            ).apply {

                key =
                    HIDE_PREMIUM_PREF

                title =
                    prefPremiumTitle

                setDefaultValue(false)

            }.also(
                screen::addPreference
            )

        } catch (e: Exception) {

            // Failsafe for Mangayomi/Tadami
        }
    }


    class TriStateFilter(
        name: String,
        val value: String,
    ) : Filter.TriState(name)


    class CheckBoxFilter(
        name: String,
        val value: String,
    ) : Filter.CheckBox(name)


    private class GenreFilter :
        TriStateGroupFilter(
            "Genres",
            genres
        )


    private class TypeFilter :
        TriStateGroupFilter(
            "Types",
            type
        )


    private class StatusFilter :
        CheckBoxGroup(
            "Status",
            status
        )


    abstract class TriStateGroupFilter(
        name: String,
        options: List<Pair<String, String>>,
    ) : Filter.Group<TriStateFilter>(
        name,
        options.map {
            TriStateFilter(
                it.first,
                it.second
            )
        }
    ) {

        val included
            get() =
                state
                    .filter {
                        it.isIncluded()
                    }
                    .map {
                        it.value
                    }

        val excluded
            get() =
                state
                    .filter {
                        it.isExcluded()
                    }
                    .map {
                        it.value
                    }
    }


    abstract class CheckBoxGroup(
        name: String,
        options: List<Pair<String, String>>,
    ) : Filter.Group<CheckBoxFilter>(
        name,
        options.map {
            CheckBoxFilter(
                it.first,
                it.second
            )
        }
    ) {

        val checked
            get() =
                state
                    .filter {
                        it.state
                    }
                    .map {
                        it.value
                    }
    }


    class SortFilter(
        name: String,
        private val sortValues: List<Pair<String, String>>,
        selection: Selection =
            Selection(0, false),
    ) : Filter.Sort(
        name = name,
        values =
            sortValues
                .map { it.first }
                .toTypedArray(),
        state = selection,
    ) {

        val sort
            get() =
                sortValues[
                    state?.index ?: 0
                ].second

        val ascending
            get() =
                state?.ascending ?: false
    }


    companion object {

        private const val THUMBNAIL_FRAGMENT =
            "thumbnail"

        private const val HIDE_PREMIUM_PREF =
            "pref_hide_premium_chapters"

        /*
         * Do not change this unless the server-side
         * SecretStream framing is also changed.
         */
        private const val CHUNK_SIZE =
            65536 + 17

        private const val PREFIX_LENGTH =
            192

        private const val STREAM_HEADER_LENGTH =
            24


        private val sortValues =
            listOf(
                "New Series" to "date",
                "Trending" to "trending",
                "Recently Updated" to "recently",
                "Most Views" to "views",
                "A-Z" to "alphabetical",
            )


        private val genres =
            listOf(
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
                "Workplace" to "workplace",
            )


        private val type =
            listOf(
                "Comic" to "comic",
                "Doujin" to "doujin",
                "Josei" to "josei",
                "Manga" to "manga",
                "Manhua" to "manhua",
                "Manhwa" to "manhwa",
                "Pornhwa" to "pornhwa",
                "Webtoon" to "webtoon",
            )


        private val status =
            listOf(
                "Ongoing" to "ongoing",
                "Finished" to "finished",
                "Dropped" to "dropped",
                "On Hold" to "onhold",
                "Upcoming" to "upcoming",
            )
    }
}


// ============================== Data Transfer Objects ==============================

@Serializable
class Version(
    val version: String,
)


@Serializable
class LibraryResponse(
    val series: Series,
) {

    @Serializable
    class Series(
        val data: List<BrowseManga>,
        val meta: Meta,
    ) {

        @Serializable
        class Meta(
            @SerialName("current_page")
            val current: Int,

            @SerialName("last_page")
            val last: Int,
        )
    }
}


@Serializable
class BrowseManga(
    private val slug: String,

    @JsonNames("name")
    private val title: String,

    @JsonNames("cover_image")
    private val image: String? = null,
) {

    fun toSManga(
        createThumbnailUrl:
            (String?) -> String?,
    ): SManga {

        return SManga.create().apply {

            url =
                slug

            title =
                this@BrowseManga.title

            thumbnail_url =
                createThumbnailUrl(image)
        }
    }
}


@Serializable
class MangaResponse(
    val props: Props,
) {

    @Serializable
    class Props(
        val serie: Manga,
    ) {

        @Serializable
        class Manga(

            val slug: String,

            @JsonNames("name")
            val title: String,

            @JsonNames("cover_image")
            val image: String? = null,

            val description: String? = null,

            val author: String? = null,

            val artist: String? = null,

            @SerialName("name_alternative")
            val alternativeName: String? = null,

            @SerialName("release_year")
            val releaseYear: Int? = null,

            val status: String? = null,

            val type: Name? = null,

            val genres: List<Name>,

            val chapters: List<Chapter>,
        )


        @Serializable
        class Chapter(

            val slug: String,

            val title: String,

            val createdAt: String,

            val isPremium: Boolean,
        )


        @Serializable
        class Name(
            val name: String,
        )
    }
}


@Serializable
class PageListResponse(
    val props: Props,
) {

    @Serializable
    class Props(

        @SerialName("page_count")
        val pageCount: Int,

        @SerialName("chapter_token")
        val chapterToken: String,

        @SerialName("server_pubkey")
        val serverPubkey: String,

        val data: Data,
    ) {

        @Serializable
        class Data(

            val slug: String,

            val serie: Serie,
        )


        @Serializable
        class Serie(
            val slug: String,
        )
    }
}