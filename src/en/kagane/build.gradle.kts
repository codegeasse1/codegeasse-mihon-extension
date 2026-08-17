plugins {
    id("com.android.application")
    id("kotlin-android")
    kotlin("plugin.serialization") version "1.9.0"
}

// --- Extension metadata -----------------------------------------------
val extName = "Kagane"
val extClass = ".Kagane"
val extVersionCode = 2
val isNsfw = true
// ----------------------------------------------------------------------

val libVersion = (project.findProperty("libVersion") as String?) ?: "1.6.0"
val libApiVersion = "1.4"

android {
    namespace = "eu.kanade.tachiyomi.extension.en.kagane"
    compileSdk = 34

    defaultConfig {
        applicationId = "eu.kanade.tachiyomi.extension.en.kagane"
        minSdk = 21
        targetSdk = 34
        versionCode = extVersionCode
        versionName = "$libApiVersion.$extVersionCode"

        manifestPlaceholders["appName"] = "Tachiyomi: $extName"
        manifestPlaceholders["extClass"] = extClass
        manifestPlaceholders["nsfw"] = if (isNsfw) 2 else 0
        manifestPlaceholders["libVersion"] = libApiVersion
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

    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val lang = "en"
            output.outputFileName = "tachiyomi-${lang}.${project.name}-v${variant.versionName}.apk"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly("com.github.mihonapp:tachiyomix:$libVersion")
    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")
    compileOnly("com.squareup.okio:okio:3.9.0")
    compileOnly("org.jsoup:jsoup:1.17.2")
    compileOnly("org.jspecify:jspecify:1.0.0")
    compileOnly("com.google.code.gson:gson:2.10.1")
    implementation(kotlin("stdlib"))

    // Required for JSON DTO parsing
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
}
