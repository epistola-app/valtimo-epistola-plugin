/**
 * Pure helpers for {@link EpistolaTaskContextInterceptor}, extracted so they
 * can be unit-tested without pulling in {@code @angular/core} (which ts-jest
 * cannot transform without {@code jest-preset-angular}).
 */

/**
 * Pattern Valtimo uses for the canonical task-open call:
 * {@code GET /api/v2/process-link/task/{taskInstanceId}}.
 *
 * Captures the {@code taskInstanceId} (UUID v4-style 36-character hyphenated
 * hex string). Anchored at the end of the URL or at a query-string delimiter
 * so we don't accidentally match a longer trailing segment.
 */
export const TASK_PROCESS_LINK_PATTERN =
  /\/api\/v2\/process-link\/task\/([0-9a-fA-F-]{36})(?:\?|$)/;

/**
 * Returns the captured {@code taskInstanceId} from a Valtimo task-open
 * request URL, or {@code null} if the request does not match.
 */
export function extractTaskInstanceIdFromUrl(method: string, url: string): string | null {
  if (method !== 'GET') return null;
  const match = TASK_PROCESS_LINK_PATTERN.exec(url);
  return match ? match[1] : null;
}
