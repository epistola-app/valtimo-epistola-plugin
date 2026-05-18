import com.vanniktech.maven.publish.SonatypeHost
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven
import org.gradle.plugins.signing.Sign

plugins {
    `java-library`
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.cyclonedx)
}

group = "app.epistola.valtimo"
version = rootProject.version

dependencyManagement {
    imports {
        mavenBom("${libs.valtimo.bom.get()}")
    }
    // Override Valtimo BOM's testcontainers version (1.20.6) with 2.0.3 for Docker Desktop compatibility
    dependencies {
        dependencySet("org.testcontainers:${libs.versions.testcontainers.get()}") {
            entry("testcontainers")
            entry("testcontainers-postgresql")
            entry("testcontainers-junit-jupiter")
            entry("testcontainers-jdbc")
            entry("testcontainers-database-commons")
        }
    }
}

dependencies {
    // Epistola client
    api(libs.epistola.client)

    // JSONata (JSON transformation language)
    api(libs.jsonata)

    // Valtimo dependencies (compileOnly - provided by implementing application)
    compileOnly(libs.valtimo.core)
    compileOnly(libs.valtimo.contract)
    compileOnly(libs.valtimo.audit)
    compileOnly(libs.valtimo.outbox)
    compileOnly(libs.valtimo.plugin)
    compileOnly(libs.valtimo.value.resolver)
    compileOnly(libs.valtimo.process.link)
    compileOnly(libs.valtimo.case)
    compileOnly(libs.valtimo.process.document)
    compileOnly(libs.valtimo.form)
    compileOnly(libs.valtimo.importer)
    compileOnly(libs.spring.boot.starter.aop)
    compileOnly(libs.spring.boot.starter.security)

    // Lombok
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    // Test dependencies
    testImplementation(libs.valtimo.core)
    testImplementation(libs.valtimo.audit)
    testImplementation(libs.valtimo.contract)
    testImplementation(libs.valtimo.plugin)
    testImplementation(libs.valtimo.outbox)
    testImplementation(libs.valtimo.value.resolver)
    testImplementation(libs.valtimo.process.link)
    testImplementation(libs.valtimo.case)
    testImplementation(libs.valtimo.process.document)
    testImplementation(libs.valtimo.form)
    testImplementation(libs.valtimo.importer)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.valtimo.test.utils.common)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Testcontainers
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)

    // Test Lombok
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-parameters")
}

// Populate the jar manifest so EpistolaAdminService.getPluginVersion() can read
// Implementation-Version at runtime. Without this Gradle emits no version
// attribute and the admin page always falls back to "development".
tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "Epistola Valtimo Plugin",
            "Implementation-Version" to project.version,
        )
    }
}

// Bundle the repo CHANGELOG into the plugin jar so the admin page can serve it
// at runtime (classpath: epistola/CHANGELOG.md).
tasks.processResources {
    from(rootProject.file("CHANGELOG.md")) {
        into("epistola")
    }
}

tasks.test {
    // Don't fail if there are no tests yet
    failOnNoDiscoveredTests.set(false)
}

tasks.named<org.cyclonedx.gradle.CycloneDxTask>("cyclonedxBom") {
    outputFormat.set("json")
    outputName.set("bom")
    projectType.set("library")
    includeBomSerialNumber.set(true)
    includeLicenseText.set(false)
    schemaVersion.set("1.5")
}

val sbomFile = layout.buildDirectory.file("reports/bom.json")

afterEvaluate {
    publishing.publications.withType<MavenPublication>().configureEach {
        artifact(sbomFile) {
            classifier = "cyclonedx"
            extension = "json"
            builtBy(tasks.named("cyclonedxBom"))
        }
    }
    tasks.withType<AbstractPublishToMaven>().configureEach {
        dependsOn("cyclonedxBom")
    }
    tasks.withType<Sign>().configureEach {
        dependsOn("cyclonedxBom")
    }
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    signAllPublications()
    coordinates("app.epistola.valtimo", "epistola-plugin", version.toString())

    pom {
        name.set("Epistola Valtimo Plugin")
        description.set("Document generation plugin for Valtimo using Epistola")
        url.set("https://github.com/epistola-app/valtimo-epistola-plugin")
        licenses {
            license {
                name.set("European Union Public Licence 1.2")
                url.set("https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12")
            }
        }
        developers {
            developer {
                id.set("epistola")
                name.set("Epistola")
                url.set("https://epistola.app")
            }
        }
        scm {
            url.set("https://github.com/epistola-app/valtimo-epistola-plugin")
            connection.set("scm:git:git://github.com/epistola-app/valtimo-epistola-plugin.git")
            developerConnection.set("scm:git:ssh://github.com/epistola-app/valtimo-epistola-plugin.git")
        }
    }
}
