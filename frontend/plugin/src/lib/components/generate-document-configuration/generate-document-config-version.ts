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

import * as _jsonata from 'jsonata';
import {
  GenerateDocumentConfigV0,
  GenerateDocumentConfigV1,
  VariantAttributeEntry,
} from '../../models';

const jsonata = (_jsonata as any).default || _jsonata;
const LEGACY_JSONATA_MARKER = /[$&({?\[]/;

export const LATEST_GENERATE_DOCUMENT_CONFIG_VERSION = 1;

export class GenerateDocumentConfigVersionError extends Error {}

export function encodeJsonataStringLiteral(value: string): string {
  return JSON.stringify(value);
}

export function decodeJsonataStringLiteral(expression: string): string | undefined {
  if (!expression.startsWith('"')) {
    return undefined;
  }
  try {
    const value = JSON.parse(expression);
    return typeof value === 'string' ? value : undefined;
  } catch {
    return undefined;
  }
}

export function migrateGenerateDocumentConfig(raw: unknown): GenerateDocumentConfigV1 {
  const config = requireObject(raw);
  const version = readVersion(config.actionConfigVersion);

  if (version > LATEST_GENERATE_DOCUMENT_CONFIG_VERSION) {
    throw new GenerateDocumentConfigVersionError(
      `This action uses configuration version ${version}, but this plugin supports up to version ${LATEST_GENERATE_DOCUMENT_CONFIG_VERSION}.`,
    );
  }

  validateCommonShape(config);
  if (version === 1) {
    const v1 = config as unknown as GenerateDocumentConfigV1;
    validateV1Scalars(v1);
    return v1;
  }

  return migrateV0ToV1(config as unknown as GenerateDocumentConfigV0);
}

function migrateV0ToV1(v0: GenerateDocumentConfigV0): GenerateDocumentConfigV1 {
  const migrated: GenerateDocumentConfigV1 = {
    ...v0,
    actionConfigVersion: 1,
    outputFormat: encodeJsonataStringLiteral('PDF'),
    filename: migrateV0Scalar(v0.filename),
    variantId: migrateOptionalV0Scalar(v0.variantId),
    environmentId: migrateOptionalV0Scalar(v0.environmentId),
    correlationId:
      v0.correlationId === undefined ? undefined : encodeJsonataStringLiteral(v0.correlationId),
    variantAttributes: v0.variantAttributes?.map((attribute) => ({
      ...attribute,
      value: migrateV0Scalar(attribute.value),
    })),
  };
  validateV1Scalars(migrated);
  return migrated;
}

function readVersion(value: unknown): 0 | 1 | number {
  if (value === undefined || value === null) {
    return 0;
  }
  if (!Number.isInteger(value) || (value as number) < 0) {
    throw new GenerateDocumentConfigVersionError(
      'actionConfigVersion must be a non-negative integer.',
    );
  }
  return value as number;
}

function requireObject(raw: unknown): Record<string, unknown> {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
    throw new GenerateDocumentConfigVersionError(
      'The generate-document action configuration must be an object.',
    );
  }
  return raw as Record<string, unknown>;
}

function validateCommonShape(config: Record<string, unknown>): void {
  for (const field of [
    'catalogId',
    'templateId',
    'dataMapping',
    'outputFormat',
    'filename',
    'resultProcessVariable',
  ]) {
    if (typeof config[field] !== 'string') {
      throw new GenerateDocumentConfigVersionError(`${field} must be a string.`);
    }
  }
  for (const field of ['variantId', 'environmentId', 'correlationId'] as const) {
    if (config[field] != null && typeof config[field] !== 'string') {
      throw new GenerateDocumentConfigVersionError(`${field} must be a string when present.`);
    }
  }
  if (config.variantAttributes != null) {
    if (!Array.isArray(config.variantAttributes)) {
      throw new GenerateDocumentConfigVersionError('variantAttributes must be an array.');
    }
    config.variantAttributes.forEach(validateAttribute);
  }
}

function validateAttribute(attribute: unknown, index: number): void {
  const value = requireObject(attribute);
  if (
    typeof value.key !== 'string' ||
    typeof value.value !== 'string' ||
    typeof value.required !== 'boolean'
  ) {
    throw new GenerateDocumentConfigVersionError(
      `variantAttributes[${index}] must contain string key/value fields and a boolean required field.`,
    );
  }
}

function validateV1Scalars(config: GenerateDocumentConfigV1): void {
  validateJsonata('dataMapping', config.dataMapping);
  validateJsonata('outputFormat', config.outputFormat);
  if (config.outputFormat !== encodeJsonataStringLiteral('PDF')) {
    throw new GenerateDocumentConfigVersionError(
      'outputFormat must be the JSONata string literal "PDF" in version 1.',
    );
  }
  validateJsonata('filename', config.filename);
  for (const field of ['variantId', 'environmentId', 'correlationId'] as const) {
    const value = config[field];
    if (typeof value === 'string' && value.trim()) {
      validateJsonata(field, value);
    }
  }
  config.variantAttributes?.forEach((attribute: VariantAttributeEntry) =>
    validateJsonata(`variantAttributes.${attribute.key}`, attribute.value),
  );
}

function validateJsonata(field: string, expression: string): void {
  try {
    jsonata(expression);
  } catch {
    throw new GenerateDocumentConfigVersionError(`${field} must contain valid JSONata.`);
  }
}

function migrateOptionalV0Scalar(value: string | undefined): string | undefined {
  return value === undefined ? undefined : migrateV0Scalar(value);
}

function migrateV0Scalar(value: string): string {
  // A v0 value may already contain an explicitly quoted JSONata literal.
  if (decodeJsonataStringLiteral(value) !== undefined) {
    return value;
  }
  if (LEGACY_JSONATA_MARKER.test(value)) {
    try {
      jsonata(value);
      return value;
    } catch {
      // Historical malformed expression-like values are deliberately preserved as literals.
    }
  }
  return encodeJsonataStringLiteral(value);
}
