import { extractTaskInstanceIdFromUrl } from './epistola-task-context.matcher';

describe('extractTaskInstanceIdFromUrl', () => {
  const TASK_ID = '01976ff7-479f-11f1-9853-3aac1621ee8c';

  it('captures the taskId from the canonical Valtimo task-open URL', () => {
    expect(extractTaskInstanceIdFromUrl('GET', `/api/v2/process-link/task/${TASK_ID}`)).toBe(
      TASK_ID,
    );
  });

  it('captures the taskId when the URL also has a query string', () => {
    expect(
      extractTaskInstanceIdFromUrl('GET', `/api/v2/process-link/task/${TASK_ID}?foo=bar`),
    ).toBe(TASK_ID);
  });

  it('captures the taskId on an absolute URL', () => {
    expect(
      extractTaskInstanceIdFromUrl(
        'GET',
        `http://localhost:4200/api/v2/process-link/task/${TASK_ID}`,
      ),
    ).toBe(TASK_ID);
  });

  it('returns null for non-GET methods', () => {
    expect(extractTaskInstanceIdFromUrl('POST', `/api/v2/process-link/task/${TASK_ID}`)).toBeNull();
    expect(extractTaskInstanceIdFromUrl('PUT', `/api/v2/process-link/task/${TASK_ID}`)).toBeNull();
    expect(
      extractTaskInstanceIdFromUrl('DELETE', `/api/v2/process-link/task/${TASK_ID}`),
    ).toBeNull();
  });

  it('returns null for unrelated URLs', () => {
    expect(extractTaskInstanceIdFromUrl('GET', '/api/v1/plugin/epistola/admin/health')).toBeNull();
    expect(extractTaskInstanceIdFromUrl('GET', '/api/v2/process-link')).toBeNull();
    expect(extractTaskInstanceIdFromUrl('GET', '/api/v1/process-link/task/12345')).toBeNull();
  });

  it('returns null when the path segment after /task/ is not a UUID', () => {
    expect(extractTaskInstanceIdFromUrl('GET', '/api/v2/process-link/task/not-a-uuid')).toBeNull();
    expect(
      extractTaskInstanceIdFromUrl('GET', '/api/v2/process-link/task/01976ff7-479f-11f1'),
    ).toBeNull();
  });

  it('returns null when extra path segments follow the UUID', () => {
    expect(
      extractTaskInstanceIdFromUrl('GET', `/api/v2/process-link/task/${TASK_ID}/extra`),
    ).toBeNull();
  });
});
