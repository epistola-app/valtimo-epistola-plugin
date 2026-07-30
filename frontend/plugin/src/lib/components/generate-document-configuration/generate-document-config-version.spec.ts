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
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: EUPL-1.2
 */

import {
  decodeJsonataStringLiteral,
  encodeJsonataStringLiteral,
  migrateGenerateDocumentConfig,
} from './generate-document-config-version';

const v0Config = (patch: Record<string, unknown> = {}) => ({
  catalogId: 'catalog',
  templateId: 'template',
  dataMapping: '{}',
  outputFormat: 'PDF',
  filename: 'value.pdf',
  resultProcessVariable: 'result',
  ...patch,
});

describe('generate-document action configuration versioning', () => {
  it('migrates v0 literals to JSONata string literals', () => {
    const migrated = migrateGenerateDocumentConfig(
      v0Config({
        variantId: 'formal',
        environmentId: 'production',
        variantAttributes: [{ key: 'language', value: 'nl', required: true }],
      }),
    );

    expect(migrated).toMatchObject({
      actionConfigVersion: 1,
      filename: '"value.pdf"',
      variantId: '"formal"',
      environmentId: '"production"',
      variantAttributes: [{ key: 'language', value: '"nl"', required: true }],
    });
  });

  it('also recognizes an explicit v0 marker', () => {
    expect(
      migrateGenerateDocumentConfig(v0Config({ actionConfigVersion: 0 })).actionConfigVersion,
    ).toBe(1);
  });

  it('preserves syntactically valid historical expressions', () => {
    const migrated = migrateGenerateDocumentConfig(
      v0Config({ filename: '"letter-" & $pv.number & ".pdf"', variantId: '$pv.variant' }),
    );

    expect(migrated.filename).toBe('"letter-" & $pv.number & ".pdf"');
    expect(migrated.variantId).toBe('$pv.variant');
  });

  it('treats ambiguous and malformed v0 values as literals', () => {
    expect(migrateGenerateDocumentConfig(v0Config({ filename: 'value.pdf' })).filename).toBe(
      '"value.pdf"',
    );
    expect(migrateGenerateDocumentConfig(v0Config({ filename: '$pv.[broken' })).filename).toBe(
      '"$pv.[broken"',
    );
    expect(migrateGenerateDocumentConfig(v0Config({ filename: 'pv:filename' })).filename).toBe(
      '"pv:filename"',
    );
  });

  it('keeps a valid v1 configuration unchanged', () => {
    const config = v0Config({
      actionConfigVersion: 1,
      filename: '"value.pdf"',
      environmentId: '$pv.environment',
    });

    expect(migrateGenerateDocumentConfig(config)).toEqual(config);
  });

  it('rejects future versions and deprecated object attributes', () => {
    expect(() => migrateGenerateDocumentConfig(v0Config({ actionConfigVersion: 2 }))).toThrow(
      'supports up to version 1',
    );
    expect(() =>
      migrateGenerateDocumentConfig(v0Config({ variantAttributes: { language: 'nl' } })),
    ).toThrow('variantAttributes must be an array');
  });

  it('round-trips escaped JSONata string literals', () => {
    const encoded = encodeJsonataStringLiteral('a "quoted" value\\file.pdf');
    expect(decodeJsonataStringLiteral(encoded)).toBe('a "quoted" value\\file.pdf');
    expect(decodeJsonataStringLiteral('$pv.filename')).toBeUndefined();
  });
});
