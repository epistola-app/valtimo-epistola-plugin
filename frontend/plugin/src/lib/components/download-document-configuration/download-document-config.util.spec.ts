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

import {
  DEFAULT_STORAGE_TARGET,
  isDownloadDocumentConfigValid,
  resolveStorageTarget,
} from './download-document-config.util';

describe('download-document-config.util', () => {
  describe('resolveStorageTarget', () => {
    it('defaults to TEMPORARY_RESOURCE when undefined', () => {
      expect(resolveStorageTarget(undefined)).toBe('TEMPORARY_RESOURCE');
      expect(DEFAULT_STORAGE_TARGET).toBe('TEMPORARY_RESOURCE');
    });

    it('passes through known targets', () => {
      expect(resolveStorageTarget('PROCESS_VARIABLE')).toBe('PROCESS_VARIABLE');
      expect(resolveStorageTarget('TEMPORARY_RESOURCE')).toBe('TEMPORARY_RESOURCE');
    });

    it('falls back to the default for unknown values', () => {
      expect(resolveStorageTarget('SOMETHING_ELSE')).toBe('TEMPORARY_RESOURCE');
    });
  });

  describe('isDownloadDocumentConfigValid', () => {
    it('requires documentVariable', () => {
      expect(isDownloadDocumentConfigValid(null)).toBe(false);
      expect(isDownloadDocumentConfigValid({ resourceIdVariable: 'r' })).toBe(false);
    });

    it('TEMPORARY_RESOURCE (incl. default) needs resourceIdVariable', () => {
      expect(isDownloadDocumentConfigValid({ documentVariable: 'epistolaResult' })).toBe(false); // default target, no resourceIdVariable
      expect(
        isDownloadDocumentConfigValid({
          documentVariable: 'epistolaResult',
          resourceIdVariable: 'documentResourceId',
        }),
      ).toBe(true);
      expect(
        isDownloadDocumentConfigValid({
          documentVariable: 'epistolaResult',
          storageTarget: 'TEMPORARY_RESOURCE',
          resourceIdVariable: 'documentResourceId',
        }),
      ).toBe(true);
    });

    it('PROCESS_VARIABLE needs contentVariable', () => {
      expect(
        isDownloadDocumentConfigValid({
          documentVariable: 'epistolaResult',
          storageTarget: 'PROCESS_VARIABLE',
        }),
      ).toBe(false);
      expect(
        isDownloadDocumentConfigValid({
          documentVariable: 'epistolaResult',
          storageTarget: 'PROCESS_VARIABLE',
          contentVariable: 'documentContent',
        }),
      ).toBe(true);
    });

    it('does not accept the wrong output variable for the target', () => {
      // resourceIdVariable set but target is PROCESS_VARIABLE → still invalid
      expect(
        isDownloadDocumentConfigValid({
          documentVariable: 'epistolaResult',
          storageTarget: 'PROCESS_VARIABLE',
          resourceIdVariable: 'documentResourceId',
        }),
      ).toBe(false);
    });
  });
});
