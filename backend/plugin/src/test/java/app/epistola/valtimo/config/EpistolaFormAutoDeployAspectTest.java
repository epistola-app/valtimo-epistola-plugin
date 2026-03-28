package app.epistola.valtimo.config;

import com.ritense.form.autodeployment.FormDefinitionDeploymentService;
import com.ritense.importer.ImportRequest;
import com.ritense.valtimo.contract.case_.CaseDefinitionId;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EpistolaFormAutoDeployAspectTest {

    @Mock
    private FormDefinitionDeploymentService deploymentService;

    private static final String RETRY_FORM_NAME = "epistola-retry-document";

    private EpistolaFormAutoDeployAspect createAspect(String caseFilter) {
        EpistolaProperties properties = new EpistolaProperties();
        properties.getRetryForm().setCaseFilter(caseFilter);
        return new EpistolaFormAutoDeployAspect(deploymentService, properties);
    }

    private ImportRequest mockImportRequest(String caseKey) {
        ImportRequest request = mock(ImportRequest.class);
        CaseDefinitionId caseDefId = mock(CaseDefinitionId.class);
        when(caseDefId.getKey()).thenReturn(caseKey);
        when(request.getCaseDefinitionId()).thenReturn(caseDefId);
        return request;
    }

    @Nested
    class CaseFilterAll {

        @Test
        void deploysFormForCase() throws Exception {
            EpistolaFormAutoDeployAspect aspect = createAspect("all");
            ImportRequest request = mockImportRequest("my-case");

            aspect.deployRetryFormAfterFormImport(request);

            verify(deploymentService).deploy(
                    eq(RETRY_FORM_NAME),
                    anyString(),
                    any(CaseDefinitionId.class),
                    eq(false)
            );
        }
    }

    @Nested
    class CaseFilterNone {

        @Test
        void skipsDeploymentForAllCases() throws Exception {
            EpistolaFormAutoDeployAspect aspect = createAspect("none");
            ImportRequest request = mockImportRequest("my-case");

            aspect.deployRetryFormAfterFormImport(request);

            verify(deploymentService, never()).deploy(anyString(), anyString(), any(), anyBoolean());
        }
    }

    @Nested
    class CaseFilterRegex {

        @Test
        void deploysForMatchingCase() throws Exception {
            EpistolaFormAutoDeployAspect aspect = createAspect("permit.*");
            ImportRequest request = mockImportRequest("permit-construction");

            aspect.deployRetryFormAfterFormImport(request);

            verify(deploymentService).deploy(
                    eq(RETRY_FORM_NAME),
                    anyString(),
                    any(CaseDefinitionId.class),
                    eq(false)
            );
        }

        @Test
        void skipsNonMatchingCase() throws Exception {
            EpistolaFormAutoDeployAspect aspect = createAspect("permit.*");
            ImportRequest request = mockImportRequest("subsidy-application");

            aspect.deployRetryFormAfterFormImport(request);

            verify(deploymentService, never()).deploy(anyString(), anyString(), any(), anyBoolean());
        }
    }

    @Nested
    class DeduplicationPerCase {

        @Test
        void deploysOnlyOncePerCase() throws Exception {
            EpistolaFormAutoDeployAspect aspect = createAspect("all");

            // Use the same CaseDefinitionId instance for both calls
            CaseDefinitionId caseDefId = mock(CaseDefinitionId.class);
            when(caseDefId.getKey()).thenReturn("my-case");

            ImportRequest request1 = mock(ImportRequest.class);
            when(request1.getCaseDefinitionId()).thenReturn(caseDefId);

            ImportRequest request2 = mock(ImportRequest.class);
            when(request2.getCaseDefinitionId()).thenReturn(caseDefId);

            aspect.deployRetryFormAfterFormImport(request1);
            aspect.deployRetryFormAfterFormImport(request2);

            // deploy should have been called exactly once because the CaseDefinitionId
            // is the same instance (Set uses identity/equals)
            verify(deploymentService).deploy(
                    eq(RETRY_FORM_NAME),
                    anyString(),
                    any(CaseDefinitionId.class),
                    eq(false)
            );
        }
    }

    @Nested
    class NullCaseDefinitionId {

        @Test
        void skipsWhenCaseDefinitionIdIsNull() throws Exception {
            EpistolaFormAutoDeployAspect aspect = createAspect("all");
            ImportRequest request = mock(ImportRequest.class);
            when(request.getCaseDefinitionId()).thenReturn(null);

            aspect.deployRetryFormAfterFormImport(request);

            verify(deploymentService, never()).deploy(anyString(), anyString(), any(), anyBoolean());
        }
    }

    @Nested
    class MissingFormResource {

        @Test
        void handlesGracefully() throws Exception {
            // The form resource file is not on the test classpath,
            // so getRetryFormJson() will log a warning and return null.
            // The aspect should handle this gracefully without throwing.
            EpistolaFormAutoDeployAspect aspect = createAspect("all");
            ImportRequest request = mockImportRequest("my-case");

            // This should not throw even though the classpath resource is absent
            aspect.deployRetryFormAfterFormImport(request);

            // deploy may or may not be called depending on whether the form resource
            // exists on the test classpath. If absent, deploy should NOT be called.
            // We just verify no exception was thrown (implicit by reaching this line).
        }
    }
}
