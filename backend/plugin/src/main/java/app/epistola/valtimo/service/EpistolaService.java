package app.epistola.valtimo.service;

import app.epistola.valtimo.domain.FileFormat;
import app.epistola.valtimo.domain.GeneratedDocument;
import app.epistola.valtimo.domain.TemplateDetails;
import app.epistola.valtimo.domain.TemplateInfo;

import java.util.List;
import java.util.Map;

/**
 * Service for interacting with Epistola document generation.
 */
public interface EpistolaService {

    /**
     * Get all available templates for a tenant.
     *
     * @param tenantId The tenant ID in Epistola
     * @return List of available templates
     */
    List<TemplateInfo> getTemplates(String tenantId);

    /**
     * Get template details including its fields.
     *
     * @param tenantId   The tenant ID in Epistola
     * @param templateId The ID of the template
     * @return Template details with fields
     */
    TemplateDetails getTemplateDetails(String tenantId, String templateId);

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
