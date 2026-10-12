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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guards the rule in {@code CLAUDE.md}: a log line about a case document, a form submission, a
 * letter or a generation request must not carry a downstream {@code e.getMessage()}, because that
 * message quotes the data it rejected — Valtimo's refused write reads {@code … Values:
 * {/besluit/x=gegrond}}, and a JSONata failure quotes what it was evaluating.
 *
 * <p>Scanned by <b>shape</b> rather than from a list of approved call sites, on purpose: a list is
 * the kind of thing that is updated to make a build pass. This fires when a log statement mentions
 * one of the subjects that carry personal data <em>and</em> passes a message, which is the
 * combination that leaked twice before this guard existed.
 *
 * <p>It is a heuristic, so it can be wrong in both directions. A log about a catalog, a process
 * definition or a bundled changelog carries configuration, not case data, and keeps its message —
 * those are not matched. If it ever flags something genuinely safe, the fix is
 * {@link Failures#describe} anyway: a class name costs little, and the rule is stated as "never".
 */
class LogStatementPersonalDataTest {

    /** Subjects whose downstream messages quote case data. */
    private static final List<String> CARRIES_PERSONAL_DATA = List.of(
            "document content", "case document", "letter", "submission", "compos",
            "preview", "generation request", "datamapping", "render", "form '");

    private static final List<String> LOG_CALLS = List.of(
            "log.trace(", "log.debug(", "log.info(", "log.warn(", "log.error(");

    @Test
    void noLogStatementAboutCaseDataCarriesADownstreamMessage() {
        List<String> offenders = new ArrayList<>();

        for (Path source : javaSources()) {
            for (Statement statement : logStatementsIn(source)) {
                String text = statement.text.toLowerCase(Locale.ROOT);
                boolean passesAMessage = text.contains("getmessage()")
                        || text.contains("getresponsebodyasstring()");
                boolean aboutCaseData = CARRIES_PERSONAL_DATA.stream().anyMatch(text::contains);
                if (passesAMessage && aboutCaseData) {
                    offenders.add(source.getFileName() + ":" + statement.line + " — " + statement.text.strip());
                }
            }
        }

        assertThat(offenders)
                .withFailMessage("""
                        These log statements are about data that is personal data, and pass a \
                        message from code this plugin does not own. Use Failures.describe(e) \
                        instead — it names the failure's type and its root cause's type, which \
                        keeps the transient-versus-permanent distinction and no data. See the \
                        "Never log personal data" section in CLAUDE.md.
                        %s""".formatted(String.join("\n", offenders)))
                .isEmpty();
    }

    @Test
    void noLogStatementAnywhereLogsARawResponseBody() {
        // Unambiguous, so it needs no subject check: a downstream error body is whatever the server
        // chose to echo, and for a generation or preview call that is the data we just sent it.
        List<String> offenders = new ArrayList<>();
        for (Path source : javaSources()) {
            for (Statement statement : logStatementsIn(source)) {
                if (statement.text.contains("getResponseBodyAsString()")) {
                    offenders.add(source.getFileName() + ":" + statement.line);
                }
            }
        }

        assertThat(offenders)
                .withFailMessage("A response body may echo the request. Log the status instead: %s", offenders)
                .isEmpty();
    }

    @Test
    void theScanActuallySeesTheSources() {
        // Otherwise both tests above pass by finding nothing to look at — which is exactly how a
        // guard like this rots into decoration.
        List<Path> sources = javaSources();
        assertThat(sources).hasSizeGreaterThan(50);

        long statements = sources.stream().mapToLong(s -> logStatementsIn(s).size()).sum();
        assertThat(statements).isGreaterThan(100);
    }

    @Test
    void theScanWouldCatchTheLeaksThatWereFixed() {
        // The two real shapes, as they were written before being fixed.
        assertThat(flagged("log.warn(\"Failed to load document content for {}: {}\", documentId, e.getMessage());"))
                .isTrue();
        assertThat(flagged("log.debug(\"Preview API error for tenant {}: {}\", id, e.getResponseBodyAsString());"))
                .isTrue();
        // And a configuration log keeps its message.
        assertThat(flagged("log.warn(\"Failed to read bundled CHANGELOG: {}\", e.getMessage());")).isFalse();
    }

    private static boolean flagged(String statement) {
        String text = statement.toLowerCase(Locale.ROOT);
        return (text.contains("getmessage()") || text.contains("getresponsebodyasstring()"))
                && CARRIES_PERSONAL_DATA.stream().anyMatch(text::contains);
    }

    private record Statement(int line, String text) {
    }

    /** A log call joined across continuation lines, since most of the long ones wrap. */
    private static List<Statement> logStatementsIn(Path source) {
        List<String> lines;
        try {
            lines = Files.readAllLines(source);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        List<Statement> statements = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (LOG_CALLS.stream().noneMatch(line::contains)) {
                continue;
            }
            StringBuilder joined = new StringBuilder(line.strip());
            for (int j = i + 1; j < lines.size() && !joined.toString().endsWith(";"); j++) {
                joined.append(' ').append(lines.get(j).strip());
            }
            statements.add(new Statement(i + 1, joined.toString()));
        }
        return statements;
    }

    private static List<Path> javaSources() {
        Path root = sourceRoot();
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Gradle runs tests from the module directory; a run from the repo root still finds it. */
    private static Path sourceRoot() {
        for (Path candidate : List.of(
                Path.of("src/main/java"),
                Path.of("backend/plugin/src/main/java"))) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not locate the plugin's Java sources from "
                + Path.of("").toAbsolutePath());
    }
}
