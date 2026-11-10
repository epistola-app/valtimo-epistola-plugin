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
package app.epistola.valtimo.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class GenerationJobStatusTest {

    /** Process variables keep the 1.x form, which installers' BPMN gateways compare against. */
    @ParameterizedTest
    @CsvSource({
            "pending, PENDING",
            "in-progress, IN_PROGRESS",
            "completed, COMPLETED",
            "failed, FAILED",
            "cancelled, CANCELLED",
            "COMPLETED, COMPLETED",
            "IN_PROGRESS, IN_PROGRESS"
    })
    void readsContract2AndContract1StatusesAlike(String sent, GenerationJobStatus expected) {
        assertThat(GenerationJobStatus.fromApi(sent)).isEqualTo(expected);
        assertThat(GenerationJobStatus.normalize(sent)).isEqualTo(expected.name());
    }

    @Test
    void normalizesAStatusItDoesNotKnow_ratherThanRefusingIt() {
        assertThat(GenerationJobStatus.normalize("on-hold")).isEqualTo("ON_HOLD");
        assertThat(GenerationJobStatus.normalize(null)).isNull();
    }
}
