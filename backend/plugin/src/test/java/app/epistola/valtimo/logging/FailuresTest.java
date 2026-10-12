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
package app.epistola.valtimo.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.time.Duration;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;

class FailuresTest {

    @Test
    void namesTheTypeAndNotTheMessage() {
        String described = Failures.describe(new IllegalStateException("Values: {/besluit/x=gegrond}"));

        assertThat(described).isEqualTo("IllegalStateException");
        assertThat(described).doesNotContain("gegrond", "besluit");
    }

    @Test
    void namesTheRootCauseToo() {
        // The distinction worth keeping: this is what tells a timeout from a parse failure once the
        // message is gone, and it is usually the root cause that carries it.
        Throwable wrapped = new UncheckedIOException(new IOException("connect timed out to 10.0.0.1"));

        assertThat(Failures.describe(wrapped)).isEqualTo("UncheckedIOException caused by IOException");
    }

    @Test
    void neverRepeatsATypeThatIsItsOwnRoot() {
        assertThat(Failures.describe(new IllegalArgumentException("x"))).isEqualTo("IllegalArgumentException");
    }

    @Test
    void survivesWhatCatchBlocksActuallyHandOver() {
        // Called from catch blocks and log statements, where throwing would replace a diagnostic
        // with an incident.
        assertThat(Failures.describe(null)).isEqualTo("unknown cause");

        // Java refuses x.initCause(x), but not a pair that points at each other — and an
        // unbounded walk of that pair never returns. Verified by building one: this test hung
        // before the traversal was bounded.
        Exception first = new IllegalStateException("first");
        Exception second = new RuntimeException("second");
        first.initCause(second);
        second.initCause(first);

        // What matters is that it returns at all. A cycle walked an even number of hops ends back
        // where it started, so it collapses to the outer type — shallow, but no data and no hang.
        assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> assertThat(Failures.describe(first)).isEqualTo("IllegalStateException"));
    }

    @Test
    void doesNotLeakAMessageHiddenInACauseChain() {
        Throwable deep = new IllegalStateException("outer",
                new RuntimeException("middle", new IllegalArgumentException("Values: {/aanvrager/bsn=123456782}")));

        assertThat(Failures.describe(deep))
                .isEqualTo("IllegalStateException caused by IllegalArgumentException")
                .doesNotContain("123456782", "bsn");
    }
}
