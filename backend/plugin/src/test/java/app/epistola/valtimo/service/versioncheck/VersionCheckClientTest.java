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
package app.epistola.valtimo.service.versioncheck;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VersionCheckClientTest {

    private HttpServer server;
    private int port;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void fetchSendsPluginVersionAndValtimoInstallationHeaders() {
        AtomicReference<String> seenVersion = new AtomicReference<>();
        AtomicReference<String> seenValtimoInstallationId = new AtomicReference<>();
        AtomicReference<String> seenUserAgent = new AtomicReference<>();
        server.createContext("/.well-known/epistola/releases.json", exchange -> {
            seenVersion.set(exchange.getRequestHeaders().getFirst("X-Epistola-Valtimo-Plugin-Version"));
            seenValtimoInstallationId.set(
                    exchange.getRequestHeaders().getFirst("X-Epistola-Valtimo-Installation-Id"));
            seenUserAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            byte[] body = """
                    {
                      "schemaVersion": 1,
                      "products": {
                        "valtimo-epistola-plugin": {
                          "stable": {
                            "version": "1.0.0",
                            "releaseUrl": "https://example.test/releases/1.0.0"
                          }
                        }
                      }
                    }
                    """.getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        EpistolaReleasesDocument document = new VersionCheckClient(restClient()).fetch(
                "http://127.0.0.1:" + port + "/.well-known/epistola/releases.json",
                "1.0.0-RC1",
                "valtimo-installation-123"
        );

        assertThat(seenVersion.get()).isEqualTo("1.0.0-RC1");
        assertThat(seenValtimoInstallationId.get()).isEqualTo("valtimo-installation-123");
        assertThat(seenUserAgent.get()).isEqualTo("valtimo-epistola-plugin/1.0.0-RC1");
        assertThat(document.products().get("valtimo-epistola-plugin").stable().version()).isEqualTo("1.0.0");
    }

    @Test
    void fetchTreats404AsMetadataUnavailable() {
        server.createContext("/.well-known/epistola/releases.json", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });

        assertThatThrownBy(() -> new VersionCheckClient(restClient()).fetch(
                "http://127.0.0.1:" + port + "/.well-known/epistola/releases.json",
                "1.0.0",
                null
        )).isInstanceOf(VersionMetadataUnavailableException.class);
    }

    private static RestClient restClient() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        return RestClient.builder().requestFactory(factory).build();
    }
}
