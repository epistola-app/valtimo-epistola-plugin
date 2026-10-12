// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2
package com.ritense.valtimo.epistola.documents

import com.ritense.resource.service.ResourceService
import com.ritense.resource.web.rest.ResourceResource
import com.ritense.valtimo.contract.config.LiquibaseMasterChangeLogLocation
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

/**
 * Durable document storage for the demo.
 *
 * Three beans, each replacing or extending a Valtimo seam rather than working around one:
 *
 *  - [LiquibaseMasterChangeLogLocation] — Valtimo's `LiquibaseRunner` runs one Liquibase per
 *    registered location, so this schema is **its own line**. Valtimo's changelogs are separate
 *    runs; nothing here includes or edits them, and these changesets are identified by their own
 *    path, so neither can disturb the other.
 *  - [ResourceService] — replaces `LocalResourceService`, whose `store` throws
 *    `NotImplementedError`.
 *  - [ResourceResource] — replaces `LocalResource`, whose download endpoint answers 204.
 *
 * Both Valtimo beans are `@ConditionalOnMissingBean`, so these take over simply by existing: a
 * user `@Configuration` is processed before auto-configuration, so Valtimo's placeholders back off.
 *
 * These are declared unconditionally on purpose. `@ConditionalOnMissingBean` is only dependable
 * inside auto-configuration — in a user configuration it is evaluated before auto-configured beans
 * are even known, so it would read as a safeguard while guaranteeing nothing. The demo always wants
 * its own storage; a real installation that configures the Documenten API or S3 does not run this
 * configuration at all.
 */
@Configuration
@EntityScan(basePackageClasses = [StoredDocument::class])
@EnableJpaRepositories(basePackageClasses = [StoredDocumentRepository::class])
class DemoDocumentStorageConfiguration {
    @Bean
    fun epistolaDemoChangeLogLocation(): LiquibaseMasterChangeLogLocation =
        LiquibaseMasterChangeLogLocation("config/liquibase/epistola-demo-master.xml")

    @Bean
    fun demoResourceService(
        repository: StoredDocumentRepository,
        @Value("\${valtimo.app.scheme:http}") scheme: String,
        @Value("\${valtimo.app.hostname:localhost}") hostname: String,
    ): ResourceService = DemoResourceService(repository, "$scheme://$hostname")

    @Bean
    fun demoResourceResource(resourceService: ResourceService): ResourceResource = DemoResourceResource(resourceService)

    /**
     * Valtimo's own resource security does not permit the `/download` path its own case UI calls, so
     * without this a stored letter answers 403. See [DemoResourceSecurityConfigurer].
     */
    @Bean
    fun demoResourceSecurityConfigurer(): DemoResourceSecurityConfigurer = DemoResourceSecurityConfigurer()
}