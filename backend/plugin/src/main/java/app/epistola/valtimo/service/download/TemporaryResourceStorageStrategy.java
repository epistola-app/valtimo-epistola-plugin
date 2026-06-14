package app.epistola.valtimo.service.download;

import app.epistola.valtimo.domain.DocumentStorageTarget;
import com.ritense.resource.service.TemporaryResourceStorageService;
import org.operaton.bpm.engine.delegate.DelegateExecution;

import java.io.ByteArrayInputStream;
import java.util.Map;

/**
 * Stores the PDF in Valtimo's temporary resource storage and writes the resulting resource id to the
 * output variable. The id is small (no task-response leak, no {@code varchar(4000)} limit) and is
 * exactly what {@code documenten-api:store-temp-document} consumes as its {@code localDocumentLocation}
 * to upload the document to the Documenten API.
 */
public class TemporaryResourceStorageStrategy implements DocumentStorageStrategy {

    private final TemporaryResourceStorageService temporaryResourceStorageService;

    public TemporaryResourceStorageStrategy(TemporaryResourceStorageService temporaryResourceStorageService) {
        this.temporaryResourceStorageService = temporaryResourceStorageService;
    }

    @Override
    public DocumentStorageTarget target() {
        return DocumentStorageTarget.TEMPORARY_RESOURCE;
    }

    @Override
    public void store(DelegateExecution execution, String documentId, byte[] content, String outputVariable) {
        Map<String, Object> metadata = Map.of(
                "title", documentId,
                "fileName", documentId + ".pdf",
                "contentType", "application/pdf");
        String resourceId = temporaryResourceStorageService.store(new ByteArrayInputStream(content), metadata);
        execution.setVariable(outputVariable, resourceId);
    }
}
