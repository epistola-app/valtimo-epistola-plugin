package app.epistola.valtimo.service.download;

import app.epistola.valtimo.domain.DocumentStorageTarget;
import org.operaton.bpm.engine.delegate.DelegateExecution;

/**
 * Strategy for materializing a downloaded Epistola PDF into the process — see
 * {@code docs/adr/0001-download-document-content-storage.md}.
 *
 * <p>One implementation per {@link DocumentStorageTarget}. A strategy is only registered as a bean
 * when the backend it needs is present (e.g. the temporary-resource strategy requires
 * {@code TemporaryResourceStorageService}); selecting a target whose strategy is absent fails with a
 * clear error rather than a hard startup dependency.
 */
public interface DocumentStorageStrategy {

    /** The target this strategy handles. */
    DocumentStorageTarget target();

    /**
     * Materialize {@code content} and write the resulting reference/value to {@code outputVariable}
     * on the given execution.
     *
     * @param execution      the BPMN execution to write the output variable on
     * @param documentId     the Epistola document id (used for naming/metadata)
     * @param content        the raw PDF bytes
     * @param outputVariable the process-variable name to write the result to (target-specific)
     */
    void store(DelegateExecution execution, String documentId, byte[] content, String outputVariable);
}
