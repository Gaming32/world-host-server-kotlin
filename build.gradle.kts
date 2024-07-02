import net.raphimc.classtokenreplacer.task.ReplaceTokensTask

plugins {
    application
    kotlin("jvm") version "2.0.0"
    kotlin("plugin.serialization") version "2.0.0"
    id("net.raphimc.class-token-replacer") version "1.1.2"
}

group = "io.github.gaming32"
version = "0.4.5"

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
    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("org.apache.logging.log4j:log4j-slf4j2-impl:2.23.1")
    implementation("org.apache.logging.log4j:log4j-core:2.23.1")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.0")

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
