import {TemplateField} from '../models';

export interface RequiredFieldsStatus {
  mapped: number;
  total: number;
}

export function countRequiredMapped(
  fields: TemplateField[],
  mapping: Record<string, any>
): RequiredFieldsStatus {
  let mapped = 0;
  let total = 0;
  for (const field of fields) {
    if (field.fieldType === 'SCALAR' && field.required) {
      total++;
      const val = mapping[field.name];
      if (typeof val === 'string' && val.trim().length > 0) {
        mapped++;
      }
    } else if (field.fieldType === 'ARRAY' && field.required) {
      total++;
      const val = mapping[field.name];
      if (typeof val === 'string' && val.trim().length > 0) {
        mapped++;
      } else if (typeof val === 'object' && val !== null && '_source' in val) {
        if (typeof val['_source'] === 'string' && val['_source'].trim().length > 0) {
          mapped++;
        }
      }
    } else if (field.fieldType === 'OBJECT' && field.children) {
      const nested = (typeof mapping[field.name] === 'object' && mapping[field.name] !== null)
        ? mapping[field.name]
        : {};
      const childStats = countRequiredMapped(field.children, nested);
      mapped += childStats.mapped;
      total += childStats.total;
    }
  }
  return {mapped, total};
}
