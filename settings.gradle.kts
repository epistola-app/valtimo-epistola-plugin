pluginManagement {
    plugins {
        kotlin("jvm") version "2.2.20"
        id("org.springframework.boot") version "3.4.1"
        id("io.spring.dependency-management") version "1.1.7"
        id("com.github.node-gradle.node") version "7.1.0"
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