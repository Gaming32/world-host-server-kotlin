plugins {
    application
    kotlin("jvm") version "1.8.21"
    kotlin("plugin.serialization") version "1.8.21"
    id("io.ktor.plugin") version "2.1.1" // It builds fat JARs
    id("net.kyori.blossom") version "1.3.1"
}

group = "io.github.gaming32"
version = "0.4.4"

val ktorVersion = "2.2.4"

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
}

dependencies {
    implementation("org.apache.logging.log4j:log4j-slf4j-impl:2.19.0")
    implementation("org.apache.logging.log4j:log4j-core:2.19.0")
    implementation("io.github.oshai:kotlin-logging-jvm:4.0.0-beta-22")

    implementation("io.ktor:ktor-network:$ktorVersion")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-java:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.5")
}

blossom {
    replaceToken("\\\${version}", project.version, "src/main/kotlin/io/github/gaming32/worldhostserver/versionHolder.kt")
}

kotlin {
    jvmToolchain(11)
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
}
