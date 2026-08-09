plugins {
    id("com.android.application")
    id("kotlin-android")
    id("tachiyomi.extension")
}

val extName = "The Blank"
val extClass = ".TheBlank"
val extVersionCode = 1
val isNsfw = true

android {
    namespace = "eu.kanade.tachiyomi.extension.en.theblankmanga"
    compileSdk = 34

    defaultConfig {
        applicationId = "eu.kanade.tachiyomi.extension.en.theblankmanga"
        minSdk = 21
        targetSdk = 34
        versionCode = extVersionCode
        versionName = "1.$extVersionCode"
    }

    buildTypes {
        release {
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
    compileOnly("com.github.mihonapp:extensions-lib:1.4")

    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")
    compileOnly("com.squareup.okio:okio:3.9.0")
    compileOnly("org.jsoup:jsoup:1.17.2")
    compileOnly("com.google.code.gson:gson:2.10.1")
    compileOnly("app.cash.quickjs:quickjs-android:0.9.2")

    implementation(kotlin("stdlib"))
}
