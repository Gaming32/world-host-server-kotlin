plugins {
    application
    kotlin("jvm") version "1.8.10"
    id("io.ktor.plugin") version "2.1.1" // It builds fat JARs
}

group = "io.github.gaming32"
version = "1.0-SNAPSHOT"

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

    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.5")
}

kotlin {
    jvmToolchain(11)
}
