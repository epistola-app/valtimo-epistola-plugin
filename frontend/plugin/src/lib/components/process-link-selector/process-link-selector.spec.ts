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
 * Tests for the process-link-selector logic.
 *
 * Since the test environment is node (no Angular), we test the pure logic
 * (key parsing, selection encoding) without importing the Angular component.
 */
import { PluginUsageEntry } from '../../models';
import { filterGenerateDocumentEntries } from './process-link-selector.util';

interface ProcessLinkSelection {
  processDefinitionKey: string;
  sourceActivityId: string;
}

function makeEntry(overrides: Partial<PluginUsageEntry> = {}): PluginUsageEntry {
  return {
    processLinkId: 'pl-1',
    processDefinitionKey: 'my-process',
    processDefinitionName: 'My Process',
    activityId: 'task1',
    activityName: 'Task 1',
    actionKey: 'epistola-generate-document',
    configurationId: 'cfg-1',
    configurationTitle: 'Config 1',
    problems: [],
    ...overrides,
  };
}

/** Mirrors the component's entryKey() method */
function entryKey(entry: PluginUsageEntry): string {
  return `${entry.processDefinitionKey}::${entry.activityId}`;
}

/** Mirrors the component's onSelect() parsing logic */
function parseSelection(key: string): ProcessLinkSelection | null {
  if (!key) return null;
  const [processDefinitionKey, sourceActivityId] = key.split('::');
  return { processDefinitionKey, sourceActivityId };
}

/** Mirrors the component's restore logic */
function selectionToKey(value: ProcessLinkSelection): string {
  return `${value.processDefinitionKey}::${value.sourceActivityId}`;
}

describe('process-link-selector', () => {
  describe('entryKey()', () => {
    it('should produce processDefinitionKey::activityId format', () => {
      const entry = makeEntry({
        processDefinitionKey: 'order-process',
        activityId: 'generate-invoice',
      });
      expect(entryKey(entry)).toBe('order-process::generate-invoice');
    });

    it('should handle hyphens and underscores in keys', () => {
      const entry = makeEntry({
        processDefinitionKey: 'my-long-process',
        activityId: 'task_with_underscores',
      });
      expect(entryKey(entry)).toBe('my-long-process::task_with_underscores');
    });
  });

  describe('parseSelection()', () => {
    it('should return null for empty string', () => {
      expect(parseSelection('')).toBeNull();
    });

    it('should parse valid key into ProcessLinkSelection', () => {
      const result = parseSelection('my-process::generate-doc');
      expect(result).toEqual({
        processDefinitionKey: 'my-process',
        sourceActivityId: 'generate-doc',
      });
    });

    it('should handle keys with hyphens', () => {
      const result = parseSelection('objection-handling::generate-decision-gegrond');
      expect(result).toEqual({
        processDefinitionKey: 'objection-handling',
        sourceActivityId: 'generate-decision-gegrond',
      });
    });
  });

  describe('selectionToKey()', () => {
    it('should round-trip with parseSelection', () => {
      const original: ProcessLinkSelection = {
        processDefinitionKey: 'my-process',
        sourceActivityId: 'gen-doc',
      };
      const key = selectionToKey(original);
      const parsed = parseSelection(key);
      expect(parsed).toEqual(original);
    });
  });

  describe('filterGenerateDocumentEntries()', () => {
    it('should keep only generate-document entries', () => {
      const entries = [
        makeEntry({ actionKey: 'epistola-generate-document', activityId: 'gen' }),
        makeEntry({ actionKey: 'epistola-check-job-status', activityId: 'check' }),
        makeEntry({ actionKey: 'epistola-download-document', activityId: 'dl' }),
        makeEntry({ actionKey: 'epistola-generate-document', activityId: 'gen2' }),
      ];
      const filtered = filterGenerateDocumentEntries(entries);
      expect(filtered).toHaveLength(2);
      expect(filtered[0].activityId).toBe('gen');
      expect(filtered[1].activityId).toBe('gen2');
    });

    it('should not match the un-prefixed action key', () => {
      const entries = [makeEntry({ actionKey: 'generate-document', activityId: 'gen' })];
      expect(filterGenerateDocumentEntries(entries)).toHaveLength(0);
    });

    it('should return empty for no matches', () => {
      const entries = [makeEntry({ actionKey: 'epistola-check-job-status' })];
      expect(filterGenerateDocumentEntries(entries)).toHaveLength(0);
    });

    it('should return empty for empty input', () => {
      expect(filterGenerateDocumentEntries([])).toHaveLength(0);
    });
  });
});
