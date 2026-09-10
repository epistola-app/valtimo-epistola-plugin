// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: EUPL-1.2

import { parseResourceFromUrl, resolveRouteCommands } from './embed-resource';

const DOCUMENT_ID = '3f6c2a1e-9b4d-4c8a-8e5f-1d2b3c4d5e6f';

describe('resolveRouteCommands', () => {
  it('resolves every static view', () => {
    expect(resolveRouteCommands({ view: 'home' })).toEqual(['/']);
    expect(resolveRouteCommands({ view: 'cases' })).toEqual(['/cases']);
    expect(resolveRouteCommands({ view: 'tasks' })).toEqual(['/tasks']);
    expect(resolveRouteCommands({ view: 'processes' })).toEqual(['/processes']);
    expect(resolveRouteCommands({ view: 'plugins' })).toEqual(['/plugins']);
    expect(resolveRouteCommands({ view: 'process-links' })).toEqual(['/process-links']);
    expect(resolveRouteCommands({ view: 'epistola-admin' })).toEqual(['/epistola']);
  });

  it('resolves a case type', () => {
    expect(
      resolveRouteCommands({ view: 'case-type', caseDefinitionKey: 'form-flow-demo' }),
    ).toEqual(['/cases', 'form-flow-demo']);
  });

  it('accepts the training facility’s generated per-trainee keys', () => {
    // TraineeKeys.caseDefinitionKey: "t" + 15 hex chars, no hyphens.
    expect(
      resolveRouteCommands({ view: 'case-type', caseDefinitionKey: 't3f9a2b1c8d0e4f7' }),
    ).toEqual(['/cases', 't3f9a2b1c8d0e4f7']);
  });

  it('resolves a case with and without a tab', () => {
    expect(
      resolveRouteCommands({
        view: 'case',
        caseDefinitionKey: 'form-flow-demo',
        documentId: DOCUMENT_ID,
      }),
    ).toEqual(['/cases', 'form-flow-demo', 'document', DOCUMENT_ID]);

    expect(
      resolveRouteCommands({
        view: 'case',
        caseDefinitionKey: 'form-flow-demo',
        documentId: DOCUMENT_ID,
        tab: 'summary',
      }),
    ).toEqual(['/cases', 'form-flow-demo', 'document', DOCUMENT_ID, 'summary']);
  });

  it('resolves a task inside a case', () => {
    expect(
      resolveRouteCommands({
        view: 'task',
        caseDefinitionKey: 'form-flow-demo',
        documentId: DOCUMENT_ID,
        tab: 'summary',
        taskId: 'e1a2b3c4',
      }),
    ).toEqual([
      '/cases',
      'form-flow-demo',
      'document',
      DOCUMENT_ID,
      'summary',
      'tasks',
      'e1a2b3c4',
    ]);
  });

  it('requires a tab for a task, which has no shorter route', () => {
    expect(
      resolveRouteCommands({
        view: 'task',
        caseDefinitionKey: 'form-flow-demo',
        documentId: DOCUMENT_ID,
        taskId: 'e1a2b3c4',
      }),
    ).toBeNull();
  });

  it('rejects an unknown or missing view', () => {
    expect(resolveRouteCommands({ view: 'admin-everything' })).toBeNull();
    expect(resolveRouteCommands({})).toBeNull();
    expect(resolveRouteCommands(null)).toBeNull();
    expect(resolveRouteCommands('home')).toBeNull();
    expect(resolveRouteCommands(undefined)).toBeNull();
  });

  it('does not resolve inherited Object properties as views', () => {
    expect(resolveRouteCommands({ view: '__proto__' })).toBeNull();
    expect(resolveRouteCommands({ view: 'toString' })).toBeNull();
    expect(resolveRouteCommands({ view: 'constructor' })).toBeNull();
  });

  it('rejects identifiers that could add structure to the path', () => {
    const bad = [
      '..',
      '../admin',
      'a/b',
      'a?b',
      'a#b',
      'a.b',
      '',
      '%2e%2e',
      'a b',
      '-leading-hyphen',
    ];
    for (const caseDefinitionKey of bad) {
      expect(resolveRouteCommands({ view: 'case-type', caseDefinitionKey }))
        .withContext(caseDefinitionKey)
        .toBeNull();
    }
  });

  it('requires a real UUID for the document id', () => {
    for (const documentId of ['not-a-uuid', '../../etc', DOCUMENT_ID + '/extra', '', 42]) {
      expect(resolveRouteCommands({ view: 'case', caseDefinitionKey: 'demo', documentId }))
        .withContext(String(documentId))
        .toBeNull();
    }
  });

  it('rejects a malformed tab rather than dropping it and navigating anyway', () => {
    expect(
      resolveRouteCommands({
        view: 'case',
        caseDefinitionKey: 'demo',
        documentId: DOCUMENT_ID,
        tab: '../../plugins',
      }),
    ).toBeNull();
  });

  it('returns a fresh array so a caller cannot mutate the lookup table', () => {
    const first = resolveRouteCommands({ view: 'home' })!;
    first.push('injected');
    expect(resolveRouteCommands({ view: 'home' })).toEqual(['/']);
  });
});

