// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import com.vanniktech.maven.publish.SonatypeHost
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven
import org.gradle.plugins.signing.Sign

plugins {
    `java-library`
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.cyclonedx)
    alias(libs.plugins.spotless)
    alias(libs.plugins.shadow)
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

// json-schema-validator is shaded: bundled into the plugin jar under a relocated package, and
// left out of the published POM, so the plugin never forces a version on the host application.
// Valtimo 13.47 added `external-plugin` to `valtimo-dependencies`, which needs the 1.x API
// (`ValidationMessage`), while this plugin is written against 2.x (`SchemaRegistry`, `Error`).
// Both share the `com.networknt.schema` package, so only one can sit on the classpath unshaded,
// and Gradle's highest-version-wins resolution broke Valtimo's startup.
//
// Keep the relocated types internal: never put them in a public or protected signature, or the
// shaded package becomes plugin API and dropping the shading a breaking change.
//
// TODO: drop the shading (back to a plain `implementation` dependency) once the Valtimo floor in
//  COMPATIBILITY.md ships a json-schema-validator whose API matches ours (2.x or later), or no
//  longer ships it at all. Check with:
//  ./gradlew :test-app:backend:dependencyInsight --dependency json-schema-validator --configuration runtimeClasspath
val shaded: Configuration by configurations.creating
configurations {
    compileOnly { extendsFrom(shaded) }
    testImplementation { extendsFrom(shaded) }
}

dependencies {
    // Epistola client
    api(libs.epistola.client)

    // JSONata (JSON transformation language)
    api(libs.jsonata)

    // Validate custom-function result schemas against bundled JSON Schema meta-schemas.
    // Shaded (relocated into the plugin jar), not a regular dependency — see `shaded` below.
    shaded(libs.json.schema.validator)
    // Left unshaded: a stable Jackson module whose version the host's Spring Boot BOM manages.
    implementation(libs.jackson.dataformat.yaml)

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
    compileOnly(libs.valtimo.temporary.resource.storage)
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
    testImplementation(libs.valtimo.temporary.resource.storage)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.valtimo.test.utils.common)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // In-memory DB for the standalone Operaton engine used by the correlation integration test
    testImplementation("com.h2database:h2")

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

// EUPL-1.2 license headers, applied with `spotlessApply` and gated by `spotlessCheck`
// (which `build` already depends on via `check`). Shares the canonical header text with
// the frontend (config/license-header.txt) so the two never diverge. The two
// Ritense-attributed, Valtimo-derived test files keep their original copyright notice.
spotless {
    java {
        target("src/**/*.java")
        targetExclude(
            "src/test/java/app/epistola/valtimo/BaseIntegrationTest.java",
            "src/test/java/app/epistola/valtimo/PostgresTestContainerConfig.java",
        )
        licenseHeaderFile(rootProject.file("config/license-header.txt"))
    }
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

shadow {
    // The regular apiElements/runtimeElements already carry the shaded jar (wired below).
    addShadowVariantIntoJavaComponent.set(false)
}

tasks.shadowJar {
    // Replaces the plain jar as the published / consumed artifact (see the outgoing wiring below).
    archiveClassifier.set("")
    configurations = listOf(shaded)
    dependencies {
        // Bundle only the validator and its date-time library; Jackson and SnakeYAML come from the host.
        exclude(dependency("com.fasterxml.jackson.core:.*"))
        exclude(dependency("com.fasterxml.jackson.dataformat:.*"))
        exclude(dependency("org.yaml:.*"))
    }
    val prefix = "app.epistola.valtimo.shaded"
    relocate("com.networknt", "$prefix.networknt")
    relocate("com.ethlo.time", "$prefix.ethlo.time")
    // The validator's message bundle sits at the jar root under a name the host's copy shares, and
    // whichever comes first on the classpath wins. 1.x messages carry a "{0}: " prefix 2.x does
    // not expect and lack 2.x-only keys, so relocate ours to be found regardless of order.
    // (Its meta-schemas and Unicode tables also sit at the root, but are standard, versioned
    // spec files that are identical in both copies, so sharing them is harmless.)
    relocate("jsv-messages", "epistola-shaded-jsv-messages")
    exclude("META-INF/maven/**", "META-INF/native-image/**")
    manifest {
        attributes(
            "Implementation-Title" to "Epistola Valtimo Plugin",
            "Implementation-Version" to project.version,
        )
    }
}

tasks.jar {
    // Keep the unshaded jar out of the way of the shaded one, which takes the plain file name.
    archiveClassifier.set("plain")
}

// Publish and hand project consumers (the test-app) the shaded jar instead of the plain one.
listOf(configurations.apiElements, configurations.runtimeElements).forEach { elements ->
    elements.configure {
        outgoing.artifacts.clear()
        outgoing.artifact(tasks.shadowJar)
        // Drop the classes-dir secondary variants: they would expose the unrelocated bytecode.
        outgoing.variants.clear()
    }
}

// Bundle the repo CHANGELOG into the plugin jar so the admin page can serve it
// at runtime (classpath: epistola/CHANGELOG.md).
tasks.processResources {
    from(rootProject.file("CHANGELOG.md")) {
        into("epistola")
    }
}

tasks.processTestResources {
    from(rootProject.file("test-fixtures")) {
        into("compatibility-fixtures")
    }
}

tasks.test {
    // Don't fail if there are no tests yet
    failOnNoDiscoveredTests.set(false)
}

// The contract client has to keep reading servers older than the contract it was generated from.
// Contract 1.3.0's client could not: it made a response field that older servers never send
// required. So the mock-server integration test also runs against the oldest contract a supported
// Epistola Suite serves — Suite 1.0.0 serves contract 0.16.1 (COMPATIBILITY.md). Raise this with
// the Suite floor. Part of `check`, so `build` runs it; `test` alone does not.
val oldestSupportedServerTest by tasks.registering(Test::class) {
    description = "Runs the mock-server integration test against the oldest supported Epistola contract."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("app.epistola.valtimo.service.EpistolaServiceImplTest") }
    systemProperty("epistola.mock-server.version", "0.16.1")
}

tasks.check {
    dependsOn(oldestSupportedServerTest)
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
