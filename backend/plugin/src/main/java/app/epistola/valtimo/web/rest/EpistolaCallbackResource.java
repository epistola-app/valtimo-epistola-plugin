package app.epistola.valtimo.web.rest;

import com.ritense.valtimo.contract.annotation.SkipComponentScan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.operaton.bpm.engine.RuntimeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST controller for Epistola webhook callbacks.
 * <p>
 * This endpoint receives callbacks from Epistola when document generation is complete.
 * It correlates BPMN messages to wake up waiting processes.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/plugin/epistola/callback")
@SkipComponentScan
@RequiredArgsConstructor
public class EpistolaCallbackResource {

    private static final String MESSAGE_NAME = "EpistolaDocumentGenerated";

    private final RuntimeService runtimeService;

    /**
     * Callback endpoint for Epistola document generation completion.
     * <p>
     * This endpoint is called by Epistola when a document generation job completes
     * (either successfully or with failure). It correlates a BPMN message to
     * wake up any processes waiting for this document.
     *
     * @param payload   The callback payload containing job details
     * @param signature Optional HMAC signature for request verification
     * @return HTTP 200 OK if message correlation succeeded
     */
    @PostMapping("/generation-complete")
    public ResponseEntity<Void> onGenerationComplete(
            @RequestBody GenerationCompletePayload payload,
            @RequestHeader(value = "X-Epistola-Signature", required = false) String signature
    ) {
        log.info("Received generation complete callback: requestId={}, status={}, documentId={}",
                payload.requestId(), payload.status(), payload.documentId());

        // TODO: Implement signature verification when Epistola supports it
        // For now, log a warning if no signature is provided
        if (signature == null || signature.isBlank()) {
            log.warn("Callback received without signature verification");
        }

        try {
            // Correlate the BPMN message using the requestId as correlation key
            runtimeService.createMessageCorrelation(MESSAGE_NAME)
                    .processInstanceVariableEquals("epistolaRequestId", payload.requestId())
                    .setVariable("epistolaStatus", payload.status())
                    .setVariable("epistolaDocumentId", payload.documentId())
                    .setVariable("epistolaErrorMessage", payload.errorMessage())
                    .correlate();

            log.info("Successfully correlated message {} for requestId {}", MESSAGE_NAME, payload.requestId());
            return ResponseEntity.ok().build();

        } catch (Exception e) {
            // Log but don't fail - the process might not be waiting for this message
            log.warn("Failed to correlate message for requestId {}: {}", payload.requestId(), e.getMessage());
            // Still return 200 OK to acknowledge receipt
            return ResponseEntity.ok().build();
        }
    }

    /**
     * Payload for generation complete callback.
     *
     * @param requestId    The original request ID from the generate call
     * @param status       The job status (COMPLETED, FAILED, CANCELLED)
     * @param documentId   The document ID if generation was successful (null otherwise)
     * @param errorMessage Error message if generation failed (null otherwise)
     * @param correlationId The client-provided correlation ID (if any)
     */
    public record GenerationCompletePayload(
            String requestId,
            String status,
            String documentId,
            String errorMessage,
            String correlationId
    ) {}
}
