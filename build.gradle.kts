import com.github.gradle.node.npm.task.NpmTask

plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    alias(libs.plugins.node.gradle)
}

group = "app.epistola"
version = (findProperty("version") as String?) ?: "0.2.0-SNAPSHOT"

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
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/epistola-app/epistola-contract")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: providers.gradleProperty("gpr.user").getOrElse("")
                password = System.getenv("GITHUB_TOKEN") ?: providers.gradleProperty("gpr.key").getOrElse("")
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

// --- Compatibility declaration (machine-readable peer feed) --------------------
// The plugin is a *client* of the `epistola-contract` wire anchor. It publishes a
// small machine-readable declaration of the contract version it targets, so the
// version-compatibility matrix can read the external (plugin) side from a feed
// instead of parsing COMPATIBILITY.md by hand (peer participation).
//
//   - targetContractVersion is DERIVED from the epistola-client dependency
//     (client-spring3-restclient IS that contract version) — never hand-authored.
//   - operations lists every Epistola API call the plugin makes (by the
//     contract's operationId), so the matrix can judge compatibility at the
//     operation level: a contract release that breaks only calls the plugin
//     never makes does not make the plugin incompatible. The list is
//     hand-maintained here and verified against the source code (see below).
//   - version is the plugin release, stamped via -Pversion at release time.
//
// compatibility.json is committed and kept honest by verifyCompatibilityDeclaration
// (CI), which compares the derivable fields (version is excluded — release-stamped).
val compatibilityDeclarationFile = layout.projectDirectory.file("compatibility.json")

// Operations called through the generated *Api clients. verifyCompatibilityDeclaration
// scans the plugin sources BOTH ways: an operation listed here but never called fails,
// and an *Api call in the code that is not listed here fails.
val generatedClientOperations = setOf(
    "generateDocument",
    "getGenerationJobStatus",
    "getTemplate",
    "listAttributes",
    "listCatalogs",
    "listEnvironments",
    "listTemplates",
    "listVariants",
)

// Operations called with a hand-built RestClient request (binary / multipart
// responses the generated client doesn't fit) or through the contract's
// ResultCollector library. No method-call scan is possible for these, so each
// maps to a source marker that must be present (a weaker, presence-only check).
val directHttpOperations = mapOf(
    "collectGenerationResults" to "ResultCollector",
    "downloadDocument" to "/documents/",
    "importCatalog" to "/catalogs/import",
    "previewDocument" to "/documents/preview",
)

val declaredOperations = (generatedClientOperations + directHttpOperations.keys).sorted()

