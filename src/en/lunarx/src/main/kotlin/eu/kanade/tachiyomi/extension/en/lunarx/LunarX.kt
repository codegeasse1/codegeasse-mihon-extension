package eu.kanade.tachiyomi.extension.en.lunarx

import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import codegeasse.utils.getPreferencesLazy
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
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
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
 * the request URL embeds a short-lived token `mint()`ed from two constant
 * keys that the site ships encrypted in its RSC payload, and the request
 * must carry a DPoP proof (`cant-catch-this-monkey`) signed with a per-app
 * P-256 keypair. Successful responses may wrap the chapter data in a
 * `session_data` blob that is AES-CBC encrypted with a key derived from one
 * of those constants + the request nonce (+ a public-key thumbprint), so we
 * decrypt it before reading the page URLs.
 *
 * The site additionally requires a Cloudflare Turnstile token on the pages
 * endpoint when it flags the client; a plain HTTP client cannot produce
 * one, so if the reader is ever refused you'll see an "Access denied"
 * error there. Everything else (browse/search/details/chapters) is plain
 * JSON.
 */
class LunarX : HttpSource(), ConfigurableSource {

    override val name = "LunarX"
    override val baseUrl = "https://lunarx.to"
    override val lang = "en"
    override val supportsLatest = true

    private val apiUrl = "https://api.lunarx.to"

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private var lastNonce: String = ""


    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = SHOW_NSFW_PREF
            title = "Show 18+ content"
            summary = "Include adult (R18+) titles in popular, latest, and search results."
            setDefaultValue(true)
        }.let(screen::addPreference)
    }

    private fun showNsfw(): Boolean = preferences.getBoolean(SHOW_NSFW_PREF, true)


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

    override fun pageListRequest(chapter: SChapter): Request {

        val path = chapter.url
        val slug = path.substringAfter("/manga/").substringBefore("/")
        val num = path.substringAfterLast("/").substringBefore("?")
        val lang = path.substringAfter("lang=", "en").substringBefore("&")

        val kp = keyPair()
        val rand = Random()
        val minted = mint(slug, num, rand)
        lastNonce = minted.nonce

        val base = "$apiUrl/api/manga/r/${minted.token}"
        val url = if (lang != "en") "$base?language=$lang" else base
        val dpop = serenityProof("GET", base, kp, rand)

        return Request.Builder()
            .url(url)
            .headers(apiHeaders())
            .addHeader("cant-catch-this-monkey", dpop)
            .get()
            .build()
    }

    override fun pageListParse(response: Response): List<Page> {

        val body = response.body!!.string()

        if (!response.isSuccessful) {
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
                    denied != null -> denied["message"]?.jsonPrimitive?.contentOrNull
                        ?: "Access denied by the site"
                    else -> "HTTP ${response.code}"
                },
            )
        }

        var root = json.parseToJsonElement(body).jsonObject
        var data = root["data"] as? JsonObject
            ?: throw IOException("No chapter data in response")

        if (data["is_coming_soon"]?.jsonPrimitive?.booleanOrNull == true) {
            throw IOException("This chapter is coming soon")
        }

        // Chapter payload may be wrapped in an encrypted session_data blob.
        val session = data["session_data"]?.jsonPrimitive?.contentOrNull
        if (!session.isNullOrEmpty()) {
            val decrypted = unpackSession(session, lastNonce, keyPair())
            if (decrypted != null) {
                root = json.parseToJsonElement(decrypted).jsonObject
                data = root["data"] as? JsonObject
                    ?: throw IOException("No chapter data after unpack")
            }
        }

        val images = data["images"] as? JsonArray ?: return emptyList()
        val base = data["base_url"]?.jsonPrimitive?.contentOrNull ?: apiUrl

        return images.mapIndexedNotNull { i, el ->
            val u = el.jsonPrimitive.contentOrNull ?: return@mapIndexedNotNull null
            val full = if (u.startsWith("http")) u else "$base$u"
            Page(i, url = full, imageUrl = full)
        }
    }

    override fun imageRequest(page: Page): Request =
        GET(page.imageUrl!!, apiHeaders())


    // ============================== Helpers ==============================

    private fun apiHeaders(): Headers = headersBuilder()
        .set("Origin", baseUrl)
        .set("Referer", "$baseUrl/")
        .build()

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
        val cached = preferences.getString(DPOP_KEY_PREF, null)
        if (!cached.isNullOrEmpty()) {
            runCatching {
                val parts = cached.split(":")
                val kf = KeyFactory.getInstance("EC")
                val priv = kf.generatePrivate(
                    PKCS8EncodedKeySpec(Base64.decode(parts[0], Base64.DEFAULT)),
                )
                val pub = kf.generatePublic(
                    X509EncodedKeySpec(Base64.decode(parts[1], Base64.DEFAULT)),
                )
                return KeyPair(pub, priv)
            }
        }

        val kp = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

        val stored = Base64.encodeToString(kp.private.encoded, Base64.DEFAULT) + ":" +
            Base64.encodeToString(kp.public.encoded, Base64.DEFAULT)
        preferences.edit().putString(DPOP_KEY_PREF, stored).commit()

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

    private fun mint(slug: String, chapter: String, rand: Random): Minted {
        val k0 = KEY0.toByteArray()
        val k1 = KEY1.toByteArray()
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

    private fun unpackSession(session: String, nonce: String, kp: KeyPair): String? {
        val ct = b64DecodeLenient(session) ?: return null
        val k0 = KEY0.toByteArray()

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

        private const val SHOW_NSFW_PREF = "show_18_plus"
        private const val DPOP_KEY_PREF = "dpop_p256_keys"

        /*
         * Constant reader keys extracted from the site's encrypted RSC
         * payload (rotated by the site maintainers — see the class comment
         * if pages stop working).
         */
        private const val KEY0 = "RLxi7IOWuU1siL1B8LVrMtFtf2vo9HeeWORTbUjSF0Gt3y3RpXwY8cDiDtq3qwdclpv1TF4"
        private const val KEY1 = "uTMtgEBM80im6vKqubDWvQmjmfvEjsDMATYjdxP34C01SNNEhBAj4F1RQYZRD47DcL3N3GXnacUtHh0F"

        private val chapterDateFormats = listOf(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT),
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT),
        ).map { it.apply { timeZone = TimeZone.getTimeZone("UTC") } }
    }
}
