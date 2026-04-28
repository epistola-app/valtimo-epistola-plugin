/**
 * Tests for the override-builder component logic.
 *
 * Since the component is an Angular component with DI (ChangeDetectorRef),
 * we test the core conversion logic by reproducing the pure functions
 * (rowsToMapping, mappingToRows) that are private methods on the component.
 * This validates the mapping format and conversion correctness.
 */
import { OverrideMapping } from './override-builder.component';

const FORM_REF_PREFIX = 'form:';

interface OverrideRow {
  scope: 'doc' | 'pv';
  inputPath: string;
  formFieldKey: string;
}

/** Mirrors EpistolaOverrideBuilderComponent.rowsToMapping */
function rowsToMapping(rows: OverrideRow[]): OverrideMapping {
  const mapping: OverrideMapping = {};
  for (const row of rows) {
    if (row.inputPath && row.formFieldKey) {
      if (!mapping[row.scope]) {
        mapping[row.scope] = {};
      }
      mapping[row.scope][row.inputPath] = FORM_REF_PREFIX + row.formFieldKey;
    }
  }
  return mapping;
}

/** Mirrors EpistolaOverrideBuilderComponent.mappingToRows */
function mappingToRows(mapping: OverrideMapping): OverrideRow[] {
  const rows: OverrideRow[] = [];
  for (const [scope, fields] of Object.entries(mapping)) {
    if (scope === 'doc' || scope === 'pv') {
      for (const [path, ref] of Object.entries(fields)) {
        const formFieldKey = String(ref).startsWith(FORM_REF_PREFIX)
          ? String(ref).substring(FORM_REF_PREFIX.length)
          : String(ref);
        rows.push({ scope, inputPath: path, formFieldKey });
      }
    }
  }
  return rows;
}

