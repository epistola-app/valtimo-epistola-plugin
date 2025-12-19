@file:Suppress("UNCHECKED_CAST")

import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.springframework.boot.gradle.tasks.run.BootRun
import java.util.Properties

plugins {
    war
    // Idea
    idea
    id("org.jetbrains.gradle.plugin.idea-ext")

    // Spring
    id("org.springframework.boot")
    id("io.spring.dependency-management")

    // Kotlin
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
    kotlin("plugin.allopen")

    // Checkstyle
    id("org.jlleitschuh.gradle.ktlint")
    id("com.diffplug.spotless")

    // Other
    id("com.avast.gradle.docker-compose")
}

java.sourceCompatibility = JavaVersion.VERSION_21
java.targetCompatibility = JavaVersion.VERSION_21

repositories {
    mavenCentral()
    maven { url = uri("https://s01.oss.sonatype.org/content/repositories/releases/") }
    maven { url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/") }
}

val valtimoVersion: String by project
val postgresqlDriverVersion: String by project
val nettyResolverDnsNativeMacOsVersion: String by project
val mockitoKotlinVersion: String by project

dependencies {
    implementation(platform("com.ritense.valtimo:valtimo-dependency-versions:$valtimoVersion"))

    implementation("com.ritense.valtimo:valtimo-dependencies")

    implementation("com.ritense.valtimo:local-document-generation")
    implementation("com.ritense.valtimo:local-resource")
    implementation("com.ritense.valtimo:local-mail")
    implementation("com.ritense.valtimo:milestones")
    implementation("com.ritense.valtimo:object-management")
    implementation("com.ritense.valtimo:objecten-api-authentication")
    implementation("com.ritense.valtimo:objecten-api")
    implementation("com.ritense.valtimo:objecttypen-api")
    implementation("com.ritense.valtimo:zaken-api")

    implementation("org.postgresql:postgresql:$postgresqlDriverVersion")

    if (System.getProperty("os.arch") == "aarch64") {
        runtimeOnly("io.netty:netty-resolver-dns-native-macos:$nettyResolverDnsNativeMacOsVersion:osx-aarch_64")
    }

    // Kotlin logger
    implementation("io.github.oshai:kotlin-logging")

    // Testing
    testImplementation("com.ritense.valtimo:test-utils-common")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.mockito:mockito-core")
    testImplementation("org.mockito.kotlin:mockito-kotlin:$mockitoKotlinVersion")
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        freeCompilerArgs = listOf("-Xjsr305=strict")
        jvmTarget = JvmTarget.JVM_21
    }
}

apply(plugin = "docker-compose")
apply(from = "gradle/dockerComposeValtimo.gradle.kts")

dockerCompose {
    setProjectName("valtimo-docker-compose")
    useDockerComposeV2 = true
    useComposeFiles.add("${buildDir.absolutePath}/docker/extract/valtimo-docker-compose-v-13/docker-compose.yaml")
    composeAdditionalArgs.addAll("--profile", "zgw")
    stopContainers = false
    removeContainers = false
    removeVolumes = false
    if (DefaultNativePlatform.getCurrentOperatingSystem().isMacOsX) {
        executable = "/usr/local/bin/docker-compose"
        dockerExecutable = "/usr/local/bin/docker"
    }
}

ktlint {
    version.set("1.4.1")
}

apply(from = "gradle/environment.gradle.kts")
val configureEnvironment = extra["configureEnvironment"] as (task: ProcessForkOptions) -> Unit

tasks.bootRun {
    val t = this
    doFirst {
        configureEnvironment(t)
    }
}

tasks.register("bootRunWithDocker", BootRun::class.java) {
    group = "application"
    description = "Starts docker containers and then runs this project as a Spring Boot application"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.ritense.valtimo.ApplicationKt"

    dependsOn("composeUpValtimo")
    doFirst {
        val f = file(".env.properties")
        if (f.isFile()) {
            val props = Properties()
            f.inputStream().use { props.load(it) }
            props.forEach { key, value ->
                environment[key.toString()] = value.toString()
            }
        }
    }
}