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
    compileOnly("com.github.mihonapp:tachiyomix:1.6.0")
    
    // The tools your custom file needs to function
    api("io.reactivex:rxjava:1.3.8")
    api("uy.kohesive.injekt:injekt-core:1.16.1")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
}
