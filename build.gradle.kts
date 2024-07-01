plugins {
    application
    kotlin("jvm") version "1.8.21"
    kotlin("plugin.serialization") version "1.8.21"
    id("io.ktor.plugin") version "2.1.1" // It builds fat JARs
    id("net.kyori.blossom") version "1.3.1"
}

group = "io.github.gaming32"
version = "0.4.4"

val ktorVersion = "2.3.12"

application {
    mainClass.set("io.github.gaming32.worldhostserver.MainKt")
}

tasks.withType<Jar> {
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
        attributes["Multi-Release"] = true
    }
}

repositories {
    mavenCentral()
    maven("https://libraries.minecraft.net")
}

dependencies {
    implementation("org.apache.logging.log4j:log4j-slf4j2-impl:2.23.1")
    implementation("org.apache.logging.log4j:log4j-core:2.23.1")
    implementation("io.github.oshai:kotlin-logging-jvm:5.1.0")

    implementation("io.ktor:ktor-network:$ktorVersion")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-java:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    implementation("org.apache.commons:commons-csv:1.11.0")

    implementation("com.mojang:authlib:6.0.54")

    implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.5")

    testImplementation(kotlin("test"))
}

blossom {
    replaceToken("\\\${version}", project.version, "src/main/kotlin/io/github/gaming32/worldhostserver/versionHolder.kt")
}

kotlin {
    jvmToolchain(17)
}

tasks {
    shadowJar {
        mergeServiceFiles()
    }

    compileKotlin {
        compilerOptions {
            freeCompilerArgs.add("-opt-in=kotlin.ExperimentalStdlibApi")
        }
    }

    test {
        useJUnitPlatform()
    }
}
