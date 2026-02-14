plugins {
    // Settings-level plugin - version catalog not available here, keep in sync with libs.versions.toml
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "valtimo-epistola-plugin"

include(
    "backend:plugin",
    "test-app:backend"
)

// Frontend is built via npm, not Gradle subproject
