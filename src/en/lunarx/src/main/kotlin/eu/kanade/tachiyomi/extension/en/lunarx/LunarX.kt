package eu.kanade.tachiyomi.extension.en.lunarx

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Random
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * LunarX (https://lunarx.to/manga) is a Next.js manga reader. Metadata is
 * AniList-backed and everything is served from the JSON API at
 * api.lunarx.to, which enforces an Origin allowlist (so every API request
 * carries `Origin: https://lunarx.to`).
 *
 *     Lists     : GET /api/manga/search?query=&page=&limit=&sort=&order=
 *     Details   : GET /api/manga/title/<slug>
 *     Chapters  : GET /api/manga/<slug>
 *     Pages     : GET /api/manga/r/<minted-token>
 *
 * The pages endpoint is DRM-wrapped in the site's own client-side crypto:
 * the request URL embeds a short-lived token `mint()`ed from two keys that
 * the site rotates and ships *sealed inside every reader page's React
 * flight payload* (re-extracted at runtime, see readerKeys()), and the
 * request must carry a DPoP proof (`cant-catch-this-monkey`) signed with a
 * per-app P-256 keypair. Successful responses may wrap the chapter data in
 * a `session_data` blob that is AES-CBC encrypted with a key derived from
 * one of those keys + the request nonce (+ a public-key thumbprint), so we
 * decrypt it before reading the page URLs.
 *
 * The site additionally requires a Cloudflare Turnstile token on the pages
 * endpoint when it flags the client; a plain HTTP client cannot produce
 * one, so if the reader is ever refused you'll see an "Access denied"
 * error there. Without the token the API serves low-res preview pages
 * (`/api/cdn/p/...`). Everything else (browse/search/details/chapters) is
 * plain JSON.
 *
 * The pages endpoint answers new clients with a `cache_status: revalidate`
 * handshake; pageListParse re-issues the request with a fresh minted token
 * and DPoP proof until it gets chapter data (the same dance the website's
 * reader performs).
 */
class LunarX : HttpSource(), ConfigurableSource {

    override val name = "LunarX"
    override val baseUrl = "https://lunarx.to"
    override val lang = "en"
    override val supportsLatest = true

    private val apiUrl = "https://api.lunarx.to"

    // Requests we must issue ourselves (key extraction, the revalidate
    // retry) use a plain OkHttpClient instead of `network.client`: the
    // latter is resolved through the host app's Injekt graph and is
    // unavailable on JVM hosts like Tachidesk/Suwayomi.
    private val directClient: OkHttpClient by lazy { OkHttpClient() }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private var lastNonce: String = ""


    // ============================== Settings ==============================
    //
    // No Injekt/Application dependency: the extension captures a Context from
    // the preference screen when it's shown and keeps the DPoP keypair in
    // memory, so it also runs on custom forks whose Injekt graph does not
    // register `Application` (e.g. the user's nekoread build).

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        appContext = screen.context.applicationContext
        SwitchPreferenceCompat(screen.context).apply {
            key = SHOW_NSFW_PREF
            title = "Show 18+ content"
            summary = "Include adult (R18+) titles in popular, latest, and search results."
            setDefaultValue(true)
        }.let(screen::addPreference)
    }

    private fun showNsfw(): Boolean {
        val ctx = appContext ?: return true
        return ctx.getSharedPreferences("${ctx.packageName}_preferences", Context.MODE_PRIVATE)
            .getBoolean(SHOW_NSFW_PREF, true)
    }


    // ============================== Search & Browse ==============================

    private val sortOptions = listOf(
        "Default" to "",
        "Latest Update" to "latest_chapter:desc",
        "Title A-Z" to "title:asc",
        "Year" to "year:desc",
        "Rating" to "rating:desc",
    )

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Text search matches manga titles."),
        Filter.Separator(),
        SortFilter("Sort", sortOptions),
        StatusFilter(),
    )

    override fun popularMangaRequest(page: Int): Request =
        searchRequest(page, "", "", "", "")

    override fun popularMangaParse(response: Response): MangasPage =
        searchMangaParse(response)

    override fun latestUpdatesRequest(page: Int): Request =
        searchRequest(page, "", "latest_chapter", "desc", "")

    override fun latestUpdatesParse(response: Response): MangasPage =
        searchMangaParse(response)

    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request {

        val sort = filters.filterIsInstance<SortFilter>().firstOrNull()?.value.orEmpty()
        val status = filters.filterIsInstance<StatusFilter>().firstOrNull()?.selectedValue.orEmpty()

        val sortVal = sort.substringBefore(":")
        val orderVal = sort.substringAfter(":", "desc").ifEmpty { "desc" }

        return searchRequest(page, query, sortVal, if (sortVal == "title") "asc" else orderVal, status)
    }

    private fun searchRequest(
        page: Int,
        query: String,
        sort: String,
        order: String,
        status: String = "",
    ): Request {

        val url = "$apiUrl/api/manga/search".toHttpUrl().newBuilder()
            .apply {
                if (query.isNotBlank()) addQueryParameter("query", query.trim())
                addQueryParameter("page", page.toString())
                addQueryParameter("limit", "30")
                if (sort.isNotBlank()) {
                    addQueryParameter("sort", sort)
                    addQueryParameter("order", order)
                }
                if (status.isNotBlank()) addQueryParameter("status", status)
            }
            .build()

        return GET(url.toString(), apiHeaders())
    }

    override fun searchMangaParse(response: Response): MangasPage {

        val root = json.parseToJsonElement(response.body!!.string()).jsonObject
        val items = root["manga"] as? JsonArray ?: return MangasPage(emptyList(), false)

        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val totalPages = root["total_pages"]?.jsonPrimitive?.intOrNull

        val mangas = items.mapNotNull { el ->
            (el as? JsonObject)?.toSManga()
        }

        return MangasPage(
            mangas = mangas,
            hasNextPage = totalPages != null && currentPage < totalPages,
        )
    }


    // ============================== Manga Details ==============================

    override fun getMangaUrl(manga: SManga): String =
        "$baseUrl${manga.url}"

    override fun mangaDetailsRequest(manga: SManga): Request {
        val slug = manga.url.trimEnd('/').substringAfterLast('/')
        return GET("$apiUrl/api/manga/title/$slug", apiHeaders())
    }

    override fun mangaDetailsParse(response: Response): SManga {

        val root = json.parseToJsonElement(response.body!!.string()).jsonObject
        val manga = root["manga"] as? JsonObject
            ?: throw IOException("Could not parse manga details")

        val slug = response.request.url.encodedPath.trimEnd('/').substringAfterLast('/')

        return SManga.create().apply {
            url = "/manga/$slug"
            title = manga["title"]?.jsonPrimitive?.contentOrNull ?: ""
            thumbnail_url = manga["cover_url"]?.jsonPrimitive?.contentOrNull
            description = manga["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
            genre = manga["genres"]?.jsonPrimitive?.contentOrNull
                ?.let { parseJsonStringArray(it) }
                .orEmpty()
            status = statusFrom(manga["publication_status"]?.jsonPrimitive?.contentOrNull)
            author = manga["author"]?.jsonPrimitive?.contentOrNull.orEmpty()
            artist = manga["artist"]?.jsonPrimitive?.contentOrNull.orEmpty()
        }
    }


    // ============================== Chapters ==============================

    override fun chapterListRequest(manga: SManga): Request {
        val slug = manga.url.trimEnd('/').substringAfterLast('/')
        return GET("$apiUrl/api/manga/$slug", apiHeaders())
    }

    override fun chapterListParse(response: Response): List<SChapter> {

        val root = json.parseToJsonElement(response.body!!.string()).jsonObject
        val slug = response.request.url.encodedPath.trimEnd('/').substringAfterLast('/')
        val data = root["data"] as? JsonArray ?: return emptyList()

        // The API returns one row per chapter * language. Group by chapter
        // number (with subnumber) and prefer English when available.
        val grouped = LinkedHashMap<String, ChapterGroup>()

        for (el in data) {
            val obj = el as? JsonObject ?: continue
            if (obj["is_coming_soon"]?.jsonPrimitive?.booleanOrNull == true) continue

            val number = obj["chapter_number"]?.jsonPrimitive?.contentOrNull ?: continue
            val sub = obj["chapter_subnumber"]?.jsonPrimitive?.intOrNull ?: 0
            val key = if (sub > 0) "$number.$sub" else number
            val lang = obj["language"]?.jsonPrimitive?.contentOrNull.orEmpty()

            val group = (obj["uploader_profile"] as? JsonObject)
                ?.get("username")
                ?.jsonPrimitive
                ?.contentOrNull

            val existing = grouped[key]
            if (existing == null || lang.equals("en", true)) {
                grouped[key] = ChapterGroup(
                    key = key,
                    lang = lang,
                    title = obj["chapter_title"]?.jsonPrimitive?.contentOrNull,
                    uploaded = obj["uploaded_at"]?.jsonPrimitive?.contentOrNull,
                    group = group,
                )
            }
        }

        return grouped.values
            .map { g ->
                SChapter.create().apply {
                    url = "/manga/$slug/${g.key}?lang=${g.lang.ifEmpty { "en" }}"
                    name = buildString {
                        append("Chapter ", g.key)
                        g.title?.takeIf { it.isNotBlank() }?.let { append(": ", it) }
                    }
                    chapter_number = g.key.toFloatOrNull() ?: 0f
                    date_upload = parseChapterDate(g.uploaded)
                    scanlator = g.group
                }
            }
            .sortedBy { it.chapter_number }
            .reversed()
    }


    // ============================== Pages ==============================

    override fun getChapterUrl(chapter: SChapter): String =
        "$baseUrl${chapter.url}"

    private var lastChapterUrl: String = ""
    private var lastKeys: Pair<String, String> = DEFAULT_KEYS

    override fun pageListRequest(chapter: SChapter): Request {
        try {
            lastChapterUrl = chapter.url
            return buildReaderRequest(chapter.url)
        } catch (t: Throwable) {
            throw IOException("LunarX pageListRequest: ${t::class.simpleName} :: ${briefTrace(t)}", t)
        }
    }

    private fun buildReaderRequest(path: String): Request {
        val slug = path.substringAfter("/manga/").substringBefore("/")
        val num = path.substringAfterLast("/").substringBefore("?")
        val lang = path.substringAfter("lang=", "en").substringBefore("&")

        val keys = readerKeys(slug, num)
        lastKeys = keys

        val kp = keyPair()
        val rand = Random()
        val minted = mint(slug, num, rand, keys)
        lastNonce = minted.nonce

        val base = "$apiUrl/api/manga/r/${minted.token}"
        val url = if (lang != "en") "$base?language=$lang" else base
        val dpop = serenityProof("GET", base, kp, rand)

        return Request.Builder()
            .url(url)
            .headers(apiHeaders())
            .addHeader("cant-catch-this-monkey", dpop)
            .addHeader("X-Native-App", "true")
            .get()
            .build()
    }

    override fun pageListParse(response: Response): List<Page> {
        try {
            return pageListParseInner(response)
        } catch (t: Throwable) {
            if (t is IOException) throw t
            throw IOException("LunarX pageListParse: ${t::class.simpleName} :: ${briefTrace(t)}", t)
        }
    }

    private fun pageListParseInner(response: Response): List<Page> {
        var resp = response

        repeat(MAX_READER_ATTEMPTS) { attempt ->
            val body = resp.body?.string()
                ?: throw IOException("LunarX null response body (HTTP ${resp.code})")
            Log.d("LunarX", "reader attempt ${attempt + 1}: HTTP ${resp.code} -> ${body.take(1200)}")

            if (!resp.isSuccessful) {
                val msg = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                val denied = msg?.get("access_denied") as? JsonObject
                val kofi = msg?.get("is_kofi_locked")?.jsonPrimitive?.booleanOrNull == true
                val locked = msg?.get("is_locked")?.jsonPrimitive?.booleanOrNull == true
                val password = msg?.get("is_password_protected")?.jsonPrimitive?.booleanOrNull == true
                throw IOException(
                    when {
                        kofi -> "This chapter is for Ko-fi supporters only"
                        locked -> "This chapter is currently locked"
                        password -> "This chapter is password protected"
                        denied != null -> buildString {
                            denied["reason"]?.jsonPrimitive?.contentOrNull
                                ?.takeIf { it.isNotBlank() }
                                ?.let { append(it, ": ") }
                            append(denied["message"]?.jsonPrimitive?.contentOrNull
                                ?: "Access denied by the site")
                        }
                        else -> "HTTP ${resp.code}: ${body.take(300)}"
                    },
                )
            }

            var root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: throw IOException("Invalid response from the reader API")
            var data = root["data"] as? JsonObject
            val revalidate = root["cache_status"]?.jsonPrimitive?.contentOrNull == "revalidate"

            if (data != null && !revalidate) {
                // Chapter payload may be wrapped in an encrypted session_data blob.
                val session = data["session_data"]?.jsonPrimitive?.contentOrNull
                if (!session.isNullOrEmpty()) {
                    val decrypted = unpackSession(session, lastNonce, keyPair(), lastKeys)
                    if (decrypted != null) {
                        root = json.parseToJsonElement(decrypted).jsonObject
                        data = root["data"] as? JsonObject
                    }
                }

                if (data != null && data["is_coming_soon"]?.jsonPrimitive?.booleanOrNull == true) {
                    throw IOException("This chapter is coming soon")
                }

                val wantedSlug = lastChapterUrl.substringAfter("/manga/").substringBefore("/")
                val gotSlug = root["slug"]?.jsonPrimitive?.contentOrNull
                val slugOk = gotSlug == null || gotSlug == "unknown" || gotSlug == wantedSlug

                val images = data?.get("images") as? JsonArray
                if (slugOk && images != null && images.isNotEmpty()) {
                    return images.mapIndexedNotNull { i, el ->
                        val u = el.jsonPrimitive.contentOrNull ?: return@mapIndexedNotNull null
                        val full = if (u.startsWith("http")) u else apiUrl + u
                        Page(i, url = full, imageUrl = full)
                    }
                }
                // Missing/empty images or slug mismatch => the server wants a revalidate.
            }

            if (attempt < MAX_READER_ATTEMPTS - 1) {
                resp.close()
                try {
                    resp = directClient.newCall(buildReaderRequest(lastChapterUrl)).execute()
                } catch (t: Throwable) {
                    throw IOException("LunarX retry request failed: ${t::class.simpleName} :: ${briefTrace(t)}", t)
                }
            } else {
                throw IOException(
                    "LunarX kept asking to revalidate the chapter request. " +
                        "Last response: ${body.take(300)}",
                )
            }
        }

        throw IOException("Could not load chapter pages")
    }

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl!!, apiHeaders())


    // ============================== Helpers ==============================

    override fun headersBuilder(): Headers.Builder =
        super.headersBuilder()
            .set("Origin", baseUrl)
            .set("Referer", "$baseUrl/")

    private fun apiHeaders(): Headers = headersBuilder().build()

    private fun JsonObject.toSManga(): SManga? {
        val slug = get("slug")?.jsonPrimitive?.contentOrNull ?: return null
        val rating = get("rating")?.jsonPrimitive?.contentOrNull
        val adult = rating == "R18+" || rating == "R+"
        if (adult && !showNsfw()) return null

        return SManga.create().apply {
            url = "/manga/$slug"
            title = this@toSManga["title"]?.jsonPrimitive?.contentOrNull ?: ""
            thumbnail_url = this@toSManga["cover_url"]?.jsonPrimitive?.contentOrNull
                ?: this@toSManga["poster_url"]?.jsonPrimitive?.contentOrNull
            description = this@toSManga["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
            genre = this@toSManga["genres"]?.jsonPrimitive?.contentOrNull
                ?.let { parseJsonStringArray(it) }
                .orEmpty()
            status = statusFrom(this@toSManga["publication_status"]?.jsonPrimitive?.contentOrNull)
        }
    }

    private fun parseJsonStringArray(raw: String): String =
        runCatching {
            json.parseToJsonElement(raw).jsonArray
                .mapNotNull { it.jsonPrimitive.contentOrNull }
                .joinToString(", ")
        }.getOrDefault("")

    private fun statusFrom(status: String?): Int = when (status?.uppercase()) {
        "ONGOING", "RELEASING", "AIRING" -> SManga.ONGOING
        "COMPLETED", "FINISHED" -> SManga.COMPLETED
        "HIATUS", "ON HIATUS", "PAUSED" -> SManga.ON_HIATUS
        "CANCELLED", "CANCELED" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    private fun parseChapterDate(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L

        raw.toLongOrNull()?.let { ts ->
            return if (ts < 1_000_000_000_000L) ts * 1000 else ts
        }

        return chapterDateFormats
            .firstNotNullOfOrNull { fmt ->
                runCatching { fmt.parse(raw)?.time }.getOrNull()
            }
            ?: 0L
    }


    // ============================== Crypto (reader) ==============================

    private fun keyPair(): KeyPair {
        keyPairHolder?.let { return it }

        val kp = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

        keyPairHolder = kp
        return kp
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private fun base64Url(bytes: ByteArray): String =
        String(Base64.encode(bytes, Base64.NO_WRAP or Base64.URL_SAFE), Charsets.US_ASCII)

    private fun b64DecodeLenient(s: String): ByteArray? {
        val variants = listOf(
            Base64.NO_WRAP or Base64.URL_SAFE,
            Base64.NO_WRAP,
            Base64.DEFAULT,
        )
        for (flags in variants) {
            runCatching { return Base64.decode(s, flags) }.getOrNull()
        }
        return null
    }

    private fun fixed32(bi: BigInteger): ByteArray {
        val raw = bi.toByteArray()
        return if (raw.size > 32) {
            raw.copyOfRange(raw.size - 32, raw.size)
        } else {
            ByteArray(32 - raw.size) + raw
        }
    }

    private fun ecdsaRawSign(data: ByteArray, priv: PrivateKey): ByteArray {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(priv)
        sig.update(data)
        return derToRaw(sig.sign())
    }

    private fun derToRaw(der: ByteArray): ByteArray {
        var i = 0
        require(der[i++].toInt() == 0x30) { "bad DER sequence" }
        val seqLen = der[i++].toInt() and 0xff
        if (seqLen and 0x80 != 0) i += seqLen and 0x7f

        fun nextInt(): ByteArray {
            require(der[i++].toInt() == 0x02) { "bad DER integer" }
            val len = der[i++].toInt() and 0xff
            val raw = der.copyOfRange(i, i + len)
            i += len
            return raw
        }

        val r = nextInt()
        val s = nextInt()
        val out = ByteArray(64)
        val rb = fixed32(BigInteger(1, r))
        val sb = fixed32(BigInteger(1, s))
        System.arraycopy(rb, 0, out, 0, 32)
        System.arraycopy(sb, 0, out, 32, 32)
        return out
    }

    private fun serenityProof(method: String, htu: String, kp: KeyPair, rand: Random): String {
        val pub = kp.public as ECPublicKey
        val point = pub.w
        val x = base64Url(fixed32(point.affineX))
        val y = base64Url(fixed32(point.affineY))
        val jwk = """{"crv":"P-256","kty":"EC","x":"$x","y":"$y"}"""
        val header = """{"alg":"ES256","typ":"dpop+jwt","jwk":$jwk}"""
        val iat = System.currentTimeMillis() / 1000
        val jti = randomString(18, rand) + "-" + System.nanoTime()
        val payload = """{"htu":"$htu","htm":"$method","iat":$iat,"jti":"$jti"}"""
        val signingInput = base64Url(header.toByteArray()) + "." + base64Url(payload.toByteArray())
        val sig = base64Url(ecdsaRawSign(signingInput.toByteArray(), kp.private))
        return "$signingInput.$sig"
    }

    private fun thumbprint(kp: KeyPair): String {
        val pub = kp.public as ECPublicKey
        val point = pub.w
        val x = base64Url(fixed32(point.affineX))
        val y = base64Url(fixed32(point.affineY))
        val canonical = """{"crv":"P-256","kty":"EC","x":"$x","y":"$y"}"""
        return base64Url(sha256(canonical.toByteArray()))
    }

    private fun mint(slug: String, chapter: String, rand: Random, keys: Pair<String, String>): Minted {
        val k0 = keys.first.toByteArray()
        val k1 = keys.second.toByteArray()
        val key = blend(k0, k1)
        val nonce = randomString(12, rand)
        val hex = java.lang.Long.toHexString(System.currentTimeMillis() / 1000)
        val body = "$hex|$nonce|$slug|$chapter|${randomString(6, rand)}"
        return Minted(token = encryptBody(body, key, rand), nonce = nonce)
    }

    private fun blend(k0: ByteArray, k1: ByteArray): ByteArray {
        val h = sha256(k0 + byteArrayOf(0x01) + k1)
        val len = maxOf(k0.size, k1.size)
        val out = ByteArray(len)
        for (i in 0 until len) {
            val a = k0[i % k0.size].toInt() and 0xff
            val b = k1[i % k1.size].toInt() and 0xff
            val c = h[i % 32].toInt() and 0xff
            out[i] = ((a xor b xor c xor ((83 * i + 29) and 0xff)) and 0xff).toByte()
        }
        return out
    }

    private fun encryptBody(body: String, key: ByteArray, rand: Random): String {
        val n = rand.nextInt(256)
        val bodyBytes = body.toByteArray()
        val out = ByteArray(bodyBytes.size + 1)
        out[0] = n.toByte()
        for (i in bodyBytes.indices) {
            val b = bodyBytes[i].toInt() and 0xff
            val k = key[(i + n) % key.size].toInt() and 0xff
            out[i + 1] = ((b xor k xor ((n + 83 * i) and 0xff)) and 0xff).toByte()
        }
        return base64Url(out)
    }

    private fun unpackSession(session: String, nonce: String, kp: KeyPair, keys: Pair<String, String>): String? {
        val ct = b64DecodeLenient(session) ?: return null
        val k0 = keys.first.toByteArray()

        val keys = listOf(
            sha256(k0 + byteArrayOf(0x01) + nonce.toByteArray() + byteArrayOf(0x02) + thumbprint(kp).toByteArray()),
            sha256(k0 + byteArrayOf(0x01) + nonce.toByteArray()),
        )

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val iv = ByteArray(16)

        for (k in keys) {
            try {
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), IvParameterSpec(iv))
                val pt = cipher.doFinal(ct)
                val s = String(pt, Charsets.UTF_8)
                if (s.startsWith("{") || s.startsWith("[")) return s
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun randomString(len: Int, rand: Random): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return buildString {
            repeat(len) { append(chars[rand.nextInt(chars.length)]) }
        }
    }


    // ==================== Runtime reader-key extraction ====================
    //
    // The mint keys are rotated by the site and shipped sealed inside every
    // reader page's React flight payload (module 817263 in chunk r1.js).
    // To stay current we fetch the reader HTML once per KEYS_TTL and undo the
    // two-stage obfuscation: stage 1 XORs with a 31-hash of the entry name,
    // stage 2 feeds an LCG + program through a byte transform and validates
    // a magic header before splitting out the two keys.

    private fun readerKeys(slug: String, num: String): Pair<String, String> {
        val now = System.currentTimeMillis()
        cachedKeys?.let {
            if (now - keysFetchedAt < KEYS_TTL_MS) return it
        }
        return try {
            val extracted = fetchKeysFromPage(slug, num)
            if (extracted != null) {
                cachedKeys = extracted
                keysFetchedAt = now
                extracted
            } else {
                cachedKeys ?: DEFAULT_KEYS
            }
        } catch (_: Exception) {
            cachedKeys ?: DEFAULT_KEYS
        }
    }

    private fun fetchKeysFromPage(slug: String, num: String): Pair<String, String>? {
        val req = GET("$baseUrl/manga/$slug/$num", apiHeaders())
        val resp = try {
            directClient.newCall(req).execute()
        } catch (t: Throwable) {
            Log.d("LunarX", "keys page fetch threw ${t::class.simpleName}: ${t.message}")
            return null
        }
        resp.use {
            if (!it.isSuccessful) {
                Log.d("LunarX", "keys page HTTP ${it.code}")
                return null
            }
            val b = it.body?.string() ?: return null
            val k = extractKeys(b)
            if (k == null) Log.d("LunarX", "key extraction failed; page len ${b.length}")
            return k
        }
    }

    private fun briefTrace(t: Throwable): String =
        t.stackTrace.take(6).joinToString(" | ") { it.toString() }

    private fun extractKeys(html: String): Pair<String, String>? {
        val pushes = extractPushes(html)
        if (pushes.isEmpty()) return null
        val combined = pushes.joinToString("")

        val props = extractKv(combined)

        for ((name, value) in props) {
            val entry = tryStage1(name, value) ?: continue
            val keys = runStage2(entry, props) ?: continue
            Log.d("LunarX", "extracted fresh reader keys (len ${keys.first.length}/${keys.second.length})")
            return keys
        }
        return null
    }

    // The flight-payload regexes catastrophically backtrack on large pages
    // (StackOverflowError in the JVM regex engine), so extraction is done
    // with linear single-pass scanners instead.

    private fun extractPushes(html: String): List<String> {
        val out = ArrayList<String>()
        val marker = "self.__next_f.push(["
        var i = 0
        while (true) {
            val start = html.indexOf(marker, i)
            if (start < 0) break
            val quote = html.indexOf('"', start + marker.length)
            if (quote < 0) break
            val sb = StringBuilder()
            var j = quote + 1
            var closed = false
            while (j < html.length) {
                val c = html[j]
                if (c == '\\') {
                    sb.append(c)
                    if (j + 1 < html.length) {
                        sb.append(html[j + 1])
                        j++
                    }
                } else if (c == '"') {
                    closed = true
                    break
                } else {
                    sb.append(c)
                }
                j++
            }
            if (closed) out.add(unescapeJsonString(sb.toString()))
            i = j + 1
        }
        return out
    }

    private fun extractKv(combined: String): LinkedHashMap<String, String> {
        val props = LinkedHashMap<String, String>()
        val n = combined.length
        var i = 0
        while (i < n) {
            if (combined[i] != '"') {
                i++
                continue
            }
            var j = i + 1
            while (j < n && combined[j] != '"' && combined[j] != '\\' &&
                combined[j] != '\n' && combined[j] != '\r'
            ) {
                j++
            }
            if (j >= n || combined[j] != '"') {
                i++
                continue
            }
            val key = combined.substring(i + 1, j)
            if (key.isEmpty() || key.length > 40 ||
                !key.all { it.isLetterOrDigit() || it == '_' }
            ) {
                i++
                continue
            }
            j++
            if (j >= n || combined[j] != ':') {
                i = j
                continue
            }
            j++
            if (j >= n || combined[j] != '"') {
                i = j
                continue
            }
            j++
            val vStart = j
            while (j < n && combined[j] != '"' && combined[j] != '\\') j++
            if (j >= n || combined[j] != '"') {
                i = vStart
                continue
            }
            val value = combined.substring(vStart, j)
            props.putIfAbsent(key, value)
            i = j + 1
        }
        return props
    }

    private data class SealedEntry(
        val seed: Int,
        val a: Int,
        val b: Int,
        val progStr: String,
        val names: List<String>,
    )

    private fun tryStage1(name: String, value: String): SealedEntry? {
        return runCatching {
            val decoded = b64DecodeLenient(value.reversed()) ?: return null
            val l = hash31(name)
            val sb = StringBuilder(decoded.size)
            for (i in decoded.indices) {
                sb.append((decoded[i].toInt() and 0xff xor ((l + 37 * i) and 0xff)).toChar())
            }
            val parts = sb.toString().split("|")
            if (parts.size != 6 || parts[0] != "3") return null
            val seed = parts[1].toIntOrNull(16) ?: return null
            val a = parts[2].toIntOrNull(16) ?: return null
            val b = parts[3].toIntOrNull(16) ?: return null
            SealedEntry(seed, a, b, parts[4], parts[5].split(".").filter { it.isNotBlank() })
        }.getOrNull()
    }

    private fun runStage2(entry: SealedEntry, props: Map<String, String>): Pair<String, String>? {
        val raw = entry.names.map { props[it] ?: "" }.joinToString("")
        if (raw.length < 2 || raw.length % 2 != 0) return null

        val prog = parseProgram(entry.progStr) ?: return null
        val out = ArrayList<Int>(raw.length / 2)
        var state = entry.seed and 0xff
        var prev = 0
        var step = 0
        var i = 0
        while (i < raw.length) {
            val byte = raw.substring(i, i + 2).toIntOrNull(16) ?: return null
            state = (state * entry.a + entry.b) and 0xff
            out.add(transformByte(byte xor prev, step, state, prog))
            prev = byte
            step++
            i += 2
        }

        if (out.size < 7 || out[0] != 0xA7 || out[1] != 0x3E || out[2] != 0x91) return null
        val k0Len = (out[3] shl 8) or out[4]
        val k1Len = (out[5] shl 8) or out[6]
        if (k0Len <= 0 || k1Len <= 0 || 7 + k0Len + k1Len > out.size) return null

        val k0 = StringBuilder(k0Len)
        for (j in 0 until k0Len) k0.append(out[7 + j].toChar())
        val k1 = StringBuilder(k1Len)
        for (j in 0 until k1Len) k1.append(out[7 + k0Len + j].toChar())
        return k0.toString() to k1.toString()
    }

    private fun parseProgram(s: String): List<IntArray>? {
        if (s.isEmpty() || s.length % 3 != 0) return null
        val out = ArrayList<IntArray>(s.length / 3)
        var i = 0
        while (i < s.length) {
            val op = s[i].digitToIntOrNull(16) ?: return null
            val arg = s.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
            if (op > 7) return null
            out.add(intArrayOf(op, arg))
            i += 3
        }
        return out
    }

    private fun transformByte(input: Int, step: Int, state: Int, prog: List<IntArray>): Int {
        var n = input and 0xff
        for (i in prog.indices.reversed()) {
            val op = prog[i][0]
            val f = prog[i][1]
            n = when (op) {
                0 -> n xor f
                1 -> (n - f) and 0xff
                2 -> {
                    val rot = (7 and f).takeIf { it != 0 } ?: 1
                    (((n ushr rot) or (n shl (8 - rot))) and 0xff)
                }
                3 -> (((15 and n) shl 4) or (n ushr 4)) and 0xff
                4 -> n xor state
                5 -> n xor ((step * (1 or f) + f) and 0xff)
                6 -> n.inv() and 0xff
                else -> (f - n) and 0xff
            }
        }
        return n and 0xff
    }

    private fun hash31(name: String): Int {
        var t = 0
        for (i in name.indices) t = (31 * t + name[i].code) and 0xff
        return t
    }

    private fun unescapeJsonString(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '"' -> { append('"'); i += 2 }
                    '\\' -> { append('\\'); i += 2 }
                    '/' -> { append('/'); i += 2 }
                    'n' -> { append('\n'); i += 2 }
                    't' -> { append('\t'); i += 2 }
                    'r' -> { append('\r'); i += 2 }
                    'b' -> { append('\b'); i += 2 }
                    'f' -> { append('\u000C'); i += 2 }
                    'u' -> {
                        val hex = s.substring(i + 2, minOf(i + 6, s.length))
                        append(hex.toIntOrNull(16)?.toChar() ?: '?')
                        i += 6
                    }
                    else -> { append(s[i + 1]); i += 2 }
                }
            } else {
                append(c); i++
            }
        }
    }


    // ============================== Filters & DTOs ==============================

    private class SortFilter(
        name: String,
        private val options: List<Pair<String, String>>,
        selection: Selection = Selection(0, false),
    ) : Filter.Sort(
        name = name,
        values = options.map { it.first }.toTypedArray(),
        state = selection,
    ) {
        val value: String
            get() = options[state?.index ?: 0].second
    }

    private class StatusFilter : Filter.Select<String>(
        "Status",
        arrayOf("Any", "Ongoing", "Completed", "Hiatus", "Cancelled"),
        state = 0,
    ) {
        val selectedValue: String
            get() = when (state) {
                1 -> "ONGOING"
                2 -> "COMPLETED"
                3 -> "HIATUS"
                4 -> "CANCELLED"
                else -> ""
            }
    }

    private data class ChapterGroup(
        val key: String,
        var lang: String,
        var title: String?,
        var uploaded: String?,
        var group: String?,
    )

    private data class Minted(
        val token: String,
        val nonce: String,
    )

    companion object {

        private const val PAGE_SIZE = 30
        private const val MAX_READER_ATTEMPTS = 3

        private const val SHOW_NSFW_PREF = "show_18_plus"

        @Volatile
        private var appContext: Context? = null

        @Volatile
        private var keyPairHolder: KeyPair? = null

        @Volatile
        private var cachedKeys: Pair<String, String>? = null

        @Volatile
        private var keysFetchedAt: Long = 0L

        private const val KEYS_TTL_MS = 45L * 60 * 1000

        /*
         * The reader mint keys. They are rotated by the site and shipped
         * sealed inside every reader page's React flight payload, so the
         * extension re-extracts them at runtime (see readerKeys()). These
         * constants are only a bootstrapping fallback for when the page
         * cannot be fetched — update them when the site rotates.
         */
        private val DEFAULT_KEYS: Pair<String, String> =
            "4bLRZvIqOP4AaCkbVq2WclLZsUdxjvaYeai81xZgKbxv28z43CN5oQxgz" to
                "HW8uYR0TmrH1fA6oHJpv72LNYbYeDcE7Vh7GWaJim7SyrMfENJ0eUE9ETJdlOXbOdDgyF9jUt4r"

        private val chapterDateFormats = listOf(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT),
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT),
        ).map { it.apply { timeZone = TimeZone.getTimeZone("UTC") } }
    }
}
