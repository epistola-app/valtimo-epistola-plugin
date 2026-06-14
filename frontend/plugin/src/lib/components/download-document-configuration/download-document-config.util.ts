import { DownloadDocumentConfig } from '../../models';

/**
 * Pure helpers for the download-document configurator, extracted so they can be unit-tested
 * without the Angular component (mirrors the process-link-selector util pattern). See
 * `docs/adr/0001-download-document-content-storage.md`.
 */

export type StorageTarget = 'TEMPORARY_RESOURCE' | 'PROCESS_VARIABLE';

export const DEFAULT_STORAGE_TARGET: StorageTarget = 'TEMPORARY_RESOURCE';

/** Normalize a (possibly undefined) storageTarget to a concrete target, applying the default. */
export function resolveStorageTarget(target?: string): StorageTarget {
  return target === 'PROCESS_VARIABLE' ? 'PROCESS_VARIABLE' : DEFAULT_STORAGE_TARGET;
}

/**
 * A config is valid when the input variable and the output variable that matches the chosen
 * storage target are both set.
 */
export function isDownloadDocumentConfigValid(
  config: Partial<DownloadDocumentConfig> | null | undefined,
): boolean {
  if (!config?.documentVariable) {
    return false;
  }
  return resolveStorageTarget(config.storageTarget) === 'PROCESS_VARIABLE'
    ? !!config.contentVariable
    : !!config.resourceIdVariable;
}
