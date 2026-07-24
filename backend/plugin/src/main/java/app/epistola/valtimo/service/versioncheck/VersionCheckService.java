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
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
public class VersionCheckService {

    private final VersionCheckClient client;
    private final VersionCheckIdentityProvider identityProvider;
    private final EpistolaProperties properties;
    private final TaskScheduler taskScheduler;
    private volatile VersionCheckStatus cachedStatus;

    public VersionCheckService(
            VersionCheckClient client,
            VersionCheckIdentityProvider identityProvider,
            EpistolaProperties properties,
            TaskScheduler taskScheduler
    ) {
        this.client = client;
        this.identityProvider = identityProvider;
        this.properties = properties;
        this.taskScheduler = taskScheduler;
    }

    public VersionCheckStatus status(String currentVersion) {
        if (!properties.getVersionCheck().isEnabled()) {
            return VersionCheckStatus.disabled(currentVersion);
        }
        VersionCheckStatus status = cachedStatus;
        if (status != null && currentVersion.equals(status.currentVersion())) {
            return status;
        }
        return checkNow(currentVersion);
    }

    public synchronized VersionCheckStatus checkNow(String currentVersion) {
        if (!properties.getVersionCheck().isEnabled()) {
            return VersionCheckStatus.disabled(currentVersion);
        }
        Instant now = Instant.now();
        VersionCheckStatus status;
        try {
            var config = properties.getVersionCheck();
            var document = client.fetch(
                    config.getWellKnownUrl(),
                    currentVersion,
                    identityProvider.valtimoInstallationId());
            status = VersionCheckEvaluator.evaluate(
                    document,
                    config.getProductKey(),
                    currentVersion,
                    now,
                    config.getDeprecationWarningWindow()
            );
        } catch (VersionMetadataUnavailableException e) {
            status = metadataUnavailable(currentVersion, now, null);
            log.info("Version check metadata unavailable: {}", e.getMessage());
        } catch (Exception e) {
            status = metadataUnavailable(currentVersion, now, e.getMessage());
            log.warn("Version check failed: {}", e.getMessage());
        }
        cachedStatus = status;
        if (status.updateAvailable()) {
            log.info(
                    "Epistola Valtimo plugin update available: current={}, latest={}, preRelease={}",
                    status.currentVersion(), status.latestVersion(), status.preRelease());
        }
        return status;
    }

    @Scheduled(
            cron = "${epistola.version-check.cron:0 0 5 * * *}",
            zone = "${epistola.version-check.zone:UTC}"
    )
    public void scheduledCheck() {
        if (!properties.getVersionCheck().isEnabled()) {
            return;
        }
        taskScheduler.schedule(this::runScheduledCheck, Instant.now().plusMillis(nextJitterMs()));
    }

    void runScheduledCheck() {
        VersionCheckStatus status = cachedStatus;
        String currentVersion = status != null && status.currentVersion() != null
                ? status.currentVersion()
                : resolveProductVersion();
        checkNow(currentVersion);
    }

    long nextJitterMs() {
        long maxJitterMs = properties.getVersionCheck().getMaxJitter().toMillis();
        if (maxJitterMs <= 0) {
            return 0L;
        }
        return ThreadLocalRandom.current().nextLong(maxJitterMs + 1);
    }

    private VersionCheckStatus metadataUnavailable(String currentVersion, Instant checkedAt, String lastError) {
        VersionCheckStatus previous = cachedStatus;
        if (previous != null && currentVersion.equals(previous.currentVersion())) {
            return new VersionCheckStatus(
                    true,
                    checkedAt,
                    currentVersion,
                    false,
                    previous.preRelease(),
                    previous.latestVersion(),
                    previous.updateAvailable(),
                    previous.releaseUrl(),
                    previous.changelogUrl(),
                    previous.latestStableVersion(),
                    previous.stableReleaseUrl(),
                    previous.stableChangelogUrl(),
                    previous.supported(),
                    previous.supportEndingSoon(),
                    previous.minSupportedVersion(),
                    previous.supportedUntil(),
                    lastError
            );
        }
        return new VersionCheckStatus(
                true, checkedAt, currentVersion, false, false, null, false,
                null, null, null, null, null, true, false, null, null, lastError);
    }

    private String resolveProductVersion() {
        Package pkg = VersionCheckService.class.getPackage();
        String version = pkg != null ? pkg.getImplementationVersion() : null;
        if (version == null || version.isBlank() || "unspecified".equals(version)) {
            return "development";
        }
        return version;
    }
}
