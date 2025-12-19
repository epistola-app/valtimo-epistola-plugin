package app.epistola.valtimo.service;

import app.epistola.valtimo.domain.FileFormat;
import app.epistola.valtimo.domain.GeneratedDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.UUID;

/**
 * Implementation of EpistolaService.
 *
 * Note: Document generation is asynchronous. This service submits a generation request
 * and returns immediately with a request ID. The actual document will be available
 * via a callback when generation is complete.
 *
 * TODO: Implement actual Epistola API client.
 */
@Slf4j
@RequiredArgsConstructor
public class EpistolaServiceImpl implements EpistolaService {

    @Override
    public GeneratedDocument generateDocument(
            String tenantId,
            String templateId,
            Map<String, Object> data,
            FileFormat format,
            String filename
    ) {
        log.info("Submitting document generation request to Epistola: tenantId={}, templateId={}, format={}, filename={}",
                tenantId, templateId, format, filename);
        log.debug("Template data: {}", data);

        // TODO: Implement actual Epistola API call to submit generation request
        // This should return a request/job ID that will be used in the callback
        String requestId = UUID.randomUUID().toString();

        log.info("Document generation request submitted: requestId={}", requestId);

        return GeneratedDocument.builder()
                .documentId(requestId)
                .build();
    }
}
