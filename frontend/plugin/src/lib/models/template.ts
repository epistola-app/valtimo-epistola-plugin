/**
 * Basic information about an Epistola template.
 */
export interface TemplateInfo {
  id: string;
  name: string;
  description?: string;
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
 * Validation result from the backend.
 */
export interface ValidationResult {
  valid: boolean;
  missingRequiredFields: string[];
}

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
