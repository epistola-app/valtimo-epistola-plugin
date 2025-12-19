// Background info https://github.com/gradle/gradle/issues/1697
pluginManagement {
    val kotlinVersion: String by settings
    val springBootVersion: String by settings
    val springDependencyManagementVersion: String by settings
    val ktlintVersion: String by settings
    val spotlessVersion: String by settings
    val ideaExt: String by settings
    val dockerComposePluginVersion: String by settings

    plugins {
        // Idea
        idea
        id("org.jetbrains.gradle.plugin.idea-ext") version ideaExt

        // Spring
        id("org.springframework.boot") version springBootVersion
        id("io.spring.dependency-management") version springDependencyManagementVersion

        // Kotlin
        kotlin("jvm") version kotlinVersion
        kotlin("plugin.spring") version kotlinVersion
        kotlin("plugin.jpa") version kotlinVersion
        kotlin("plugin.allopen") version kotlinVersion

        // Checkstyle
        id("org.jlleitschuh.gradle.ktlint") version ktlintVersion
        id("com.diffplug.spotless") version spotlessVersion

        // Other
        id("com.avast.gradle.docker-compose") version dockerComposePluginVersion
    }
}

val projectName: String by settings
rootProject.name = projectName