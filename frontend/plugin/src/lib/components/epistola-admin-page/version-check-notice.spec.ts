// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

// The specification is a plain object, but it imports the Angular components it names, which are
// not loadable under ts-jest — stubbed away, as in reconcile-feedback.spec.ts.
jest.mock('@valtimo/plugin', () => ({}));
jest.mock(
  '../../composer/configuration/generate-composed-document-configuration.component',
  () => ({ GenerateComposedDocumentConfigurationComponent: class {} }),
);
jest.mock('../epistola-configuration/epistola-configuration.component', () => ({
  EpistolaConfigurationComponent: class {},
}));
jest.mock('../generate-document-configuration/generate-document-configuration.component', () => ({
  GenerateDocumentConfigurationComponent: class {},
}));
jest.mock('../check-job-status-configuration/check-job-status-configuration.component', () => ({
  CheckJobStatusConfigurationComponent: class {},
}));
jest.mock('../download-document-configuration/download-document-configuration.component', () => ({
  DownloadDocumentConfigurationComponent: class {},
}));

import { VersionCheckStatus } from '../../models';
import { epistolaPluginSpecification } from '../../epistola.specification';
import { versionCheckNotice } from './version-check-notice';

function status(overrides: Partial<VersionCheckStatus> = {}): VersionCheckStatus {
  return {
    enabled: true,
    currentVersion: '1.0.1',
    metadataAvailable: true,
    preRelease: false,
    updateAvailable: false,
    supported: true,
    supportEndingSoon: false,
    lastError: null,
    ...overrides,
  };
}

describe('versionCheckNotice', () => {
  it('says nothing when the version is current and supported', () => {
    expect(versionCheckNotice(status())).toBeNull();
    expect(versionCheckNotice(null)).toBeNull();
  });

  it.each([
    [{ enabled: false }, 'info', 'epistolaAdminVersionCheckDisabled'],
    [{ supported: false }, 'error', 'epistolaAdminVersionUnsupported'],
    [{ supportEndingSoon: true }, 'warning', 'epistolaAdminVersionSupportEnding'],
    [{ preRelease: true }, 'info', 'epistolaAdminVersionPrerelease'],
    [{ updateAvailable: true }, 'info', 'epistolaAdminVersionUpdate'],
    [{ metadataAvailable: false }, 'warning', 'epistolaAdminVersionMetadataUnavailable'],
  ] as const)('shows %o as %s %s', (overrides, type, key) => {
    expect(versionCheckNotice(status(overrides))).toEqual({
      type,
      titleKey: `${key}Title`,
      bodyKey: `${key}Body`,
    });
  });

  it('does not send the reader to their network when fetched metadata could not be used', () => {
    const notice = versionCheckNotice(
      status({ lastError: 'Release metadata was generated at …, more than 7 days ago' }),
    );

    expect(notice?.titleKey).toBe('epistolaAdminVersionMetadataUnusableTitle');
  });

  it('still says a failed fetch failed, with or without an error message', () => {
    expect(
      versionCheckNotice(status({ metadataAvailable: false, lastError: 'Connection refused' }))
        ?.titleKey,
    ).toBe('epistolaAdminVersionMetadataUnavailableTitle');
  });

  it('puts support before an update, and an update before trouble with the metadata', () => {
    expect(versionCheckNotice(status({ supported: false, updateAvailable: true }))?.type).toBe(
      'error',
    );
    expect(
      versionCheckNotice(status({ updateAvailable: true, lastError: 'stale' }))?.titleKey,
    ).toBe('epistolaAdminVersionUpdateTitle');
  });

  it('has both translations for every notice it can show', () => {
    const translations = epistolaPluginSpecification.pluginTranslations as Record<
      string,
      Record<string, string>
    >;
    const statuses: VersionCheckStatus[] = [
      status({ enabled: false }),
      status({ supported: false }),
      status({ supportEndingSoon: true }),
      status({ preRelease: true }),
      status({ updateAvailable: true }),
      status({ lastError: 'stale' }),
      status({ metadataAvailable: false }),
    ];

    for (const language of ['nl', 'en']) {
      for (const notice of statuses.map(versionCheckNotice)) {
        expect(translations[language][notice!.titleKey]).toBeTruthy();
        expect(translations[language][notice!.bodyKey]).toBeTruthy();
      }
    }
  });
});
