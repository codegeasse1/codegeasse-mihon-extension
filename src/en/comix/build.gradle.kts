plugins {
    id("com.android.application")
    id("kotlin-android")
}

// --- Extension metadata -----------------------------------------------
val extName = "Codegeasse Comix" // 👈 Changed to your unique name
val extClass = ".Comix"
val extVersionCode = 1 // Reset back to 1 since this is a brand new extension
val isNsfw = false
// ------------------------------------------------------------------------

val libVersion = (project.findProperty("libVersion") as String?) ?: "1.6.0"

android {
    // We leave the namespace alone so you don't have to rename your physical folders on your phone
    namespace = "eu.kanade.tachiyomi.extension.en.comix"
    compileSdk = 34

    defaultConfig {
        // 👇 This is the crucial fingerprint change that separates it from Keiyoushi
        applicationId = "eu.kanade.tachiyomi.extension.en.codegeassecomix" 
        minSdk = 21
        targetSdk = 34
        versionCode = extVersionCode
        versionName = "1.$extVersionCode"

        manifestPlaceholders["appName"] = "Tachiyomi: $extName"
        manifestPlaceholders["extClass"] = extClass
        // Content Rating per tachiyomix's manifest spec: 0 = Safe, 1 = Mixed, 2 = NSFW
        manifestPlaceholders["nsfw"] = if (isNsfw) 2 else 0
        manifestPlaceholders["libVersion"] = "1.4"
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
    compileOnly("com.github.mihonapp:tachiyomix:$libVersion")

    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")
    compileOnly("com.squareup.okio:okio:3.9.0")
    compileOnly("org.jsoup:jsoup:1.17.2")
    compileOnly("com.google.code.gson:gson:2.10.1")

    implementation(kotlin("stdlib"))
}
