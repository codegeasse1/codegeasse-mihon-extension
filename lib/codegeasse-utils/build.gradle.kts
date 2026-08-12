plugins {
    id("com.android.library")
    id("kotlin-android")
    kotlin("plugin.serialization") version "1.9.0"
}

android {
    namespace = "codegeasse.utils"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }
}

// Add JitPack directly here so this specific module can find Injekt!
repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    compileOnly("com.github.mihonapp:tachiyomix:1.6.0")
    
    // The tools your custom file needs to function
    api("io.reactivex:rxjava:1.3.8")
    api("com.github.inorichi.injekt:injekt-core:65b0440")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
}
