plugins {
    id("com.android.application")
    id("kotlin-android")
}

// --- Extension metadata -----------------------------------------------
val extName = "Example"
val extClass = ".Example"
val extVersionCode = 1
val isNsfw = false
// ------------------------------------------------------------------------

val libVersion: String by rootProject.extra.properties.withDefault { "1.5" }

android {
    namespace = "eu.kanade.tachiyomi.extension.en.example"
    compileSdk = 34

    defaultConfig {
        applicationId = "eu.kanade.tachiyomi.extension.en.example"
        minSdk = 21
        targetSdk = 34
        versionCode = extVersionCode
        versionName = "1.$extVersionCode"

        manifestPlaceholders["appName"] = "Tachiyomi: $extName"
        manifestPlaceholders["extClass"] = extClass
        manifestPlaceholders["nsfw"] = if (isNsfw) 1 else 0
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Stub interfaces only
    compileOnly("com.github.mihonapp:tachiyomix:1.6")

    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")
    compileOnly("com.okio:okio:3.9.0") // or com.squareup.okio:okio:3.9.0 depending on your setup
    compileOnly("org.jsoup:jsoup:1.17.2")
    compileOnly("com.google.code.gson:gson:2.10.1")
    compileOnly("app.cash.quickjs:quickjs-android:0.9.2")

    implementation(kotlin("stdlib"))
}
