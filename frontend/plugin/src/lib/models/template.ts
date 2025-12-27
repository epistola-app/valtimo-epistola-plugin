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
 */
export interface TemplateField {
  name: string;
  type: string;
  required: boolean;
  description?: string;
}

/**
 * Represents a single data mapping entry.
 */
export interface DataMappingEntry {
  templateField: string;
  dataSource: string;
}
