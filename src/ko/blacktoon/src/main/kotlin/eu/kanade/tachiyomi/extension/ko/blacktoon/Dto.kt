package eu.kanade.tachiyomi.extension.ko.blacktoon

import com.google.gson.annotations.SerializedName
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import java.text.SimpleDateFormat
import java.util.Locale

val platformsMap = mapOf(
    1 to "네이버",
    2 to "다음",
    3 to "카카오",
    4 to "레진",
    5 to "투믹스",
    6 to "탑툰",
    7 to "코미카",
    8 to "배틀코믹",
    9 to "코믹GT",
    10 to "케이툰",
    11 to "애니툰",
    12 to "폭스툰",
    13 to "피너툰",
    14 to "봄툰",
    15 to "코미코",
    16 to "무툰",
    17 to "지존신마",
    99 to "기타",
)

val tagsMap = mapOf(
    1 to "학원",
    2 to "액션",
    3 to "SF",
    4 to "스토리",
    5 to "판타지",
    6 to "BL/백합",
    7 to "개그/코미디",
    8 to "연애/순정",
    9 to "드라마",
    10 to "로맨스",
    11 to "시대극",
    12 to "스포츠",
    13 to "일상",
    14 to "추리/미스터리",
    15 to "공포/스릴러",
    16 to "성인",
    17 to "옴니버스",
    18 to "에피소드",
    19 to "무협",
    20 to "소년",
    99 to "기타",
)

val publishDayMap = mapOf(
    1 to "월",
    2 to "화",
    3 to "수",
    4 to "목",
    5 to "금",
    6 to "토",
    7 to "일",
    10 to "열흘",
)

class SeriesItem(
    @SerializedName("x")
    private val id: String,
    @SerializedName("t")
    val name: String,
    @SerializedName("p")
    private val poster: String = "",
    @SerializedName("au")
    val author: String = "",
    @SerializedName("g")
    val updatedAt: Long = 0,
    @SerializedName("tag")
    private val tagIds: String = "",
    @SerializedName("c")
    private val platformId: String = "-1",
    @SerializedName("d")
    private val publishDayId: String = "-1",
    @SerializedName("h")
    val hot: Int = 0,
) {
    val tag: List<Int>
        get() = tagIds.split(",")
            .filter(String::isNotBlank)
            .map(String::toInt)

    val platform: Int get() = platformId.toInt()

    val publishDay: Int get() = publishDayId.toInt()

    @Volatile
    var listIndex = -1

    fun toSManga(cdnUrl: String): SManga = SManga.create().apply {
        url = id
        title = name
        thumbnail_url = poster.takeIf { it.isNotBlank() }?.let {
            cdnUrl + it.replace("_x4", "").replace("_x3", "")
        }
        genre = buildList {
            add(platformsMap[platform])
            add(publishDayMap[publishDay])
            tag.forEach {
                add(tagsMap[it])
            }
        }.filterNotNull().joinToString()
        author = this@SeriesItem.author
        status = when (listIndex) {
            0 -> SManga.COMPLETED
            1 -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
    }
}

class Chapter(
    @SerializedName("id")
    val id: String,
    @SerializedName("t")
    val title: String,
    @SerializedName("d")
    val date: String = "",
) {
    fun toSChapter(mangaId: String): SChapter = SChapter.create().apply {
        url = "$mangaId/$id"
        name = title
        date_upload = runCatching { dateFormat.parse(date)?.time ?: 0L }.getOrDefault(0L)
    }
}

private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
