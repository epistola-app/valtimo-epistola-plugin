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
import org.operaton.bpm.engine.variable.Variables;

/**
 * Stores the raw PDF bytes inline as a {@code bytes} process variable.
 *
 * <p>Supported but not the default — best for small, non-sensitive documents. The bytes are
 * serialized into the task-detail HTTP response (Valtimo serializes the full variable map) and
 * persist with the process, so {@link TemporaryResourceStorageStrategy} is preferable for large or
 * private documents. A {@code byte[]} (not a String/Base64) is used so the value stays in the
 * byte-array table and avoids Operaton's {@code varchar(4000)} variable column.
 */
public class ProcessVariableStorageStrategy implements DocumentStorageStrategy {

    @Override
    public DocumentStorageTarget target() {
        return DocumentStorageTarget.PROCESS_VARIABLE;
    }

    @Override
    public void store(DelegateExecution execution, String documentId, byte[] content, String outputVariable) {
        execution.setVariable(outputVariable, Variables.byteArrayValue(content));
    }
}
