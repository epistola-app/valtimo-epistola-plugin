package com.ritense.valtimo.web.rest

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import javax.sql.DataSource

/**
 * REST endpoint to reset the database for demo purposes.
 *
 * Drops the public schema and recreates it, then shuts down the application.
 * On Kubernetes, the pod restarts automatically and Valtimo/Operaton recreate
 * all tables on boot. Other replicas will crash on their next DB call and
 * restart too, giving eventual consistency.
 *
 * Secured by Valtimo's default security configuration (all API endpoints
 * require authentication).
 */
@RestController
@RequestMapping("/api/v1/test")
class TestResetResource(
    private val dataSource: DataSource
) {
    private val logger = KotlinLogging.logger {}

    @PostMapping("/reset")
    fun resetDatabase(): ResponseEntity<Map<String, String>> {
        logger.warn { "Database reset requested — dropping public schema" }

        val username = dataSource.connection.use { it.metaData.userName }

        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP SCHEMA public CASCADE")
                statement.execute("CREATE SCHEMA public")
                statement.execute("GRANT ALL ON SCHEMA public TO \"$username\"")
                statement.execute("GRANT ALL ON SCHEMA public TO public")
            }
        }

        logger.warn { "Schema reset complete. Scheduling shutdown in 1 second..." }

        Thread({
            Thread.sleep(1000)
            logger.warn { "Shutting down for restart..." }
            System.exit(0)
        }, "reset-shutdown").apply {
            isDaemon = true
            start()
        }

        return ResponseEntity.accepted().body(
            mapOf("status" to "Database reset complete. Application will restart shortly.")
        )
    }
}
