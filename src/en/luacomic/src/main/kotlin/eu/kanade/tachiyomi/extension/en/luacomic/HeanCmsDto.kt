package eu.kanade.tachiyomi.extension.en.luacomic

import com.google.gson.annotations.SerializedName
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

internal class QuerySearchDto(
    val data: List<SeriesDto> = emptyList(),
    val meta: QuerySearchMetaDto? = null,
)

internal class QuerySearchMetaDto(
    @SerializedName("current_page") private val currentPage: Int = 0,
    @SerializedName("last_page") private val lastPage: Int = 0,
) {
    fun hasNextPage() = currentPage < lastPage
}

internal class SeriesDto(
    val id: Int = 0,
    @SerializedName("series_slug") val slug: String = "",
    val author: String? = null,
    val description: String? = null,
    val studio: String? = null,
    val status: String? = null,
    val thumbnail: String = "",
    val title: String = "",
    val tags: List<TagDto>? = emptyList(),
) {

    fun toSManga(): SManga = SManga.create().apply {
        val descriptionBody = this@SeriesDto.description?.let(Jsoup::parseBodyFragment)

        this.title = this@SeriesDto.title
        author = this@SeriesDto.author?.trim()
        artist = this@SeriesDto.studio?.trim()
        description = descriptionBody?.select("p")
            ?.joinToString("\n\n") { it.text() }
            ?.ifEmpty { descriptionBody.text().replace("\n", "\n\n") }
        genre = this@SeriesDto.tags.orEmpty()
            .sortedBy { it.name }
            .joinToString { it.name }
        thumbnail_url = this@SeriesDto.thumbnail.ifEmpty { null }
        status = this@SeriesDto.status?.toStatus() ?: SManga.UNKNOWN
        url = "/series/${this@SeriesDto.slug}#${this@SeriesDto.id}"
    }
}

internal class TagDto(
    val name: String = "",
)

internal class ChapterPayloadDto(
    val data: List<ChapterDto> = emptyList(),
    val meta: ChapterMetaDto? = null,
)

internal class ChapterMetaDto(
    @SerializedName("current_page") private val currentPage: Int = 0,
    @SerializedName("last_page") private val lastPage: Int = 0,
) {
    fun hasNextPage() = currentPage < lastPage
}

internal class ChapterDto(
    val id: Int = 0,
    @SerializedName("chapter_name") val name: String = "",
    @SerializedName("chapter_title") val title: String? = null,
    @SerializedName("chapter_slug") val slug: String = "",
    @SerializedName("created_at") val createdAt: String? = null,
    val price: Int? = null,
) {

    fun toSChapter(seriesSlug: String): SChapter = SChapter.create().apply {
        this.name = this@ChapterDto.name.trim()

        if (title != null) {
            this.name += " - ${title.trim()}"
        }

        if (price != 0) {
            this.name += " \uD83D\uDD12"
        }

        date_upload = createdAt?.let { parseDate(it) } ?: 0L

        url = "/series/$seriesSlug/${this@ChapterDto.slug}#${this@ChapterDto.id}"
    }
}

internal class PagePayloadDto(
    val chapter: PageDto? = null,
    private val paywall: Boolean = false,
) {
    fun isPaywalled() = paywall
}

internal class PageDto(
    @SerializedName("chapter_data") val chapterData: PageDataDto? = null,
)

internal class PageDataDto(
    val images: List<String>? = emptyList(),
)

private fun String.toStatus(): Int = when (this) {
    "Ongoing" -> SManga.ONGOING
    "Hiatus" -> SManga.ON_HIATUS
    "Dropped" -> SManga.CANCELLED
    "Completed", "Finished" -> SManga.COMPLETED
    else -> SManga.UNKNOWN
}

private fun parseDate(value: String): Long = runCatching {
    ISO_8601.parse(value)?.time ?: 0L
}.getOrDefault(0L)

private val ISO_8601 = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}
