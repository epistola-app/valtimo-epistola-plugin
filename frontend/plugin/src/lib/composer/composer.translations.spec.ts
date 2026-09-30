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

/**
 * The composer owns its strings, and the specification spreads them into the one shape Valtimo
 * consumes. That spread is a seam: drop it and every label falls back to its key, on screen, with
 * nothing failing. So it is asserted here rather than trusted.
 */
// The specification is a plain object, but it imports the Angular components it names. Those are
// irrelevant here and are not loadable under ts-jest, so they are stubbed away.
jest.mock('@valtimo/plugin', () => ({}));
jest.mock('./configuration/generate-composed-document-configuration.component', () => ({
  GenerateComposedDocumentConfigurationComponent: class {},
}));
jest.mock('../components/epistola-configuration/epistola-configuration.component', () => ({
  EpistolaConfigurationComponent: class {},
}));
jest.mock(
  '../components/generate-document-configuration/generate-document-configuration.component',
  () => ({
    GenerateDocumentConfigurationComponent: class {},
  }),
);
jest.mock(
  '../components/check-job-status-configuration/check-job-status-configuration.component',
  () => ({
    CheckJobStatusConfigurationComponent: class {},
  }),
);
jest.mock(
  '../components/download-document-configuration/download-document-configuration.component',
  () => ({
    DownloadDocumentConfigurationComponent: class {},
  }),
);

import { COMPOSER_TRANSLATIONS } from './composer.translations';
import { epistolaPluginSpecification } from '../epistola.specification';

const LANGUAGES = ['nl', 'en'] as const;

describe('composer translations', () => {
  it.each(LANGUAGES)('reach the plugin specification (%s)', (language) => {
    const specification = (epistolaPluginSpecification.pluginTranslations as any)[language];

    for (const [key, value] of Object.entries(COMPOSER_TRANSLATIONS[language])) {
      expect(specification[key]).toBe(value);
    }
  });

  /** A key in one language only shows an untranslated screen to exactly half the users. */
  it('say the same things in both languages', () => {
    expect(Object.keys(COMPOSER_TRANSLATIONS.nl).sort()).toEqual(
      Object.keys(COMPOSER_TRANSLATIONS.en).sort(),
    );
  });

  it('do not silently take over a key the rest of the plugin already defines', () => {
    // Spreading last means the composer would win, and the other component would change language
    // for reasons no one would look for here.
    const composerKeys = new Set(Object.keys(COMPOSER_TRANSLATIONS.nl));
    const ownedElsewhere = Object.keys(
      (epistolaPluginSpecification.pluginTranslations as any).nl,
    ).filter((key) => composerKeys.has(key));

    expect(ownedElsewhere.sort()).toEqual([...composerKeys].sort());
  });
});