describe('parseResourceFromUrl', () => {
  it('recognises the static views', () => {
    expect(parseResourceFromUrl('/')).toEqual({ view: 'home' });
    expect(parseResourceFromUrl('/cases')).toEqual({ view: 'cases' });
    expect(parseResourceFromUrl('/tasks')).toEqual({ view: 'tasks' });
    expect(parseResourceFromUrl('/epistola')).toEqual({ view: 'epistola-admin' });
    expect(parseResourceFromUrl('/process-links')).toEqual({ view: 'process-links' });
  });

  it('recognises a case type, a case, and a task', () => {
    expect(parseResourceFromUrl('/cases/form-flow-demo')).toEqual({
      view: 'case-type',
      caseDefinitionKey: 'form-flow-demo',
    });

    expect(parseResourceFromUrl(`/cases/form-flow-demo/document/${DOCUMENT_ID}`)).toEqual({
      view: 'case',
      caseDefinitionKey: 'form-flow-demo',
      documentId: DOCUMENT_ID,
    });

    expect(parseResourceFromUrl(`/cases/form-flow-demo/document/${DOCUMENT_ID}/summary`)).toEqual({
      view: 'case',
      caseDefinitionKey: 'form-flow-demo',
      documentId: DOCUMENT_ID,
      tab: 'summary',
    });

    expect(
      parseResourceFromUrl(`/cases/form-flow-demo/document/${DOCUMENT_ID}/summary/tasks/abc123`),
    ).toEqual({
      view: 'task',
      caseDefinitionKey: 'form-flow-demo',
      documentId: DOCUMENT_ID,
      tab: 'summary',
      taskId: 'abc123',
    });
  });

  it('ignores query string and fragment', () => {
    expect(parseResourceFromUrl('/cases/form-flow-demo?page=2#top')).toEqual({
      view: 'case-type',
      caseDefinitionKey: 'form-flow-demo',
    });
  });

  it('returns null for routes outside the vocabulary', () => {
    // A normal outcome — `navigated` still carries the raw path.
    expect(parseResourceFromUrl('/access-control/17/summary')).toBeNull();
    expect(parseResourceFromUrl('/form-management/abc')).toBeNull();
    expect(parseResourceFromUrl('/cases/demo/document')).toBeNull();
    expect(parseResourceFromUrl('/cases/demo/document/not-a-uuid/summary')).toBeNull();
    expect(parseResourceFromUrl(`/cases/demo/document/${DOCUMENT_ID}/summary/tasks`)).toBeNull();
  });

  it('round-trips everything resolveRouteCommands can build', () => {
    const resources = [
      { view: 'home' },
      { view: 'cases' },
      { view: 'tasks' },
      { view: 'epistola-admin' },
      { view: 'case-type', caseDefinitionKey: 'form-flow-demo' },
      { view: 'case', caseDefinitionKey: 'form-flow-demo', documentId: DOCUMENT_ID },
      { view: 'case', caseDefinitionKey: 'form-flow-demo', documentId: DOCUMENT_ID, tab: 'notes' },
      {
        view: 'task',
        caseDefinitionKey: 'form-flow-demo',
        documentId: DOCUMENT_ID,
        tab: 'notes',
        taskId: 'abc123',
      },
    ];

    for (const resource of resources) {
      const commands = resolveRouteCommands(resource)!;
      expect(commands).withContext(JSON.stringify(resource)).not.toBeNull();
      const url = commands.join('/').replace(/^\/\//, '/');
      expect(parseResourceFromUrl(url))
        .withContext(url)
        .toEqual(resource as never);
    }
  });
});
