package app.epistola.valtimo.config;

import app.epistola.valtimo.service.download.DocumentStorageStrategy;
import app.epistola.valtimo.service.download.ProcessVariableStorageStrategy;
import app.epistola.valtimo.service.download.TemporaryResourceStorageStrategy;
import com.ritense.resource.service.TemporaryResourceStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Verifies the conditional wiring of the {@code download-document} storage strategies — the
 * "no hard dependency" guarantee from {@code docs/adr/0001-download-document-content-storage.md}:
 * the inline strategy is always available, the temporary-resource strategy only when its backend
 * (the {@code TemporaryResourceStorageService}) is on the context.
 */
class EpistolaDownloadStorageConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(EpistolaDownloadStorageConfiguration.class));

    @Test
    void processVariableStrategyIsAlwaysAvailable_temporaryResourceIsNotWithoutItsBackend() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ProcessVariableStorageStrategy.class);
            assertThat(context).doesNotHaveBean(TemporaryResourceStorageStrategy.class);
            // Only the inline strategy is registered when temporary storage is absent.
            assertThat(context.getBeansOfType(DocumentStorageStrategy.class)).hasSize(1);
        });
    }

    @Test
    void temporaryResourceStrategyIsRegisteredWhenItsBackendIsPresent() {
        // The backend is supplied via a user configuration (processed before auto-configurations),
        // so @ConditionalOnBean on the strategy resolves it reliably.
        runner.withUserConfiguration(TemporaryResourceStorageServiceConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(TemporaryResourceStorageStrategy.class);
                    assertThat(context).hasSingleBean(ProcessVariableStorageStrategy.class);
                    assertThat(context.getBeansOfType(DocumentStorageStrategy.class)).hasSize(2);
                });
    }

    @Configuration
    static class TemporaryResourceStorageServiceConfig {
        @Bean
        TemporaryResourceStorageService temporaryResourceStorageService() {
            return mock(TemporaryResourceStorageService.class);
        }
    }
}
