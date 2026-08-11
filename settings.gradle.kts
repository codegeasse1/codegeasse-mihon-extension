rootProject.name = "mihon-extension-template"

// Auto-register every extension module.
// Convention: src/<lang>/<extension-name>

val srcDir = file("src")

if (srcDir.exists()) {
    srcDir
        .listFiles()
        ?.filter { it.isDirectory }
        ?.sortedBy { it.name }
        ?.forEach { langDir ->
            langDir
                .listFiles()
                ?.filter { it.isDirectory }
                ?.sortedBy { it.name }
                ?.forEach { extDir ->
                    val buildGradleKts = file("${extDir.path}/build.gradle.kts")
                    val buildGradle = file("${extDir.path}/build.gradle")

                    if (buildGradleKts.exists() || buildGradle.exists()) {
                        val modulePath = ":src:${langDir.name}:${extDir.name}"
                        include(modulePath)
                        project(modulePath).projectDir = extDir
                    }
                }
        }
}
