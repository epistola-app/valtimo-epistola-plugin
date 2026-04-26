import { parseJsonataToBuilder, builderToJsonata, BuilderField } from './jsonata-converter';

describe('jsonata-converter', () => {
  describe('parseJsonataToBuilder', () => {
    it('should parse simple doc references', () => {
      const expr = '{ "name": $doc.customer.name, "email": $doc.customer.email }';
      const fields = parseJsonataToBuilder(expr);

      expect(fields).toHaveLength(2);
      expect(fields[0]).toEqual({ name: 'name', mode: 'ref', value: 'doc:customer.name' });
      expect(fields[1]).toEqual({ name: 'email', mode: 'ref', value: 'doc:customer.email' });
    });

    it('should parse pv and case references', () => {
      const expr = '{ "status": $pv.approvalStatus, "owner": $case.assignee }';
      const fields = parseJsonataToBuilder(expr);

      expect(fields).toHaveLength(2);
      expect(fields[0]).toEqual({ name: 'status', mode: 'ref', value: 'pv:approvalStatus' });
      expect(fields[1]).toEqual({ name: 'owner', mode: 'ref', value: 'case:assignee' });
    });

    it('should parse string literals', () => {
      const expr = '{ "status": "active" }';
      const fields = parseJsonataToBuilder(expr);

      expect(fields).toHaveLength(1);
      expect(fields[0]).toEqual({ name: 'status', mode: 'ref', value: '"active"' });
    });

    it('should parse nested objects as children', () => {
      const expr = '{ "customer": { "name": $doc.name, "email": $pv.email } }';
      const fields = parseJsonataToBuilder(expr);

      expect(fields).toHaveLength(1);
      expect(fields[0].name).toBe('customer');
      expect(fields[0].mode).toBe('ref');
      expect(fields[0].children).toHaveLength(2);
      expect(fields[0].children![0]).toEqual({ name: 'name', mode: 'ref', value: 'doc:name' });
      expect(fields[0].children![1]).toEqual({ name: 'email', mode: 'ref', value: 'pv:email' });
    });

    it('should fall back to raw for unsupported expressions', () => {
      const expr =
        '{ "name": $doc.first & " " & $doc.last, "type": $doc.isVip ? "premium" : "standard" }';
      const fields = parseJsonataToBuilder(expr);

      expect(fields).toHaveLength(2);
      expect(fields[0].name).toBe('name');
      expect(fields[0].mode).toBe('raw');
      expect(fields[0].value).toContain('&');
      expect(fields[1].name).toBe('type');
      expect(fields[1].mode).toBe('raw');
      expect(fields[1].value).toContain('?');
    });

    it('should return empty array for empty expression', () => {
      expect(parseJsonataToBuilder('')).toEqual([]);
      expect(parseJsonataToBuilder('  ')).toEqual([]);
    });

    it('should handle invalid JSONata gracefully', () => {
      const fields = parseJsonataToBuilder('{ invalid syntax !!!');
      expect(fields).toHaveLength(1);
      expect(fields[0].mode).toBe('raw');
    });
  });

  describe('builderToJsonata', () => {
    it('should generate JSONata from simple refs', () => {
      const fields: BuilderField[] = [
        { name: 'name', mode: 'ref', value: 'doc:customer.name' },
        { name: 'email', mode: 'ref', value: 'pv:email' },
      ];
      const result = builderToJsonata(fields);

      expect(result).toContain('"name": $doc.customer.name');
      expect(result).toContain('"email": $pv.email');
    });

    it('should generate JSONata with nested objects', () => {
      const fields: BuilderField[] = [
        {
          name: 'customer',
          mode: 'ref',
          value: '',
          children: [
            { name: 'name', mode: 'ref', value: 'doc:name' },
            { name: 'city', mode: 'ref', value: 'doc:city' },
          ],
        },
      ];
      const result = builderToJsonata(fields);

      expect(result).toContain('"customer": {');
      expect(result).toContain('"name": $doc.name');
      expect(result).toContain('"city": $doc.city');
    });

    it('should pass through raw values as-is', () => {
      const fields: BuilderField[] = [
        { name: 'name', mode: 'ref', value: 'doc:name' },
        { name: 'fullName', mode: 'raw', value: '$doc.first & " " & $doc.last' },
      ];
      const result = builderToJsonata(fields);

      expect(result).toContain('"name": $doc.name');
      expect(result).toContain('"fullName": $doc.first & " " & $doc.last');
    });

    it('should return empty string for empty fields', () => {
      expect(builderToJsonata([])).toBe('');
    });

    it('should handle string literals', () => {
      const fields: BuilderField[] = [{ name: 'status', mode: 'ref', value: '"active"' }];
      const result = builderToJsonata(fields);

      expect(result).toContain('"status": "active"');
    });
  });

  describe('round-trip', () => {
    it('should round-trip simple references', () => {
      const original = '{\n  "name": $doc.customer.name,\n  "email": $pv.email\n}';
      const fields = parseJsonataToBuilder(original);
      const regenerated = builderToJsonata(fields);

      // Parse both to verify semantic equivalence
      const reparsed = parseJsonataToBuilder(regenerated);
      expect(reparsed).toEqual(fields);
    });
  });
});
