package app.epistola.valtimo.web.rest;

import app.epistola.valtimo.service.EpistolaMessageCorrelationService;
import com.ritense.valtimo.contract.annotation.SkipComponentScan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for Epistola webhook callbacks.
 * <p>
 * This endpoint receives callbacks from Epistola when document generation is complete.
 * It delegates to {@link EpistolaMessageCorrelationService} to correlate BPMN messages.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/plugin/epistola/callback")
@SkipComponentScan
@RequiredArgsConstructor
public class EpistolaCallbackResource {

    private final EpistolaMessageCorrelationService correlationService;

    /**
     * Callback endpoint for Epistola document generation completion.
     * <p>
     * This endpoint is called by Epistola when a document generation job completes
     * (either successfully or with failure). It correlates a BPMN message to
     * wake up any processes waiting for this document.
     *
     * @param payload   The callback payload containing job details
     * @param signature Optional HMAC signature for request verification
     * @return HTTP 200 OK to acknowledge receipt
     */
    @PostMapping("/generation-complete")
    public ResponseEntity<Void> onGenerationComplete(
            @RequestBody GenerationCompletePayload payload,
            @RequestHeader(value = "X-Epistola-Signature", required = false) String signature
    ) {
        log.info("Received generation complete callback: requestId={}, status={}, documentId={}",
                payload.requestId(), payload.status(), payload.documentId());

        // TODO: Implement signature verification when Epistola supports it
        if (signature == null || signature.isBlank()) {
            log.warn("Callback received without signature verification");
        }

        try {
            int count = correlationService.correlateCompletion(
                    payload.requestId(),
                    payload.status(),
                    payload.documentId(),
                    payload.errorMessage()
            );

            if (count == 0) {
                log.warn("No waiting process instances found for requestId {}", payload.requestId());
            }
        } catch (Exception e) {
            log.warn("Failed to correlate message for requestId {}: {}", payload.requestId(), e.getMessage());
        }

        // Always return 200 OK to acknowledge receipt
        return ResponseEntity.ok().build();
    }

    /**
     * Payload for generation complete callback.
     *
     * @param requestId     The original request ID from the generate call
     * @param status        The job status (COMPLETED, FAILED, CANCELLED)
     * @param documentId    The document ID if generation was successful (null otherwise)
     * @param errorMessage  Error message if generation failed (null otherwise)
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
