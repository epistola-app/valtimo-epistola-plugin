pluginManagement {
    plugins {
        kotlin("jvm") version "2.0.21"
        kotlin("plugin.spring") version "2.0.21"
        kotlin("plugin.jpa") version "2.0.21"
        kotlin("plugin.allopen") version "2.0.21"
        id("org.springframework.boot") version "3.4.1"
        id("io.spring.dependency-management") version "1.1.7"
        id("com.github.node-gradle.node") version "7.1.0"
        id("org.jetbrains.gradle.plugin.idea-ext") version "1.1.8"
        id("org.jlleitschuh.gradle.ktlint") version "12.1.2"
        id("com.diffplug.spotless") version "6.25.0"
        id("com.avast.gradle.docker-compose") version "0.17.12"
        id("com.vanniktech.maven.publish") version "0.30.0"
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
rootProject.name = "valtimo-epistola-plugin"

include(
    "backend:plugin",
    "test-app:backend"
)

// Frontend is built via npm, not Gradle subproject