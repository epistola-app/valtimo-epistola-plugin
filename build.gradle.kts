plugins {
    `java-library`
    id("maven-publish")
}

group = "app.epistola.valtimo"
version = "1.0-SNAPSHOT"

java {
}

repositories {
    mavenCentral()
}

val valtimoVersion = "13.4.1.RELEASE"
val lombokVersion = "1.18.42"
val testcontainersVersion = "1.20.4"

dependencies {
    // Import BOMs for dependency management -
    // Note: enforcedPlatform ensures that projects depending on this library does not result into including unnecessary transistive dependencies
    implementation(enforcedPlatform("com.ritense.valtimo:valtimo-dependency-versions:$valtimoVersion"))

    implementation("org.springframework.boot:spring-boot-starter-aop")

    // Valtimo dependencies
    // Using compileOnly for 'provided' scope equivalent
    compileOnly("com.ritense.valtimo:core")
    compileOnly("com.ritense.valtimo:contract")
    compileOnly("com.ritense.valtimo:audit")
    compileOnly("com.ritense.valtimo:outbox")
    compileOnly("org.springframework.boot:spring-boot-starter-aop")
    compileOnly("com.ritense.valtimo:plugin")
    compileOnly("com.ritense.valtimo:value-resolver")
    compileOnly("com.ritense.valtimo:process-link")

    testImplementation("com.ritense.valtimo:core")
    testImplementation("com.ritense.valtimo:audit")
    testImplementation("com.ritense.valtimo:contract")
    testImplementation("com.ritense.valtimo:plugin")
    testImplementation("com.ritense.valtimo:outbox")
    testImplementation("com.ritense.valtimo:value-resolver")
    testImplementation("com.ritense.valtimo:process-link")

    // Test dependencies
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.ritense.valtimo:test-utils-common")
    testImplementation("org.junit.jupiter:junit-jupiter")

    // Testcontainers
    testImplementation(platform("org.testcontainers:testcontainers-bom:$testcontainersVersion"))
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")

    // Lombok
    compileOnly("org.projectlombok:lombok:$lombokVersion")
    annotationProcessor("org.projectlombok:lombok:$lombokVersion")
    testCompileOnly("org.projectlombok:lombok:$lombokVersion")
    testAnnotationProcessor("org.projectlombok:lombok:$lombokVersion")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = project.group.toString()
            artifactId = project.name
            version = project.version.toString()
        }
    }
}