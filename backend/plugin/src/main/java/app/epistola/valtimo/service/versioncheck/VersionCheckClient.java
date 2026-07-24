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

import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

public class VersionCheckClient {

    private final RestClient restClient;

    public VersionCheckClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public EpistolaReleasesDocument fetch(String url, String currentVersion, String valtimoInstallationId) {
        try {
            var request = restClient.get()
                    .uri(url)
                    .header("User-Agent", "valtimo-epistola-plugin/" + currentVersion)
                    .header("X-Epistola-Valtimo-Plugin-Version", currentVersion);
            if (valtimoInstallationId != null && !valtimoInstallationId.isBlank()) {
                request.header("X-Epistola-Valtimo-Installation-Id", valtimoInstallationId);
            }
            EpistolaReleasesDocument document = request.retrieve()
                    .body(EpistolaReleasesDocument.class);
            if (document == null) {
                throw new VersionCheckException("Empty response from " + url);
            }
            return document;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new VersionMetadataUnavailableException("Release metadata not found at " + url, e);
            }
            throw new VersionCheckException("Fetch failed for " + url + ": " + e.getMessage(), e);
        } catch (RestClientException e) {
            throw new VersionCheckException("Fetch failed for " + url + ": " + e.getMessage(), e);
        }
    }
}
