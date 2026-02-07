plugins {
    `java-library`
    id("maven-publish")
    id("io.spring.dependency-management")
}

group = "app.epistola"
version = rootProject.version

val valtimoVersion: String by rootProject.extra
val lombokVersion: String by rootProject.extra
val testcontainersVersion: String by rootProject.extra

dependencyManagement {
    imports {
        mavenBom("com.ritense.valtimo:valtimo-dependency-versions:$valtimoVersion")
    }
}

dependencies {
    // Epistola client
    api(libs.epistola.client)

    // Valtimo dependencies (compileOnly - provided by implementing application)
    compileOnly("com.ritense.valtimo:core")
    compileOnly("com.ritense.valtimo:contract")
    compileOnly("com.ritense.valtimo:audit")
    compileOnly("com.ritense.valtimo:outbox")
    compileOnly("com.ritense.valtimo:plugin")
    compileOnly("com.ritense.valtimo:value-resolver")
    compileOnly("com.ritense.valtimo:process-link")
    compileOnly("org.springframework.boot:spring-boot-starter-aop")
    compileOnly("org.springframework.boot:spring-boot-starter-security")

    // Lombok
    compileOnly("org.projectlombok:lombok:$lombokVersion")
    annotationProcessor("org.projectlombok:lombok:$lombokVersion")

    // Test dependencies
    testImplementation("com.ritense.valtimo:core")
    testImplementation("com.ritense.valtimo:audit")
    testImplementation("com.ritense.valtimo:contract")
    testImplementation("com.ritense.valtimo:plugin")
    testImplementation("com.ritense.valtimo:outbox")
    testImplementation("com.ritense.valtimo:value-resolver")
    testImplementation("com.ritense.valtimo:process-link")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.ritense.valtimo:test-utils-common")
    testImplementation("org.junit.jupiter:junit-jupiter")

    // Testcontainers
    testImplementation(platform("org.testcontainers:testcontainers-bom:$testcontainersVersion"))
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")

    // WireMock for HTTP mocking
    testImplementation("org.wiremock:wiremock-standalone:3.10.0")

    // Test Lombok
    testCompileOnly("org.projectlombok:lombok:$lombokVersion")
    testAnnotationProcessor("org.projectlombok:lombok:$lombokVersion")
}

java {
    withJavadocJar()
    withSourcesJar()
}

tasks.test {
    // Don't fail if there are no tests yet
    failOnNoDiscoveredTests.set(false)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = project.group.toString()
            artifactId = "epistola-plugin"
            version = project.version.toString()

            pom {
                name.set("Epistola Plugin")
                description.set("Document generation plugin for Valtimo using Epistola")
            }
        }
    }
}