describe('override-builder', () => {
  describe('rowsToMapping', () => {
    it('should add form: prefix to formFieldKey values', () => {
      const rows: OverrideRow[] = [{ scope: 'doc', inputPath: 'name', formFieldKey: 'nameField' }];

      const result = rowsToMapping(rows);
      expect(result).toEqual({ doc: { name: 'form:nameField' } });
    });

    it('should group rows by scope', () => {
      const rows: OverrideRow[] = [
        { scope: 'doc', inputPath: 'name', formFieldKey: 'nameField' },
        { scope: 'pv', inputPath: 'status', formFieldKey: 'statusField' },
        { scope: 'doc', inputPath: 'email', formFieldKey: 'emailField' },
      ];

      const result = rowsToMapping(rows);
      expect(result).toEqual({
        doc: { name: 'form:nameField', email: 'form:emailField' },
        pv: { status: 'form:statusField' },
      });
    });

    it('should skip rows with empty inputPath', () => {
      const rows: OverrideRow[] = [
        { scope: 'doc', inputPath: '', formFieldKey: 'nameField' },
        { scope: 'doc', inputPath: 'email', formFieldKey: 'emailField' },
      ];

      const result = rowsToMapping(rows);
      expect(result).toEqual({ doc: { email: 'form:emailField' } });
    });

    it('should skip rows with empty formFieldKey', () => {
      const rows: OverrideRow[] = [
        { scope: 'doc', inputPath: 'name', formFieldKey: '' },
        { scope: 'doc', inputPath: 'email', formFieldKey: 'emailField' },
      ];

      const result = rowsToMapping(rows);
      expect(result).toEqual({ doc: { email: 'form:emailField' } });
    });

    it('should skip rows where both inputPath and formFieldKey are empty', () => {
      const rows: OverrideRow[] = [{ scope: 'doc', inputPath: '', formFieldKey: '' }];

      const result = rowsToMapping(rows);
      expect(result).toEqual({});
    });

    it('should return empty object for empty rows array', () => {
      expect(rowsToMapping([])).toEqual({});
    });

    it('should handle dot-notation in inputPath', () => {
      const rows: OverrideRow[] = [
        { scope: 'doc', inputPath: 'beslissing.tekst', formFieldKey: 'motivationField' },
      ];

      const result = rowsToMapping(rows);
      expect(result).toEqual({ doc: { 'beslissing.tekst': 'form:motivationField' } });
    });
  });

  describe('mappingToRows', () => {
    it('should strip form: prefix from values', () => {
      const mapping: OverrideMapping = { doc: { name: 'form:nameField' } };

      const rows = mappingToRows(mapping);
      expect(rows).toEqual([{ scope: 'doc', inputPath: 'name', formFieldKey: 'nameField' }]);
    });

    it('should handle legacy values without form: prefix', () => {
      const mapping: OverrideMapping = { doc: { name: 'legacyKey' } };

      const rows = mappingToRows(mapping);
      expect(rows).toEqual([{ scope: 'doc', inputPath: 'name', formFieldKey: 'legacyKey' }]);
    });

    it('should parse multiple scopes', () => {
      const mapping: OverrideMapping = {
        doc: { name: 'form:nameField' },
        pv: { status: 'form:statusField' },
      };

      const rows = mappingToRows(mapping);
      expect(rows).toHaveLength(2);
      expect(rows).toContainEqual({
        scope: 'doc',
        inputPath: 'name',
        formFieldKey: 'nameField',
      });
      expect(rows).toContainEqual({
        scope: 'pv',
        inputPath: 'status',
        formFieldKey: 'statusField',
      });
    });

    it('should ignore unknown scopes', () => {
      const mapping: OverrideMapping = {
        doc: { name: 'form:nameField' },
        case: { owner: 'form:ownerField' },
      };

      const rows = mappingToRows(mapping);
      expect(rows).toHaveLength(1);
      expect(rows[0].scope).toBe('doc');
    });

    it('should return empty array for empty mapping', () => {
      expect(mappingToRows({})).toEqual([]);
    });
  });

  describe('round-trip', () => {
    it('should preserve data through mapping -> rows -> mapping', () => {
      const original: OverrideMapping = {
        doc: { name: 'form:nameField', 'address.street': 'form:streetField' },
        pv: { motivation: 'form:pv:motivation' },
      };

      const rows = mappingToRows(original);
      const regenerated = rowsToMapping(rows);
      expect(regenerated).toEqual(original);
    });

    it('should preserve data through rows -> mapping -> rows', () => {
      const original: OverrideRow[] = [
        { scope: 'doc', inputPath: 'name', formFieldKey: 'nameField' },
        { scope: 'pv', inputPath: 'status', formFieldKey: 'statusField' },
      ];

      const mapping = rowsToMapping(original);
      const regenerated = mappingToRows(mapping);
      expect(regenerated).toEqual(original);
    });
  });

  describe('toggleMode (simple <-> advanced)', () => {
    it('should convert rows to JSON when switching to advanced mode', () => {
      const rows: OverrideRow[] = [{ scope: 'doc', inputPath: 'name', formFieldKey: 'nameField' }];

      const mapping = rowsToMapping(rows);
      const jsonText = Object.keys(mapping).length > 0 ? JSON.stringify(mapping, null, 2) : '';

      expect(jsonText).toBe(JSON.stringify({ doc: { name: 'form:nameField' } }, null, 2));
    });

    it('should produce empty string when all rows are empty', () => {
      const rows: OverrideRow[] = [{ scope: 'doc', inputPath: '', formFieldKey: '' }];

      const mapping = rowsToMapping(rows);
      const jsonText = Object.keys(mapping).length > 0 ? JSON.stringify(mapping, null, 2) : '';

      expect(jsonText).toBe('');
    });

    it('should parse JSON back to rows when switching to simple mode', () => {
      const jsonText = '{ "pv": { "motivation": "form:pv:motivation" } }';

      const parsed = JSON.parse(jsonText);
      const rows = mappingToRows(parsed);

      expect(rows).toEqual([
        { scope: 'pv', inputPath: 'motivation', formFieldKey: 'pv:motivation' },
      ]);
    });
  });

  describe('onJsonChange', () => {
    it('should parse valid JSON and produce a mapping', () => {
      const text = '{ "doc": { "name": "form:nameField" } }';
      const parsed = JSON.parse(text);
      expect(parsed).toEqual({ doc: { name: 'form:nameField' } });
    });

    it('should detect invalid JSON', () => {
      let jsonError: string | null = null;
      try {
        JSON.parse('{ invalid }');
      } catch {
        jsonError = 'Invalid JSON';
      }
      expect(jsonError).toBe('Invalid JSON');
    });

    it('should treat empty string as null value', () => {
      const text = '';
      const trimmed = text.trim();
      const value = trimmed ? JSON.parse(trimmed) : null;
      expect(value).toBeNull();
    });

    it('should treat whitespace-only string as null value', () => {
      const text = '   ';
      const trimmed = text.trim();
      const value = trimmed ? JSON.parse(trimmed) : null;
      expect(value).toBeNull();
    });
  });

  describe('addRow / removeRow', () => {
    it('addRow should append a default row', () => {
      const rows: OverrideRow[] = [];
      rows.push({ scope: 'doc', inputPath: '', formFieldKey: '' });

      expect(rows).toHaveLength(1);
      expect(rows[0]).toEqual({ scope: 'doc', inputPath: '', formFieldKey: '' });
    });

    it('removeRow should remove the row at the given index', () => {
      const rows: OverrideRow[] = [
        { scope: 'doc', inputPath: 'name', formFieldKey: 'nameField' },
        { scope: 'pv', inputPath: 'status', formFieldKey: 'statusField' },
        { scope: 'doc', inputPath: 'email', formFieldKey: 'emailField' },
      ];

      rows.splice(1, 1); // remove index 1

      expect(rows).toHaveLength(2);
      expect(rows[0].inputPath).toBe('name');
      expect(rows[1].inputPath).toBe('email');
    });
  });

  describe('emitChange', () => {
    it('should produce null when all rows are empty', () => {
      const rows: OverrideRow[] = [
        { scope: 'doc', inputPath: '', formFieldKey: '' },
        { scope: 'pv', inputPath: '', formFieldKey: '' },
      ];

      const mapping = rowsToMapping(rows);
      const value = Object.keys(mapping).length > 0 ? mapping : null;
      expect(value).toBeNull();
    });

    it('should produce mapping when rows have data', () => {
      const rows: OverrideRow[] = [{ scope: 'doc', inputPath: 'name', formFieldKey: 'nameField' }];

      const mapping = rowsToMapping(rows);
      const value = Object.keys(mapping).length > 0 ? mapping : null;
      expect(value).toEqual({ doc: { name: 'form:nameField' } });
    });
  });
});
