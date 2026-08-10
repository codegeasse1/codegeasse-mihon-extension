buildscript {
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
    }
    dependencies {
    // Upgrade AGP to a newer version compatible with Kotlin 2.4+
    classpath("com.android.tools.build:gradle:8.7.3") 
    
    // Upgrade Kotlin to 2.4.0 to match the metadata version of tachiyomix
    classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0") 
}
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
