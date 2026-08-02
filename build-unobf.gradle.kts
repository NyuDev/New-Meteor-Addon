// Build script for the UNOBFUSCATED Minecraft line (26.x).
//
// From 26.1 onward Minecraft ships with real (Mojang) names already in the jar,
// so there is nothing to deobfuscate. This requires:
//   - plugin id `net.fabricmc.fabric-loom` (not the `fabric-loom` alias)
//   - no `mappings` dependency
//   - plain `implementation` instead of `modImplementation`
//   - Java 25 target
//
// Our source is already written in Mojang names (see build.gradle.kts), so the
// same source compiles here unchanged.
plugins {
    id("net.fabricmc.fabric-loom")
}

val mcVersion = stonecutter.current.version

version = "${property("mod_version")}+mc$mcVersion"
group = property("maven_group") as String

base {
    archivesName.set(property("archives_base_name") as String)
}

repositories {
    maven("https://maven.meteordev.org/releases")
    maven("https://maven.meteordev.org/snapshots")
}

// A single Meteor 26.1.2-SNAPSHOT covers the whole 26.1.x line.
val meteorVersion = "26.1.2-SNAPSHOT"

loom {
    runs {
        named("client") {
            programArgs("--username", "Shamallow_")
        }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    // No mappings: 26.x is already deobfuscated with Mojang's own names.
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("meteordevelopment:meteor-client:$meteorVersion")
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("minecraft_version", mcVersion)
    filteringCharset = "UTF-8"

    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version,
            "minecraft_version" to mcVersion,
        )
    }
}

java {
    withSourcesJar()
    // Gradle toolchain auto-discovery: picks up the installed JDK 25 even when
    // Gradle itself runs on JDK 21. The toolchain resolver finds JDK 25 from the
    // system without any manual JAVA_HOME swap.
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
