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
