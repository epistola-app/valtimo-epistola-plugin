package com.ritense.valtimo.epistola.plugin;

import app.epistola.valtimo.mapping.JsonataMappingService;
import app.epistola.valtimo.service.EpistolaService;
import app.epistola.valtimo.service.completion.EpistolaResultCollectorRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ritense.document.service.DocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.variable.value.BytesValue;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EpistolaPlugin#downloadDocument} storage behaviour.
 *
 * <p>Regression guard for two failures:
 * <ul>
 *   <li>The {@code varchar(4000)} overflow — the action must store the document as a byte-array
 *       backed Operaton variable, never as a String. A Base64 String of any real document overflows
 *       Operaton's variable column and rolls back the surrounding transaction (e.g. the message
 *       correlation that resumed the process), leaving the intermediate catch event uncompleted.</li>
 *   <li>The task-open serialization error — a {@code FileValue} exposes its value as a
 *       {@code ByteArrayInputStream} that Jackson cannot serialize when Valtimo renders the user
 *       task's variables. A {@code byte[]} ({@link BytesValue}) serializes cleanly as Base64.</li>
 * </ul>
 */
class EpistolaPluginDownloadDocumentTest {

    private static final String BASE_URL = "https://api.epistola.app";
    private static final String API_KEY = "api-key";
    private static final String TENANT_ID = "demo";
    private static final String DOCUMENT_VARIABLE = "epistolaResult";
    private static final String CONTENT_VARIABLE = "documentContent";
    private static final String DOCUMENT_ID = "doc-123";

    private EpistolaService epistolaService;
    private EpistolaPlugin plugin;
    private DelegateExecution execution;

    @BeforeEach
    void setUp() {
        epistolaService = mock(EpistolaService.class);
        plugin = new EpistolaPlugin(
                epistolaService,
                mock(ObjectMapper.class),
                mock(JsonataMappingService.class),
                mock(DocumentService.class),
                mock(EpistolaResultCollectorRunner.class));
        ReflectionTestUtils.setField(plugin, "baseUrl", BASE_URL);
        ReflectionTestUtils.setField(plugin, "apiKey", API_KEY);
        ReflectionTestUtils.setField(plugin, "tenantId", TENANT_ID);

        execution = mock(DelegateExecution.class);
        when(execution.getVariable(DOCUMENT_VARIABLE)).thenReturn(DOCUMENT_ID);
    }

    @Test
    void downloadDocument_storesPdfBytesAsByteVariableNotString() {
        byte[] pdf = new byte[]{0x25, 0x50, 0x44, 0x46, 0x2d}; // %PDF-
        when(epistolaService.downloadDocument(BASE_URL, API_KEY, TENANT_ID, DOCUMENT_ID)).thenReturn(pdf);

        plugin.downloadDocument(execution, DOCUMENT_VARIABLE, CONTENT_VARIABLE);

        assertThat(captureStoredValue().getValue()).isEqualTo(pdf);
    }

    @Test
    void downloadDocument_handlesDocumentLargerThanVarcharLimitWithoutThrowing() {
        // 8 KB of bytes — its Base64 form (~10.9 KB) would blow past Operaton's varchar(4000)
        // string-variable column. As a byte variable it is stored in the byte-array table instead.
        byte[] largePdf = new byte[8192];
        for (int i = 0; i < largePdf.length; i++) {
            largePdf[i] = (byte) (i % 256);
        }
        when(epistolaService.downloadDocument(BASE_URL, API_KEY, TENANT_ID, DOCUMENT_ID)).thenReturn(largePdf);

        assertThatCode(() -> plugin.downloadDocument(execution, DOCUMENT_VARIABLE, CONTENT_VARIABLE))
                .doesNotThrowAnyException();

        assertThat(captureStoredValue().getValue()).isEqualTo(largePdf);
    }

    private BytesValue captureStoredValue() {
        ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);
        verify(execution).setVariable(eq(CONTENT_VARIABLE), valueCaptor.capture());
        assertThat(valueCaptor.getValue())
                .as("download-document must store a byte variable, not a String")
                .isInstanceOf(BytesValue.class);
        return (BytesValue) valueCaptor.getValue();
    }
}
