package app.epistola.valtimo.config;

import app.epistola.valtimo.deployment.EpistolaCatchEventLinkResolver;
import app.epistola.valtimo.deployment.EpistolaCatchEventParseListener;
import app.epistola.valtimo.deployment.EpistolaProcessEnginePlugin;
import app.epistola.valtimo.service.completion.EpistolaCatchEventStartListener;
import app.epistola.valtimo.service.completion.EpistolaMessageCorrelationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the conditional registration of the catch-event auto-wiring beans:
 * <ul>
 *   <li>{@code epistola.enabled=false} → none of the catch-event/correlation beans exist at all
 *       (the user's "if disabled, do none of these operations" requirement);</li>
 *   <li>default → they're all registered;</li>
 *   <li>{@code epistola.catch-event-auto-wiring.enabled=false} → the engine-SPI beans (parse listener +
 *       process-engine plugin) drop out, but correlation and the start listener remain so declarative
 *       {@code epistolaWaitFor} mappings still work.</li>
 * </ul>
 *
 * <p>The full autoconfig has a large collaborator graph, so the context is made lazy
 * ({@link LazyInitializationBeanFactoryPostProcessor}) — conditions are still evaluated at definition
 * time, but beans are never instantiated, so no mock dependencies are needed and bean presence is
 * asserted purely from the registered definitions.
 */
class EpistolaCatchEventAutoWiringConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(EpistolaPluginAutoConfiguration.class))
            .withBean(LazyInitializationBeanFactoryPostProcessor.class, LazyInitializationBeanFactoryPostProcessor::new);

    @Test
    void enabledByDefault_registersAllCatchEventBeans() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(EpistolaCatchEventParseListener.class);
            assertThat(context).hasSingleBean(EpistolaProcessEnginePlugin.class);
            assertThat(context).hasSingleBean(EpistolaCatchEventStartListener.class);
            assertThat(context).hasSingleBean(EpistolaCatchEventLinkResolver.class);
            assertThat(context).hasSingleBean(EpistolaMessageCorrelationService.class);
        });
    }

    @Test
    void disabled_registersNoneOfTheCatchEventOrCorrelationBeans() {
        runner.withPropertyValues("epistola.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(EpistolaCatchEventParseListener.class);
            assertThat(context).doesNotHaveBean(EpistolaProcessEnginePlugin.class);
            assertThat(context).doesNotHaveBean(EpistolaCatchEventStartListener.class);
            assertThat(context).doesNotHaveBean(EpistolaCatchEventLinkResolver.class);
            assertThat(context).doesNotHaveBean(EpistolaMessageCorrelationService.class);
        });
    }

    @Test
    void autoWiringDisabled_dropsTheEngineSpiBeansButKeepsCorrelation() {
        runner.withPropertyValues("epistola.catch-event-auto-wiring.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(EpistolaCatchEventParseListener.class);
            assertThat(context).doesNotHaveBean(EpistolaProcessEnginePlugin.class);
            // Correlation + the start listener stay so declarative epistolaWaitFor mappings still work.
            assertThat(context).hasSingleBean(EpistolaMessageCorrelationService.class);
            assertThat(context).hasSingleBean(EpistolaCatchEventStartListener.class);
        });
    }
}
