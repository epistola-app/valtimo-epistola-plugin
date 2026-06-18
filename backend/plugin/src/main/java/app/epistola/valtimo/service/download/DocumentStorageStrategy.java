/*
 * Copyright 2025 Epistola.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: EUPL-1.2
 */
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
