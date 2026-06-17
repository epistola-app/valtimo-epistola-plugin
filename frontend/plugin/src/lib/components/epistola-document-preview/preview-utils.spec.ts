import {
  expandDotNotation,
  computeInputOverrides,
  isExpression,
  isOverrideDriven,
  hasUsableOverrides,
  shouldLoadPreview,
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

  describe('isOverrideDriven', () => {
    it('is true for a non-empty JSONata string mapping', () => {
      expect(isOverrideDriven('{ "doc": { "name": $form.x } }')).toBe(true);
    });

    it('is true for a non-empty legacy object mapping', () => {
      expect(isOverrideDriven({ doc: { name: 'form:nameField' } })).toBe(true);
    });

    it('is false for an empty string, empty object, null, or undefined', () => {
      expect(isOverrideDriven('')).toBe(false);
      expect(isOverrideDriven('   ')).toBe(false);
      expect(isOverrideDriven({})).toBe(false);
      expect(isOverrideDriven(null)).toBe(false);
      expect(isOverrideDriven(undefined)).toBe(false);
    });
  });

  describe('hasUsableOverrides', () => {
    it('is true when overrides contain data', () => {
      expect(hasUsableOverrides({ doc: { name: 'Alice' } })).toBe(true);
    });

    it('is false for empty object, null, or undefined', () => {
      expect(hasUsableOverrides({})).toBe(false);
      expect(hasUsableOverrides(null)).toBe(false);
      expect(hasUsableOverrides(undefined)).toBe(false);
    });
  });

  describe('shouldLoadPreview', () => {
    it('always loads a preview that has no override mapping (base-data driven)', () => {
      expect(shouldLoadPreview(undefined, null)).toBe(true);
      expect(shouldLoadPreview({}, null)).toBe(true);
    });

    it('waits for overrides when the preview is override-driven', () => {
      const mapping = { doc: { name: 'form:nameField' } };
      expect(shouldLoadPreview(mapping, null)).toBe(false);
      expect(shouldLoadPreview(mapping, {})).toBe(false);
      expect(shouldLoadPreview(mapping, { doc: { name: 'Alice' } })).toBe(true);
    });
  });

  describe('FORM_REF_PREFIX', () => {
    it('should be "form:"', () => {
      expect(FORM_REF_PREFIX).toBe('form:');
    });
  });

  describe('computeInputOverrides — JSONata string mapping', () => {
    it('evaluates a $form expression into a {doc,pv} overlay', async () => {
      const mapping = '{ "pv": { "motivation": $form.`pv:motivation` } }';
      const formData = { 'pv:motivation': 'test value' };

      expect(await computeInputOverrides(mapping, formData)).toEqual({
        pv: { motivation: 'test value' },
      });
    });

    it('supports transforms on form values (concat)', async () => {
      const mapping = `{ "doc": { "naam": $form.voornaam & ' ' & $form.achternaam } }`;
      const formData = { voornaam: 'Ada', achternaam: 'Lovelace' };

      expect(await computeInputOverrides(mapping, formData)).toEqual({
        doc: { naam: 'Ada Lovelace' },
      });
    });

    it('omits scopes with no resolved fields', async () => {
      const mapping = '{ "doc": { "name": $form.missing } }';
      expect(await computeInputOverrides(mapping, {})).toEqual({});
    });

    it('keeps falsy but defined form values', async () => {
      const mapping = '{ "doc": { "t": $form.t, "n": $form.n, "f": $form.f } }';
      const formData = { t: '', n: 0, f: false };
      expect(await computeInputOverrides(mapping, formData)).toEqual({
        doc: { t: '', n: 0, f: false },
      });
    });

    it('drops scopes other than doc/pv', async () => {
      const mapping = '{ "doc": { "x": $form.x }, "case": { "y": $form.y } }';
      const result = await computeInputOverrides(mapping, { x: 'a', y: 'b' });
      expect(result).toEqual({ doc: { x: 'a' } });
    });

    it('returns {} for a malformed expression', async () => {
      expect(await computeInputOverrides('{ not valid ::', { x: 1 })).toEqual({});
    });

    it('returns {} for an empty/blank mapping', async () => {
      expect(await computeInputOverrides('', { x: 1 })).toEqual({});
      expect(await computeInputOverrides(null, { x: 1 })).toEqual({});
    });
  });

  describe('computeInputOverrides — legacy object mapping (backward compat)', () => {
    it('resolves legacy form: references', async () => {
      const mapping = { pv: { motivation: 'form:pv:motivation' } };
      const formData = { 'pv:motivation': 'test value' };
      expect(await computeInputOverrides(mapping, formData)).toEqual({
        pv: { motivation: 'test value' },
      });
    });

    it('handles both doc and pv scopes', async () => {
      const mapping = { doc: { name: 'form:doc:name' }, pv: { status: 'form:pv:status' } };
      const formData = { 'doc:name': 'Alice', 'pv:status': 'approved' };
      expect(await computeInputOverrides(mapping, formData)).toEqual({
        doc: { name: 'Alice' },
        pv: { status: 'approved' },
      });
    });

    it('skips undefined form values (field not filled in yet)', async () => {
      const mapping = { doc: { name: 'form:nameField', email: 'form:emailField' } };
      const formData = { nameField: 'Alice' };
      expect(await computeInputOverrides(mapping, formData)).toEqual({ doc: { name: 'Alice' } });
    });

    it('handles values without the form: prefix', async () => {
      const mapping = { doc: { name: 'legacyFieldKey' } };
      const formData = { legacyFieldKey: 'legacy value' };
      expect(await computeInputOverrides(mapping, formData)).toEqual({
        doc: { name: 'legacy value' },
      });
    });

    it('expands dot-notation input paths into nested objects', async () => {
      const mapping = { doc: { 'beslissing.tekst': 'form:field1' } };
      const formData = { field1: 'my decision text' };
      expect(await computeInputOverrides(mapping, formData)).toEqual({
        doc: { beslissing: { tekst: 'my decision text' } },
      });
    });

    it('drops scopes other than doc/pv', async () => {
      const mapping = {
        doc: { name: 'form:nameField' },
        case: { owner: 'form:ownerField' },
        custom: { foo: 'form:fooField' },
      };
      const formData = { nameField: 'Alice', ownerField: 'Bob', fooField: 'bar' };
      const result = await computeInputOverrides(mapping, formData);
      expect(result).toEqual({ doc: { name: 'Alice' } });
    });

    it('returns {} for an empty mapping', async () => {
      expect(await computeInputOverrides({}, { someField: 'value' })).toEqual({});
    });
  });
});
