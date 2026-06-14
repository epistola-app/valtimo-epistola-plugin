package app.epistola.valtimo.download;

import app.epistola.valtimo.service.download.ProcessVariableStorageStrategy;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.JavaDelegate;

/**
 * Test service-task delegate standing in for {@code download-document}. Invoked by the engine via
 * {@code camunda:class} (no Spring bean resolution — Valtimo whitelists expression beans), and uses
 * the real, dependency-free {@link ProcessVariableStorageStrategy} to store an 8 KB document whose
 * Base64 would exceed Operaton's {@code varchar(4000)} variable column.
 */
public class EpistolaTestStoreDelegate implements JavaDelegate {

    static final String OUTPUT_VARIABLE = "documentContent";

    @Override
    public void execute(DelegateExecution execution) {
        byte[] content = new byte[8192];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i % 256);
        }
        new ProcessVariableStorageStrategy().store(execution, "test-doc", content, OUTPUT_VARIABLE);
    }
}
