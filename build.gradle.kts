plugins {
    id("fabric-loom")
}

// Stonecutter sets the active Minecraft version for this node.
val mcVersion = stonecutter.current.version

// Minecraft 26.x ships unobfuscated; this file only handles 1.20.x-1.21.x.
// The 26.x nodes use build-unobf.gradle.kts instead (declared in versions.json).
val isUnobf = mcVersion.startsWith("26.")

version = "${property("mod_version")}+mc$mcVersion"
group = property("maven_group") as String

base {
    archivesName.set(property("archives_base_name") as String)
}

repositories {
    maven("https://maven.meteordev.org/releases")
    maven("https://maven.meteordev.org/snapshots")
}

// Meteor Client snapshot for each Minecraft version.
//
// Naming history:
//   0.5.x / 0.6.0   - old Meteor version numbering (MC 1.20.x and 1.21.1-1.21.3)
//   MC_VERSION      - new naming starting from MC 1.21.4 (meteor matches the MC ver)
val meteorVersion = mapOf(
    "1.20.1"  to "0.5.4-SNAPSHOT",
    "1.20.4"  to "0.5.6-SNAPSHOT",
    "1.21.1"  to "0.5.8-SNAPSHOT",
    "1.21.3"  to "0.5.9-SNAPSHOT",
    "1.21.4"  to "1.21.4-SNAPSHOT",
    "1.21.5"  to "1.21.5-SNAPSHOT",
    "1.21.8"  to "1.21.8-SNAPSHOT",
    "1.21.10" to "1.21.10-SNAPSHOT",
    "1.21.11" to "1.21.11-SNAPSHOT",
)[mcVersion] ?: error("No Meteor version mapped for Minecraft $mcVersion")

loom {
    runs {
        named("client") {
            programArgs("--username", "Shamallow_")
        }
        // Second dev client, own run dir so it doesn't fight the default one over
        // options.txt / world saves, for testing player-to-player interaction locally.
        create("client2") {
            client()
            configName = "Client (SaltyNew)"
            programArgs("--username", "SaltyNew")
            runDir("run2")
        }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    // All obfuscated versions deobfuscate with Mojang's official mappings.
    // These share the same API names as 26.x (already unobfuscated), so a single
    // source tree covers both without any per-version name differences for core APIs.
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    modImplementation("meteordevelopment:meteor-client:$meteorVersion")
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

// 1.20.x-1.21.x target Java 21. Gradle itself must run on JDK 21+ for these nodes.
val javaTarget = 21

java {
    withSourcesJar()
    val v = JavaVersion.toVersion(javaTarget)
    sourceCompatibility = v
    targetCompatibility = v
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(javaTarget)
}
