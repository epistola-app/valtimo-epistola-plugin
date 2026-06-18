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
  description?: string;
}

/**
 * Information about an Epistola catalog.
 */
export interface CatalogInfo {
  id: string;
  name: string;
  type: string;
}
