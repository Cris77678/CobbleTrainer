plugins {
    id("fabric-loom") version "1.9-SNAPSHOT"
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0"
}

val modVersion: String by project
val mavenGroup: String by project
val minecraftVersion: String by project
val fabricLoaderVersion: String by project
val fabricApiVersion: String by project
val fabricLanguageKotlinVersion: String by project
val cobblemonVersion: String by project

version = modVersion
group = mavenGroup

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven("https://maven.impactdev.net/repository/development/")
    maven("https://api.modrinth.com/maven")
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings("net.fabricmc:yarn:${minecraftVersion}+build.1:v2")
    modImplementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
    modImplementation("net.fabricmc:fabric-language-kotlin:$fabricLanguageKotlinVersion")
    modImplementation("com.cobblemon:fabric:$cobblemonVersion")
    include(implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")!!)
}

tasks {
    processResources {
        inputs.property("version", project.version)
        inputs.property("minecraft_version", minecraftVersion)
        inputs.property("fabric_loader_version", fabricLoaderVersion)
        inputs.property("cobblemon_version", cobblemonVersion)
        filesMatching("fabric.mod.json") {
            expand(
                "version" to project.version,
                "minecraft_version" to minecraftVersion,
                "fabric_loader_version" to fabricLoaderVersion,
                "cobblemon_version" to cobblemonVersion
            )
        }
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }
    jar {
        from(rootDir) {
            include("LICENSE")
            rename { "${it}_${project.name}" }
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
}
