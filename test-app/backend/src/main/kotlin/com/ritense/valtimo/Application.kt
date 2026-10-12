// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

/*
 * Copyright 2015-2022 Ritense BV, the Netherlands.
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

package com.ritense.valtimo

import com.ritense.resource.autoconfigure.LocalResourceAutoConfiguration
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.core.env.Environment
import java.net.InetAddress

/**
 * Excludes Valtimo's local resource module, which the demo replaces.
 *
 * `LocalResourceService.store` throws `NotImplementedError` and `LocalResource`'s download endpoint
 * answers 204, so a case worker clicking a file gets nothing; the demo stores files itself (see
 * `DemoDocumentStorageConfiguration`) and has to keep Valtimo's beans out, because their
 * `@ConditionalOnMissingBean` names their own concrete classes and both would map the same request
 * paths.
 *
 * Here rather than in `spring.autoconfigure.exclude`, where it was first put and silently lost: a
 * profile-specific list *replaces* the base one, so activating `authentik` — which has its own
 * exclusions — brought the placeholder back and the context failed with two `ResourceService`
 * beans. An annotation cannot be dropped by a profile.
 */
@SpringBootApplication(exclude = [LocalResourceAutoConfiguration::class])
class Application

fun main(args: Array<String>) {
    val applicationContext = runApplication<Application>(*args)
    val environment: Environment = applicationContext.environment
    val logger = KotlinLogging.logger {}

    logger.info {
        """

        ----------------------------------------------------------
        |    Application '${environment.getProperty("spring.application.name")}' is running!
        |    Active profile(s): [${environment.getProperty("spring.profiles.active")}].
        |    Local URL: [http://127.0.0.1:${environment.getProperty("server.port")}].
        |    External URL: [http://${InetAddress.getLocalHost().hostAddress}:${environment.getProperty("server.port")}]
        ----------------------------------------------------------
        """.trimIndent()
    }
}