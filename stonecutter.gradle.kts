plugins {
    id("dev.kikugie.stonecutter")
    id("fabric-loom") version "1.16-SNAPSHOT" apply false
}

stonecutter active "1.21.11" /* [SC] DO NOT EDIT */

tasks.register("buildAll") {
    description = "Build jars for every supported Minecraft version."
    group = "project"
    // Depends on each version subproject's build task.
    // 26.x nodes use the toolchain to auto-discover JDK 25; no JAVA_HOME swap needed.
    subprojects.forEach { sub -> dependsOn(":${sub.name}:build") }
}

tasks.register<Copy>("collectJars") {
    description = "Copy every built jar into build/jars for release."
    group = "project"
    dependsOn("buildAll")
    from(subprojects.map { it.layout.buildDirectory.dir("libs") }) {
        exclude("*-sources.jar")
    }
    into(layout.buildDirectory.dir("jars"))
}
