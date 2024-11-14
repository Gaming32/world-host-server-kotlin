import net.raphimc.classtokenreplacer.task.ReplaceTokensTask
import org.jetbrains.gradle.ext.Application
import org.jetbrains.gradle.ext.runConfigurations
import org.jetbrains.gradle.ext.settings

plugins {
    application
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("net.raphimc.class-token-replacer") version "1.1.3"
    id("org.jetbrains.gradle.plugin.idea-ext") version "1.1.9"
}

group = "io.github.gaming32"
version = "0.5.0"

val ktorVersion = "3.0.1"

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
    implementation(kotlin("reflect"))

    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("org.apache.logging.log4j:log4j-slf4j2-impl:2.24.1")
    implementation("org.apache.logging.log4j:log4j-core:2.24.1")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.0")

    implementation("io.ktor:ktor-network:$ktorVersion")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-java:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    implementation("org.apache.commons:commons-csv:1.12.0")

    implementation("com.mojang:authlib:6.0.55")

    implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.6")

    testImplementation(kotlin("test"))
}

sourceSets {
    main {
        classTokenReplacer {
            property("\${version}", project.version)
        }
    }
}

kotlin {
    jvmToolchain(17)
}

idea.project.settings.runConfigurations {
    register<Application>("World Host Server") {
        mainClass = "io.github.gaming32.worldhostserver.MainKt"
        moduleName = "${project.idea.module.name}.main"
        jvmArgs = "-Dlog4j.configurationFile=\"${file("log4j2-debug.xml")}\""
    }
}

tasks.compileKotlin {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.ExperimentalStdlibApi")
    }
}

tasks.test {
    useJUnitPlatform()
}

val fatJar by tasks.registering(Jar::class) {
    archiveClassifier = "fat"

    val replaceTask = tasks.getByName<ReplaceTokensTask>(
        sourceSets.main.get().getTaskName("replace", "tokens")
    )
    dependsOn(replaceTask)
    from(sourceSets.main.get().output, replaceTask.outputDir)
    exclude {
        val modified = it.relativePath.getFile(replaceTask.outputDir.get().asFile)
        modified != it.file && modified.isFile
    }

    from(configurations.runtimeClasspath.get().files.map { if (it.isDirectory) it else zipTree(it) })
    exclude("META-INF/versions/9/module-info.class", "module-info.class")
    exclude("META-INF/LICENSE", "META-INF/NOTICE", "META-INF/DEPENDENCIES")
    exclude("META-INF/LICENSE.txt", "META-INF/NOTICE.txt")
    duplicatesStrategy = DuplicatesStrategy.WARN
}
tasks.assemble.get().dependsOn(fatJar)
