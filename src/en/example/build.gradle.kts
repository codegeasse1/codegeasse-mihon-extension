dependencies {
    // Correct library dependency coordinate for Mihon extension stubs
    compileOnly("com.github.mihonapp:tachiyomix:1.6")

    // Fix the Okio group name (added squareup)
    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")
    compileOnly("com.squareup.okio:okio:3.9.0") 
    compileOnly("org.jsoup:jsoup:1.17.2")
    compileOnly("com.google.code.gson:gson:2.10.1")
    compileOnly("app.cash.quickjs:quickjs-android:0.9.2")

    implementation(kotlin("stdlib"))
}
