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

val libVersion = (project.findProperty("libVersion") as String?) ?: "1.6.0"

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
        // Content Rating per tachiyomix's manifest spec: 0 = Safe, 1 = Mixed, 2 = NSFW
        manifestPlaceholders["nsfw"] = if (isNsfw) 2 else 0
        manifestPlaceholders["libVersion"] = libVersion
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
}

    kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Stub interfaces only (HttpSource, SManga, SChapter, ...).
    // The real implementations live inside Mihon/Aniyomi itself at runtime.
    compileOnly("com.github.mihonapp:tachiyomix:$libVersion")

    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")
    compileOnly("com.squareup.okio:okio:3.9.0")
    compileOnly("org.jsoup:jsoup:1.17.2")
    compileOnly("com.google.code.gson:gson:2.10.1")

    implementation(kotlin("stdlib"))
}
