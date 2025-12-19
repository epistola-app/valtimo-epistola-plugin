package app.epistola.valtimo.service;

import app.epistola.valtimo.domain.FileFormat;
import app.epistola.valtimo.domain.GeneratedDocument;

import java.util.Map;

/**
 * Service for interacting with Epistola document generation.
 */
public interface EpistolaService {

    /**
     * Generate a document using a template.
     *
     * @param tenantId     The tenant ID in Epistola
     * @param templateId   The ID of the template to use
     * @param data         The data to populate the template with (already resolved values)
     * @param format       The output format (PDF or HTML)
     * @param filename     The desired filename for the generated document
     * @return The generated document information
     */
    GeneratedDocument generateDocument(
            String tenantId,
            String templateId,
            Map<String, Object> data,
            FileFormat format,
            String filename
    );
}
