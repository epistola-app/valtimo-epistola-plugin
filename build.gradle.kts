import com.github.gradle.node.npm.task.NpmTask

plugins {
    id("org.springframework.boot") apply false
    id("io.spring.dependency-management") apply false
    id("com.github.node-gradle.node")
}

group = "app.epistola"
version = "1.0.0-SNAPSHOT"

// Shared configuration for all subprojects
val valtimoVersion = "13.4.1.RELEASE"
val lombokVersion = "1.18.42"
val testcontainersVersion = "1.20.4"

// Make versions available to subprojects
extra["valtimoVersion"] = valtimoVersion
extra["lombokVersion"] = lombokVersion
extra["testcontainersVersion"] = testcontainersVersion

// Node.js configuration for frontend builds
node {
    version.set("20.10.0")
    npmVersion.set("10.2.3")
    download.set(true)
    workDir.set(file("${project.projectDir}/.gradle/nodejs"))
    npmWorkDir.set(file("${project.projectDir}/.gradle/npm"))
}

// Shared configuration for all Java subprojects
subprojects {
    repositories {
        mavenCentral()
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
    args.set(listOf("install"))
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
    dependsOn(":backend:plugin:publish")
    // npm publish is done separately from frontend/plugin directory
}
