/*
 * Copyright 2015-2024 Ritense BV, the Netherlands.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

tasks.register("downloadValtimoDockerCompose") {
    doFirst {
        file(buildDir.absolutePath + "/docker").mkdirs()
        val dockerFile = file(buildDir.absolutePath + "/docker/valtimo-docker-compose.zip")
        java.net.URL("https://codeload.github.com/valtimo-platform/valtimo-docker-compose/zip/refs/heads/v/13")
            .openStream().use { input -> dockerFile.outputStream().use { output -> input.copyTo(output) } }
    }
}

tasks.register<Copy>("downloadAndUnzipValtimoDockerCompose") {
    dependsOn(tasks["downloadValtimoDockerCompose"])
    from(zipTree("${buildDir.absolutePath}/docker/valtimo-docker-compose.zip"))
    into(file("${buildDir.absolutePath}/docker/extract/"))
}

tasks.register("composeUpValtimo") {
    group = "docker"
    dependsOn("downloadAndUnzipValtimoDockerCompose")
    dependsOn("composeUp")
}

tasks["composeBuild"].dependsOn("downloadAndUnzipValtimoDockerCompose")

tasks["composeUp"].mustRunAfter("downloadAndUnzipValtimoDockerCompose")
tasks["composeUp"].dependsOn("downloadAndUnzipValtimoDockerCompose")
