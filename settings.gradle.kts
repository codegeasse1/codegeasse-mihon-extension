rootProject.name = "mihon-extension-template"

include(":lib:codegeasse-utils")

// Auto-register extension modules...
val srcDir = file("src")
if (srcDir.exists()) {
    srcDir.listFiles()?.filter { it.isDirectory }?.forEach { langDir ->
        langDir.listFiles()?.filter { it.isDirectory }?.forEach { extDir ->
            if (file("${extDir.path}/build.gradle.kts").exists() || file("${extDir.path}/build.gradle").exists()) {
                val modulePath = ":src:${langDir.name}:${extDir.name}"
                include(modulePath)
                project(modulePath).projectDir = extDir
            }
        }
    }
}
