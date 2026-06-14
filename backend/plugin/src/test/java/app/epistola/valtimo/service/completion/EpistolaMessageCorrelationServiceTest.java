package app.epistola.valtimo.service.completion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the pure job-path helpers. The correlation/update behaviour (which depends on real
 * Operaton execution scoping) is covered end-to-end by
 * {@link EpistolaParallelCorrelationIntegrationTest} against a real engine — mocking the engine here
 * previously gave false confidence (it "passed" while parallel correlation was actually broken).
 */
class EpistolaMessageCorrelationServiceTest {

    @Test
    void buildJobPath_producesCorrectFormat() {
        assertThat(EpistolaMessageCorrelationService.buildJobPath("demo-tenant", "550e8400-e29b-41d4-a716-446655440000"))
                .isEqualTo("epistola:job:demo-tenant/550e8400-e29b-41d4-a716-446655440000");
    }

    @Test
    void parseJobPath_extractsTenantAndRequestId() {
        String[] parts = EpistolaMessageCorrelationService.parseJobPath(
                "epistola:job:demo-tenant/550e8400-e29b-41d4-a716-446655440000");
        assertThat(parts[0]).isEqualTo("demo-tenant");
        assertThat(parts[1]).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
    }

    @Test
    void parseJobPath_roundTripsWithBuildJobPath() {
        String original = EpistolaMessageCorrelationService.buildJobPath("my-tenant", "abc-123");
        String[] parts = EpistolaMessageCorrelationService.parseJobPath(original);
        assertThat(parts[0]).isEqualTo("my-tenant");
        assertThat(parts[1]).isEqualTo("abc-123");
    }

    @Test
    void parseJobPath_rejectsNullInput() {
        assertThatThrownBy(() -> EpistolaMessageCorrelationService.parseJobPath(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseJobPath_rejectsInvalidPrefix() {
        assertThatThrownBy(() -> EpistolaMessageCorrelationService.parseJobPath("invalid:prefix/abc"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseJobPath_rejectsPathWithoutSlash() {
        assertThatThrownBy(() -> EpistolaMessageCorrelationService.parseJobPath("epistola:job:no-slash"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
