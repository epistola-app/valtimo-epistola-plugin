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

import { VersionCheckStatus } from '../../models';

/** The one notice the admin page shows about the version check. */
export interface VersionCheckNotice {
  type: 'error' | 'warning' | 'info';
  titleKey: string;
  bodyKey: string;
}

function notice(type: VersionCheckNotice['type'], key: string): VersionCheckNotice {
  return { type, titleKey: `${key}Title`, bodyKey: `${key}Body` };
}

/**
 * The notice for a version check, or null when there is nothing to say. The page has room for
 * one, so the most pressing wins: support before updates, updates before trouble with the
 * metadata itself.
 */
export function versionCheckNotice(status: VersionCheckStatus | null): VersionCheckNotice | null {
  if (!status) {
    return null;
  }
  if (!status.enabled) {
    return notice('info', 'epistolaAdminVersionCheckDisabled');
  }
  if (!status.supported) {
    return notice('error', 'epistolaAdminVersionUnsupported');
  }
  if (status.supportEndingSoon) {
    return notice('warning', 'epistolaAdminVersionSupportEnding');
  }
  if (status.preRelease) {
    return notice('info', 'epistolaAdminVersionPrerelease');
  }
  if (status.updateAvailable) {
    return notice('info', 'epistolaAdminVersionUpdate');
  }
  // Fetched, but not usable to judge support (out of date, or missing what the check needs): not
  // a network problem, so it must not send the reader off to check their network.
  if (status.metadataAvailable && status.lastError) {
    return notice('warning', 'epistolaAdminVersionMetadataUnusable');
  }
  if (!status.metadataAvailable || status.lastError) {
    return notice('warning', 'epistolaAdminVersionMetadataUnavailable');
  }
  return null;
}
