package codegeasse.utils

import android.app.Application
import android.content.SharedPreferences
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get


val applicationContext: Application
    get() = Injekt.get()


fun HttpSource.getPreferencesLazy(): Lazy<SharedPreferences> = lazy {
    Injekt.get<Application>().getSharedPreferences("source_$id", android.content.Context.MODE_PRIVATE)
}


private val json = Json { 
    ignoreUnknownKeys = true 
    isLenient = true 
}

inline fun <reified T> String.parseAs(): T {
    return json.decodeFromString(this)
}


inline fun <reified T> Iterable<*>.firstInstanceOrNull(): T? {
    return filterIsInstance<T>().firstOrNull()
}
