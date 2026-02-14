import com.vanniktech.maven.publish.SonatypeHost

plugins {
    `java-library`
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.spring.dependency.management)
}

group = "app.epistola.valtimo"
version = rootProject.version

dependencyManagement {
    imports {
        mavenBom("${libs.valtimo.bom.get()}")
    }
}

dependencies {
    // Epistola client
    api(libs.epistola.client)

    // Valtimo dependencies (compileOnly - provided by implementing application)
    compileOnly(libs.valtimo.core)
    compileOnly(libs.valtimo.contract)
    compileOnly(libs.valtimo.audit)
    compileOnly(libs.valtimo.outbox)
    compileOnly(libs.valtimo.plugin)
    compileOnly(libs.valtimo.value.resolver)
    compileOnly(libs.valtimo.process.link)
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
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.valtimo.test.utils.common)
    testImplementation(libs.junit.jupiter)

    // Testcontainers
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)

    // Test Lombok
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
}

tasks.test {
    // Don't fail if there are no tests yet
    failOnNoDiscoveredTests.set(false)
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
