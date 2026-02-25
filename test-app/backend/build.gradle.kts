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
    alias(libs.plugins.idea.ext)

    // Spring
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)

    // Kotlin
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.kotlin.allopen)

    // Checkstyle
    alias(libs.plugins.ktlint)
    alias(libs.plugins.spotless)

    // Other
    alias(libs.plugins.docker.compose)
}

java.sourceCompatibility = JavaVersion.VERSION_21
java.targetCompatibility = JavaVersion.VERSION_21

repositories {
    mavenCentral()
    maven { url = uri("https://s01.oss.sonatype.org/content/repositories/releases/") }
    maven { url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/") }
}

configurations.runtimeClasspath {
    // js-community is a POM-only artifact (no JAR) that ends up on the classpath via
    // valtimo:core → graalvm:js → graalvm:js-community. Spring's classpath scanner
    // fails when it tries to open the .pom as a JAR (ZipException).
    exclude(group = "org.graalvm.js", module = "js-community")
}

dependencies {
    implementation(platform(libs.valtimo.bom))

    implementation(libs.valtimo.dependencies)

    // Epistola plugin
    implementation(project(":backend:plugin"))

    implementation(libs.valtimo.local.document.generation)
    implementation(libs.valtimo.local.resource)
    implementation(libs.valtimo.local.mail)
    implementation(libs.valtimo.milestones)
    implementation(libs.valtimo.`object`.management)
    implementation(libs.valtimo.objecten.api.authentication)
    implementation(libs.valtimo.objecten.api)
    implementation(libs.valtimo.objecttypen.api)
    implementation(libs.valtimo.zaken.api)
    implementation(libs.valtimo.documenten.api)
    implementation(libs.valtimo.catalogi.api)

    implementation(libs.postgresql)

    if (System.getProperty("os.arch") == "aarch64") {
        runtimeOnly(variantOf(libs.netty.resolver.dns.macos) { classifier("osx-aarch_64") })
    }

    // Kotlin logger
    implementation(libs.kotlin.logging)

    // Testing
    testImplementation(libs.valtimo.test.utils.common)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.testcontainers.postgresql)
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