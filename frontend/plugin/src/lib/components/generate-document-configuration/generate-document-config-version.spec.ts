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

import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  decodeJsonataStringLiteral,
  encodeJsonataStringLiteral,
  DEFAULT_GENERATE_DOCUMENT_DATA_MAPPING,
  isLegacyGenerateDocumentConfig,
  migrateGenerateDocumentConfig,
} from './generate-document-config-version';

interface V0ScalarCompatibilityFixture {
  name: string;
  value: string;
  interpretation: 'literal' | 'expression';
}

const v0ScalarCompatibilityFixtures = JSON.parse(
  readFileSync(
    resolve(
      __dirname,
      '../../../../../../test-fixtures/generate-document-v0-scalar-compatibility.json',
    ),
    'utf8',
  ),
) as V0ScalarCompatibilityFixture[];

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
  it.each(v0ScalarCompatibilityFixtures)(
    'classifies shared v0 scalar fixture: $name',
    ({ value, interpretation }) => {
      const migrated = migrateGenerateDocumentConfig(v0Config({ filename: value }));

      expect(migrated.filename).toBe(
        interpretation === 'expression' ? value : encodeJsonataStringLiteral(value),
      );
    },
  );

  it('identifies only missing, null, and zero versions as legacy', () => {
    expect(isLegacyGenerateDocumentConfig(v0Config())).toBe(true);
    expect(isLegacyGenerateDocumentConfig(v0Config({ actionConfigVersion: null }))).toBe(true);
    expect(isLegacyGenerateDocumentConfig(v0Config({ actionConfigVersion: 0 }))).toBe(true);
    expect(isLegacyGenerateDocumentConfig(v0Config({ actionConfigVersion: 1 }))).toBe(false);
    expect(isLegacyGenerateDocumentConfig(null)).toBe(false);
  });

  it('migrates v0 literals to JSONata string literals', () => {
    const migrated = migrateGenerateDocumentConfig(
      v0Config({
        variantId: 'formal',
        environmentId: 'production',
        correlationId: 'request-123',
        variantAttributes: [{ key: 'language', value: 'nl', required: true }],
      }),
    );

    expect(migrated).toMatchObject({
      actionConfigVersion: 1,
      outputFormat: '"PDF"',
      filename: '"value.pdf"',
      variantId: '"formal"',
      environmentId: '"production"',
      correlationId: '"request-123"',
      variantAttributes: [{ key: 'language', value: '"nl"', required: true }],
    });
  });

  it('also recognizes an explicit v0 marker', () => {
    expect(
      migrateGenerateDocumentConfig(v0Config({ actionConfigVersion: 0 })).actionConfigVersion,
    ).toBe(1);
  });

  it.each([undefined, 0, 1])(
    'opens a blank data mapping from version %s with the empty-object default',
    (actionConfigVersion) => {
      const migrated = migrateGenerateDocumentConfig(
        v0Config({
          actionConfigVersion,
          dataMapping: '   ',
          ...(actionConfigVersion === 1 ? { outputFormat: '"PDF"', filename: '"value.pdf"' } : {}),
        }),
      );

      expect(migrated.dataMapping).toBe(DEFAULT_GENERATE_DOCUMENT_DATA_MAPPING);
    },
  );

  it('preserves syntactically valid historical expressions', () => {
    const migrated = migrateGenerateDocumentConfig(
      v0Config({ filename: '"letter-" & $pv.number & ".pdf"', variantId: '$pv.variant' }),
    );

    expect(migrated.filename).toBe('"letter-" & $pv.number & ".pdf"');
    expect(migrated.variantId).toBe('$pv.variant');
  });

  it('preserves v0 correlation IDs as literals during migration', () => {
    expect(
      migrateGenerateDocumentConfig(v0Config({ correlationId: '$pv.correlationId' })).correlationId,
    ).toBe('"$pv.correlationId"');
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

  it.each(['$pv.correlationId', 'customer.id', 'test', '$uppercase(customer.name)'])(
    'keeps the valid v1 correlation expression %s unchanged',
    (correlationId) => {
      const config = v0Config({
        actionConfigVersion: 1,
        outputFormat: '"PDF"',
        filename: '"value.pdf"',
        environmentId: '$pv.environment',
        correlationId,
      });

      expect(migrateGenerateDocumentConfig(config)).toEqual(config);
    },
  );

  it('keeps a valid v1 configuration unchanged across repeated opens', () => {
    const config = v0Config({
      actionConfigVersion: 1,
      outputFormat: '"PDF"',
      filename: '"value.pdf"',
      environmentId: '$pv.environment',
      correlationId: 'customer.id',
    });

    const reopened = migrateGenerateDocumentConfig(config);

    expect(reopened).toEqual(config);
    expect(migrateGenerateDocumentConfig(reopened)).toEqual(config);
  });

  it('rejects v0 field representations inside an explicitly versioned v1 configuration', () => {
    expect(() =>
      migrateGenerateDocumentConfig(
        v0Config({
          actionConfigVersion: 1,
          filename: '"value.pdf"',
          correlationId: 'request-123',
        }),
      ),
    ).toThrow('outputFormat must be the JSONata string literal "PDF" in version 1');
  });

  it('does not encode a quoted v1 correlation expression again when reopened', () => {
    const config = v0Config({
      actionConfigVersion: 1,
      outputFormat: '"PDF"',
      filename: '"value.pdf"',
      correlationId: '"test"',
    });

    const reopened = migrateGenerateDocumentConfig(config);

    expect(reopened.correlationId).toBe('"test"');
    expect(migrateGenerateDocumentConfig(reopened)).toEqual(reopened);
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
    expect(decodeJsonataStringLiteral("'production'")).toBe('production');
    expect(decodeJsonataStringLiteral("'prod' & 'uction'")).toBeUndefined();
    expect(decodeJsonataStringLiteral('$pv.filename')).toBeUndefined();
  });
});
