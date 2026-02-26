import com.github.gradle.node.npm.task.NpmTask

plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    alias(libs.plugins.node.gradle)
}

group = "app.epistola"
version = (findProperty("version") as String?) ?: "0.1.0-SNAPSHOT"

// Node.js configuration for frontend builds
node {
    version.set("22.12.0")
    npmVersion.set("10.9.2")
    download.set(true)
    workDir.set(file("${project.projectDir}/.gradle/nodejs"))
    npmWorkDir.set(file("${project.projectDir}/.gradle/npm"))
}

// Shared configuration for all Java subprojects
subprojects {
    repositories {
        mavenCentral()
        maven { url = uri("https://s01.oss.sonatype.org/content/repositories/releases/") }
        maven { url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/") }
        maven {
            name = "sonatypeSnapshots"
            url = uri("https://central.sonatype.com/repository/maven-snapshots/")
            mavenContent {
                snapshotsOnly()
            }
        }
        mavenLocal()
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    pluginManager.withPlugin("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
        }
    }
}

// Frontend plugin build tasks
tasks.register<NpmTask>("frontendInstall") {
    description = "Install frontend plugin dependencies"
    workingDir.set(file("frontend/plugin"))
    args.set(listOf("install", "--legacy-peer-deps"))
}

tasks.register<NpmTask>("frontendBuild") {
    description = "Build frontend plugin"
    dependsOn("frontendInstall")
    workingDir.set(file("frontend/plugin"))
    args.set(listOf("run", "build"))
}

tasks.register<NpmTask>("frontendTest") {
    description = "Test frontend plugin"
    dependsOn("frontendInstall")
    workingDir.set(file("frontend/plugin"))
    args.set(listOf("run", "test"))
}

// Test app frontend build tasks
tasks.register<NpmTask>("testAppFrontendInstall") {
    description = "Install test app frontend dependencies"
    dependsOn("frontendBuild")  // Ensure plugin is built before npm install (file: dependency)
    workingDir.set(file("test-app/frontend"))
    args.set(listOf("install"))
}

tasks.register<NpmTask>("testAppFrontendBuild") {
    description = "Build test app frontend"
    dependsOn("testAppFrontendInstall", "frontendBuild")
    workingDir.set(file("test-app/frontend"))
    args.set(listOf("run", "build"))
}

tasks.register<NpmTask>("testAppFrontendStart") {
    description = "Start test app frontend dev server"
    dependsOn("testAppFrontendInstall")
    workingDir.set(file("test-app/frontend"))
    args.set(listOf("run", "start"))
}

// Aggregate tasks
tasks.register("buildAll") {
    description = "Build all modules (backend + frontend)"
    dependsOn(":backend:plugin:build", "frontendBuild")
}

tasks.register("testAll") {
    description = "Test all modules"
    dependsOn(":backend:plugin:test", "frontendTest")
}

tasks.register("publishAll") {
    description = "Publish all artifacts"
    dependsOn(":backend:plugin:publishAndReleaseToMavenCentral")
    // npm publish is done separately from frontend/plugin directory
}
