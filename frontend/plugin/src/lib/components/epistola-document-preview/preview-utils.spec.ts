import {
  expandDotNotation,
  computeInputOverrides,
  isExpression,
  FORM_REF_PREFIX,
} from './preview-utils';

describe('preview-utils', () => {
  describe('isExpression', () => {
    it('should detect $ variable references', () => {
      expect(isExpression('$pv.language')).toBe(true);
      expect(isExpression('$doc.name')).toBe(true);
    });

    it('should detect string concatenation', () => {
      expect(isExpression('"besluit-" & $doc.name')).toBe(true);
    });

    it('should detect function calls', () => {
      expect(isExpression('$uppercase($doc.name)')).toBe(true);
    });

    it('should detect conditionals', () => {
      expect(isExpression('$pv.x ? "a" : "b"')).toBe(true);
    });

    it('should detect object literals', () => {
      expect(isExpression('{"key": $pv.val}')).toBe(true);
    });

    it('should return false for plain literals', () => {
      expect(isExpression('output.pdf')).toBe(false);
      expect(isExpression('my-variant')).toBe(false);
      expect(isExpression('letter-formal')).toBe(false);
    });

    it('should return false for empty string', () => {
      expect(isExpression('')).toBe(false);
    });
  });

  describe('expandDotNotation', () => {
    it('should return simple keys unchanged', () => {
      expect(expandDotNotation({ a: 1 })).toEqual({ a: 1 });
    });

    it('should expand nested dot-notation keys', () => {
      expect(expandDotNotation({ 'a.b.c': 1 })).toEqual({ a: { b: { c: 1 } } });
    });

    it('should merge multiple keys at the same level', () => {
      expect(expandDotNotation({ 'a.b': 1, 'a.c': 2 })).toEqual({ a: { b: 1, c: 2 } });
    });

    it('should handle mixed simple and dot-notation keys', () => {
      expect(expandDotNotation({ x: 1, 'a.b': 2 })).toEqual({ x: 1, a: { b: 2 } });
    });

    it('should return empty object for empty input', () => {
      expect(expandDotNotation({})).toEqual({});
    });

    it('should handle deeply nested paths', () => {
      expect(expandDotNotation({ 'a.b.c.d.e': 'deep' })).toEqual({
        a: { b: { c: { d: { e: 'deep' } } } },
      });
    });

    it('should overwrite non-object intermediate values', () => {
      // If "a" is first set to a scalar, then "a.b" tries to nest, it overwrites
      expect(expandDotNotation({ a: 'scalar', 'a.b': 2 })).toEqual({ a: { b: 2 } });
    });
  });

  describe('FORM_REF_PREFIX', () => {
    it('should be "form:"', () => {
      expect(FORM_REF_PREFIX).toBe('form:');
    });
  });

  describe('computeInputOverrides', () => {
    it('should resolve form: references from formData', () => {
      const mapping = { pv: { motivation: 'form:pv:motivation' } };
      const formData = { 'pv:motivation': 'test value' };

      const result = computeInputOverrides(mapping, formData);
      expect(result).toEqual({ pv: { motivation: 'test value' } });
    });

    it('should handle both doc and pv scopes', () => {
      const mapping = {
        doc: { name: 'form:doc:name' },
        pv: { status: 'form:pv:status' },
      };
      const formData = { 'doc:name': 'Alice', 'pv:status': 'approved' };

      const result = computeInputOverrides(mapping, formData);
      expect(result).toEqual({
        doc: { name: 'Alice' },
        pv: { status: 'approved' },
      });
    });

    it('should skip undefined form values (field not filled in yet)', () => {
      const mapping = {
        doc: { name: 'form:nameField', email: 'form:emailField' },
      };
      const formData = { nameField: 'Alice' }; // emailField not present

      const result = computeInputOverrides(mapping, formData);
      expect(result).toEqual({ doc: { name: 'Alice' } });
    });

    it('should return empty object when no overrides match', () => {
      const mapping = { doc: { name: 'form:missingField' } };
      const formData = {};

      const result = computeInputOverrides(mapping, formData);
      expect(result).toEqual({});
    });

    it('should handle values without form: prefix (backward compat)', () => {
      const mapping = { doc: { name: 'legacyFieldKey' } };
      const formData = { legacyFieldKey: 'legacy value' };

      const result = computeInputOverrides(mapping, formData);
      expect(result).toEqual({ doc: { name: 'legacy value' } });
    });

    it('should expand dot-notation in input paths', () => {
      const mapping = { doc: { 'beslissing.tekst': 'form:field1' } };
      const formData = { field1: 'my decision text' };

      const result = computeInputOverrides(mapping, formData);
      expect(result).toEqual({ doc: { beslissing: { tekst: 'my decision text' } } });
    });

    it('should skip invalid scope names (not doc or pv)', () => {
      const mapping = {
        doc: { name: 'form:nameField' },
        case: { owner: 'form:ownerField' },
        custom: { foo: 'form:fooField' },
      };
      const formData = { nameField: 'Alice', ownerField: 'Bob', fooField: 'bar' };

      const result = computeInputOverrides(mapping, formData);
      expect(result).toEqual({ doc: { name: 'Alice' } });
      expect(result).not.toHaveProperty('case');
      expect(result).not.toHaveProperty('custom');
    });

    it('should return empty object for empty mapping', () => {
      const result = computeInputOverrides({}, { someField: 'value' });
      expect(result).toEqual({});
    });

    it('should handle multiple fields in the same scope', () => {
      const mapping = {
        doc: {
          'address.street': 'form:streetField',
          'address.city': 'form:cityField',
          name: 'form:nameField',
        },
      };
      const formData = {
        streetField: 'Main St',
        cityField: 'Amsterdam',
        nameField: 'Alice',
      };

      const result = computeInputOverrides(mapping, formData);
      expect(result).toEqual({
        doc: {
          address: { street: 'Main St', city: 'Amsterdam' },
          name: 'Alice',
        },
      });
    });

    it('should handle falsy but defined form values (empty string, 0, false)', () => {
      const mapping = {
        doc: {
          text: 'form:textField',
          count: 'form:countField',
          active: 'form:activeField',
        },
      };
      const formData = { textField: '', countField: 0, activeField: false };

      const result = computeInputOverrides(mapping, formData);
      expect(result).toEqual({
        doc: { text: '', count: 0, active: false },
      });
    });
  });
});
