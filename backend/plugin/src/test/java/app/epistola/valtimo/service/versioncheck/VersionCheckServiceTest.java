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

import app.epistola.valtimo.config.EpistolaProperties;
import app.epistola.valtimo.web.rest.dto.VersionCheckStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VersionCheckServiceTest {

    @Test
    void disabledStatusDoesNotFetchMetadata() {
        EpistolaProperties properties = new EpistolaProperties();
        properties.getVersionCheck().setEnabled(false);
        VersionCheckClient client = mock(VersionCheckClient.class);

        VersionCheckStatus status = new VersionCheckService(
                client,
                mock(VersionCheckIdentityProvider.class),
                properties
        ).status("1.0.0");

        assertThat(status.enabled()).isFalse();
        assertThat(status.currentVersion()).isEqualTo("1.0.0");
        verify(client, never()).fetch("unused", "unused", "unused");
    }

    @Test
    void statusFetchesAndCachesReleaseMetadata() {
        EpistolaProperties properties = new EpistolaProperties();
        VersionCheckClient client = mock(VersionCheckClient.class);
        VersionCheckIdentityProvider identityProvider = mock(VersionCheckIdentityProvider.class);
        when(identityProvider.valtimoInstallationId()).thenReturn("valtimo-1");
        when(client.fetch(
                properties.getVersionCheck().getWellKnownUrl(),
                "1.0.0",
                "valtimo-1"
        )).thenReturn(new EpistolaReleasesDocument(1, Map.of(
                "valtimo-epistola-plugin",
                new EpistolaReleasesDocument.ProductReleases(
                        new EpistolaReleasesDocument.ReleaseRef("1.1.0", null, null, null),
                        null,
                        null
                )
        )));

        VersionCheckService service = new VersionCheckService(client, identityProvider, properties);

        VersionCheckStatus first = service.status("1.0.0");
        VersionCheckStatus second = service.status("1.0.0");

        assertThat(first.updateAvailable()).isTrue();
        assertThat(second).isSameAs(first);
        verify(client).fetch(properties.getVersionCheck().getWellKnownUrl(), "1.0.0", "valtimo-1");
    }
}
