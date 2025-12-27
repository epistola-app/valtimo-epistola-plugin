package app.epistola.valtimo.service;

import app.epistola.valtimo.domain.FileFormat;
import app.epistola.valtimo.domain.GeneratedDocument;
import app.epistola.valtimo.domain.TemplateDetails;
import app.epistola.valtimo.domain.TemplateField;
import app.epistola.valtimo.domain.TemplateInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
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

    // Mock templates for development - TODO: Replace with actual Epistola API calls
    private static final List<TemplateInfo> MOCK_TEMPLATES = List.of(
            new TemplateInfo("template-1", "Welcome Letter", "A welcome letter for new customers"),
            new TemplateInfo("template-2", "Invoice Template", "Standard invoice template"),
            new TemplateInfo("template-3", "Contract Agreement", "Legal contract template")
    );

    private static final Map<String, TemplateDetails> MOCK_TEMPLATE_DETAILS = Map.of(
            "template-1", new TemplateDetails("template-1", "Welcome Letter", List.of(
                    new TemplateField("customerName", "string", true, "Full name of the customer"),
                    new TemplateField("customerEmail", "string", true, "Email address"),
                    new TemplateField("welcomeDate", "date", false, "Date of welcome"),
                    new TemplateField("personalMessage", "string", false, "Optional personal message")
            )),
            "template-2", new TemplateDetails("template-2", "Invoice Template", List.of(
                    new TemplateField("invoiceNumber", "string", true, "Invoice number"),
                    new TemplateField("customerName", "string", true, "Customer name"),
                    new TemplateField("amount", "number", true, "Invoice amount"),
                    new TemplateField("dueDate", "date", true, "Payment due date"),
                    new TemplateField("description", "string", false, "Invoice description")
            )),
            "template-3", new TemplateDetails("template-3", "Contract Agreement", List.of(
                    new TemplateField("partyName", "string", true, "Name of contracting party"),
                    new TemplateField("contractDate", "date", true, "Date of contract"),
                    new TemplateField("contractValue", "number", true, "Value of contract"),
                    new TemplateField("duration", "string", true, "Contract duration"),
                    new TemplateField("specialTerms", "string", false, "Special terms and conditions")
            ))
    );

    @Override
    public List<TemplateInfo> getTemplates(String tenantId) {
        log.info("Fetching templates for tenant: {}", tenantId);
        // TODO: Implement actual Epistola API call
        return MOCK_TEMPLATES;
    }

    @Override
    public TemplateDetails getTemplateDetails(String tenantId, String templateId) {
        log.info("Fetching template details for tenant: {}, template: {}", tenantId, templateId);
        // TODO: Implement actual Epistola API call
        TemplateDetails details = MOCK_TEMPLATE_DETAILS.get(templateId);
        if (details == null) {
            throw new IllegalArgumentException("Template not found: " + templateId);
        }
        return details;
    }

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
