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

import type { JsonSchema } from './expression';

/**
 * Basic information about an Epistola template.
 */
export interface TemplateInfo {
  id: string;
  name: string;
  description?: string;
  catalogId?: string;
  catalogName?: string;
}

/**
 * Detailed information about an Epistola template including its fields.
 */
export interface TemplateDetails {
  id: string;
  name: string;
  fields: TemplateField[];
  schema?: JsonSchema | boolean | null;
  simpleMappingSupport?: SimpleMappingSupport;
}

export interface SimpleMappingSupport {
  level: 'FULL' | 'PARTIAL' | 'UNSUPPORTED';
  reason?: string | null;
}

/**
 * Represents a field in an Epistola template.
 * Supports nested structures through the children property.
 */
export interface TemplateField {
  name: string;
  path: string;
  type: string;
  fieldType: 'SCALAR' | 'OBJECT' | 'ARRAY';
  required: boolean;
  description?: string;
  children?: TemplateField[];
  complex?: boolean;
  complexityReason?: string;
  nullable?: boolean;
  /** What the backend read from the field's schema; only what the frontend uses is typed here. */
  hints?: TemplateFieldHints;
}

export interface TemplateFieldHints {
  /** Set when the field takes one of Epistola's rich-text documents (a list of them included). */
  richText?: 'INLINE' | 'BLOCK' | null;
}

/**
 * Represents a single data mapping entry.
 */
export interface DataMappingEntry {
  templateField: string;
  dataSource: string;
}

/**
 * Source type for data mapping: how the value is provided.
 */
export type DataSourceType = 'document' | 'processVariable' | 'manual';

/**
/**
 * Information about an Epistola environment.
 */
export interface EnvironmentInfo {
  id: string;
  name: string;
}

/**
 * Information about an Epistola template variant.
 */
export interface VariantInfo {
  id: string;
  templateId: string;
  name: string;
  attributes: Record<string, string>;
}

/**
 * An attribute definition for variant selection within a tenant.
 */
export interface AttributeDefinition {
  key: string;
  name?: string;
}

/**
 * Information about an Epistola catalog.
 *
 * `id` is what a process link stores: `registry/namespace/key` (contract 2.0 names a catalog by all
 * three). A link saved against contract 1.x holds only the key; see `matchCatalogId`.
 */
export interface CatalogInfo {
  id: string;
  name: string;
  registry: string;
  namespace: string;
  key: string;
  editable: boolean;
}

/**
 * The released versions of a catalog a process link may pin, and the one it renders.
 *
 * Mirrors `CatalogReleases` on the backend.
 */
export interface CatalogReleases {
  /** Released versions, highest first: in an environment, those deployed to it. */
  versions: string[];
  /** The version the link renders with its range, or `null` when none satisfies it. */
  selected: string | null;
  /** True when, without a range, the environment renders the catalog's deployed draft. */
  draft: boolean;
}

/**
 * One generate task that reads a composer's letter, named as the author sees it in the modeller.
 *
 * Mirrors `LetterReaderService.LetterReader` on the backend.
 */
export interface LetterReader {
  processDefinitionKey: string;
  processDefinitionName: string;
  activityId: string;
}
