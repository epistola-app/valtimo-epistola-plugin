import * as _jsonata from 'jsonata';
const jsonata = (_jsonata as any).default || _jsonata;

/**
 * Represents a field in the visual builder.
 * - mode 'ref': a simple $doc/$pv/$case path reference
 * - mode 'raw': raw JSONata text the builder can't represent visually
 */
export interface BuilderField {
  name: string;
  mode: 'ref' | 'raw';
  value: string;
  children?: BuilderField[];
}

/**
 * Parse a JSONata expression into BuilderField array.
 * Only supports top-level object literals with simple path references or nested objects.
 * Anything else is stored as raw JSONata text.
 */
export function parseJsonataToBuilder(expression: string): BuilderField[] {
  if (!expression || !expression.trim()) {
    return [];
  }

  try {
    const ast = (jsonata(expression) as any).ast();
    if (ast.type === 'unary' && ast.value === '{') {
      return parseObjectEntries(ast.lhs, expression);
    }
    // Not a top-level object — can't represent in builder
    return [{ name: '_root', mode: 'raw', value: expression }];
  } catch {
    // Invalid JSONata — return as single raw field
    return [{ name: '_root', mode: 'raw', value: expression }];
  }
}

/**
 * Convert BuilderField array back to a JSONata expression string.
 */
export function builderToJsonata(fields: BuilderField[]): string {
  if (fields.length === 0) {
    return '';
  }
  // Special case: single _root raw field means the whole expression is raw
  if (fields.length === 1 && fields[0].name === '_root' && fields[0].mode === 'raw') {
    return fields[0].value;
  }

  const entries = fields.map((field) => formatFieldEntry(field)).filter(Boolean);
  return `{\n${entries.join(',\n')}\n}`;
}

/**
 * Check if a JSONata expression can be fully represented by the builder
 * (i.e., all fields are simple refs or nested objects of simple refs).
 */
export function isBuilderCompatible(expression: string): boolean {
  const fields = parseJsonataToBuilder(expression);
  return fields.every((f) => f.mode === 'ref' || (f.children && f.children.every(isFieldSimple)));
}

function isFieldSimple(field: BuilderField): boolean {
  if (field.mode === 'raw') return false;
  if (field.children) return field.children.every(isFieldSimple);
  return true;
}

function parseObjectEntries(entries: any[][], source: string): BuilderField[] {
  return entries.map(([keyNode, valueNode]) => {
    const name = keyNode.value as string;
    return classifyValue(name, valueNode, source);
  });
}

function classifyValue(name: string, node: any, source: string): BuilderField {
  // Simple path reference: $doc.x.y, $pv.x, $case.x — store as JSONata directly
  if (node.type === 'path' && node.steps?.length > 0 && node.steps[0].type === 'variable') {
    const varName = node.steps[0].value; // doc, pv, case
    if (['doc', 'pv', 'case'].includes(varName)) {
      const path = node.steps
        .slice(1)
        .map((s: any) => s.value)
        .join('.');
      return { name, mode: 'ref', value: `$${varName}.${path}` };
    }
  }

  // String literal
  if (node.type === 'string') {
    return { name, mode: 'ref', value: `"${node.value}"` };
  }

  // Number literal
  if (node.type === 'number') {
    return { name, mode: 'ref', value: String(node.value) };
  }

  // Boolean literal (value node)
  if (node.type === 'value' && typeof node.value === 'boolean') {
    return { name, mode: 'ref', value: String(node.value) };
  }

  // Nested object
  if (node.type === 'unary' && node.value === '{' && node.lhs) {
    const children = parseObjectEntries(node.lhs, source);
    return { name, mode: 'ref', value: '', children };
  }

  // Anything else: extract raw source text
  const raw = extractSourceFragment(node, source);
  return { name, mode: 'raw', value: raw };
}

/**
 * Extract the source text for a node using position info.
 * Falls back to a generic representation if positions aren't useful.
 */
function extractSourceFragment(node: any, source: string): string {
  // Try to reconstruct from AST for common patterns
  if (node.type === 'condition') {
    const cond = reconstructExpression(node.condition);
    const then = reconstructExpression(node.then);
    const els = reconstructExpression(node.else);
    return `${cond} ? ${then} : ${els}`;
  }
  if (node.type === 'binary') {
    const left = reconstructExpression(node.lhs);
    const right = reconstructExpression(node.rhs);
    return `${left} ${node.value} ${right}`;
  }
  if (node.type === 'function') {
    const name = reconstructExpression(node.procedure);
    const args = (node.arguments || []).map(reconstructExpression).join(', ');
    return `${name}(${args})`;
  }
  // Generic fallback
  return reconstructExpression(node);
}

function reconstructExpression(node: any): string {
  if (!node) return '';
  if (node.type === 'string') return `"${node.value}"`;
  if (node.type === 'number') return String(node.value);
  if (node.type === 'value') return String(node.value);
  if (node.type === 'path' && node.steps) {
    return node.steps.map((s: any) => (s.type === 'variable' ? `$${s.value}` : s.value)).join('.');
  }
  if (node.type === 'binary') {
    return `${reconstructExpression(node.lhs)} ${node.value} ${reconstructExpression(node.rhs)}`;
  }
  if (node.type === 'condition') {
    return `${reconstructExpression(node.condition)} ? ${reconstructExpression(node.then)} : ${reconstructExpression(node.else)}`;
  }
  if (node.type === 'function') {
    const proc = reconstructExpression(node.procedure);
    const args = (node.arguments || []).map(reconstructExpression).join(', ');
    return `${proc}(${args})`;
  }
  if (node.type === 'unary' && node.value === '{') {
    const entries = (node.lhs || [])
      .map(([k, v]: any[]) => `"${k.value}": ${reconstructExpression(v)}`)
      .join(', ');
    return `{${entries}}`;
  }
  return '...';
}

function formatFieldEntry(field: BuilderField, indent: string = '  '): string {
  if (field.children && field.children.length > 0) {
    const childEntries = field.children.map((c) => formatFieldEntry(c, indent + '  ')).join(',\n');
    return `${indent}"${field.name}": {\n${childEntries}\n${indent}}`;
  }

  // Value is already valid JSONata (e.g. $doc.x.y, "string", 42, or raw expression)
  const value = field.value || 'null';
  return `${indent}"${field.name}": ${value}`;
}
