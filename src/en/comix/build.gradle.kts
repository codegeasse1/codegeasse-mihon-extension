plugins {
    id("com.android.application")
    id("kotlin-android")
}

// --- Extension metadata -----------------------------------------------
val extName = "Codegeasse Comix"
val extClass = ".Comix"
val extVersionCode = 5 // Bumped to 5 to force a clean update
val isNsfw = false
// ------------------------------------------------------------------------

// 🔥 FIXED: Downgraded to 1.4.4 for stable Mihon compatibility
val libVersion = "1.4.4"

android {
    // Keep namespace as comix so your physical folder structure doesn't break
    namespace = "eu.kanade.tachiyomi.extension.en.comix"
    compileSdk = 34

    defaultConfig {
        // 🔥 FIXED: Unique ID so Keiyoushi doesn't overwrite it
        applicationId = "eu.kanade.tachiyomi.extension.en.codegeassecomix"
        minSdk = 21
        targetSdk = 34
        versionCode = extVersionCode
        
        // Display version in the app
        versionName = "1.4.$extVersionCode" 

        manifestPlaceholders["appName"] = "Tachiyomi: $extName"
        manifestPlaceholders["extClass"] = extClass
        manifestPlaceholders["nsfw"] = if (isNsfw) 2 else 0
        
        // 🔥 FIXED: Mihon strictly requires this exact string to load the APK!
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