fun readContractClientVersion(): String {
    val toml = file("gradle/libs.versions.toml").readText()
    return Regex("""(?m)^epistola-client\s*=\s*"([^"]+)"""").find(toml)?.groupValues?.get(1)
        ?: throw GradleException("could not read epistola-client from gradle/libs.versions.toml")
}

fun pluginMainSources(): List<File> =
    fileTree("backend/plugin/src/main") { include("**/*.java", "**/*.kt") }.files.sortedBy { it.path }

// Every generated-client call in the sources, by operation name. Matches both
// call shapes used in the plugin: `templatesApi.listTemplates(` (a field/local
// whose name ends in Api) and `createCatalogsApi(...).listCatalogs(` (fluent
// off the factory).
fun scanGeneratedClientCalls(): Set<String> {
    val direct = Regex("""\b\w+Api\s*\.\s*(\w+)\(""")
    val fluent = Regex("""create\w+Api\([^)]*\)\s*\.\s*(\w+)\(""")
    return pluginMainSources().flatMap { f ->
        val text = f.readText()
        (direct.findAll(text) + fluent.findAll(text)).map { it.groupValues[1] }
    }.toSet()
}

fun renderCompatibilityDeclaration(pluginVersion: String, contractVersion: String): String {
    val ops = declaredOperations.joinToString(",\n") { "    \"$it\"" }
    return """
    {
      "schemaVersion": 1,
      "artifact": "valtimo-epistola-plugin",
      "anchor": "epistola-contract",
      "role": "client",
      "version": "$pluginVersion",
      "targetContractVersion": "$contractVersion",
      "operations": [
    __OPS__
      ]
    }
    """.trimIndent().replace("__OPS__", ops) + "\n"
}

tasks.register("generateCompatibilityDeclaration") {
    group = "epistola"
    description = "Writes compatibility.json — the plugin's machine-readable compatibility declaration."
    val out = compatibilityDeclarationFile
    val projectVersion = version.toString()
    outputs.file(out)
    doLast {
        // The version field is release-stamped (-Pversion). A local regeneration
        // without it keeps the version already in the committed file instead of
        // overwriting it with Gradle's "unspecified".
        val pluginVersion = projectVersion.takeIf { it != "unspecified" }
            ?: out.asFile.takeIf { it.exists() }?.let { f ->
                @Suppress("UNCHECKED_CAST")
                (groovy.json.JsonSlurper().parse(f) as Map<String, Any?>)["version"] as? String
            }
            ?: "unspecified"
        val contract = readContractClientVersion()
        out.asFile.writeText(renderCompatibilityDeclaration(pluginVersion, contract))
        logger.lifecycle("Wrote ${out.asFile.relativeTo(projectDir)} (version=$pluginVersion, targetContractVersion=$contract)")
    }
}

tasks.register("verifyCompatibilityDeclaration") {
    group = "verification"
    description = "Fails if compatibility.json drifts from the build-derived values (version excluded — release-stamped)."
    doLast {
        val declaration = compatibilityDeclarationFile.asFile
        if (!declaration.exists()) {
            throw GradleException("compatibility.json is missing — run ./gradlew generateCompatibilityDeclaration and commit it")
        }
        @Suppress("UNCHECKED_CAST")
        val json = groovy.json.JsonSlurper().parse(declaration) as Map<String, Any?>
        val expectedContract = readContractClientVersion()
        val problems = buildList {
            if (json["schemaVersion"] != 1) add("schemaVersion (expected 1, found ${json["schemaVersion"]})")
            if (json["artifact"] != "valtimo-epistola-plugin") add("artifact")
            if (json["anchor"] != "epistola-contract") add("anchor")
            if (json["role"] != "client") add("role")
            if (json["targetContractVersion"] != expectedContract) {
                add("targetContractVersion (expected $expectedContract, found ${json["targetContractVersion"]})")
            }
            if (json["operations"] != declaredOperations) {
                add("operations (expected $declaredOperations, found ${json["operations"]})")
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "compatibility.json is out of date (${problems.joinToString()}). " +
                    "Run ./gradlew generateCompatibilityDeclaration and commit the result.",
            )
        }

        // The declared operations must match the code. Generated-client calls are
        // scanned both ways; direct-HTTP operations are presence-checked by marker.
        val calls = scanGeneratedClientCalls()
        val undeclared = calls - generatedClientOperations
        val unused = generatedClientOperations - calls
        if (undeclared.isNotEmpty()) {
            throw GradleException(
                "The code calls Epistola API operation(s) not declared in compatibility.json: $undeclared. " +
                    "Add them to generatedClientOperations in build.gradle.kts, run " +
                    "./gradlew generateCompatibilityDeclaration and commit the result.",
            )
        }
        if (unused.isNotEmpty()) {
            throw GradleException(
                "compatibility.json declares operation(s) the code no longer calls: $unused. " +
                    "Remove them from generatedClientOperations in build.gradle.kts, run " +
                    "./gradlew generateCompatibilityDeclaration and commit the result.",
            )
        }
        val sourceText = pluginMainSources().joinToString("\n") { it.readText() }
        val missingMarkers = directHttpOperations.filterValues { marker -> !sourceText.contains(marker) }.keys
        if (missingMarkers.isNotEmpty()) {
            throw GradleException(
                "Declared direct-HTTP operation(s) have no trace in the code: $missingMarkers. " +
                    "Remove them from directHttpOperations in build.gradle.kts, run " +
                    "./gradlew generateCompatibilityDeclaration and commit the result.",
            )
        }
        logger.lifecycle(
            "compatibility.json OK (targetContractVersion=$expectedContract, ${declaredOperations.size} operations verified against the source)",
        )
    }
}
