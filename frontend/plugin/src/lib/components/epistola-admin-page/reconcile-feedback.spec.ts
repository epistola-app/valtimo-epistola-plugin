// The specification is a plain object, but it imports the Angular components it names, which are
// not loadable under ts-jest — stubbed away, as in composer.translations.spec.ts.
jest.mock('@valtimo/plugin', () => ({}));
jest.mock(
  '../../composer/configuration/generate-composed-document-configuration.component',
  () => ({ GenerateComposedDocumentConfigurationComponent: class {} }),
);
jest.mock('../epistola-configuration/epistola-configuration.component', () => ({
  EpistolaConfigurationComponent: class {},
}));
jest.mock('../generate-document-configuration/generate-document-configuration.component', () => ({
  GenerateDocumentConfigurationComponent: class {},
}));
jest.mock('../check-job-status-configuration/check-job-status-configuration.component', () => ({
  CheckJobStatusConfigurationComponent: class {},
}));
jest.mock('../download-document-configuration/download-document-configuration.component', () => ({
  DownloadDocumentConfigurationComponent: class {},
}));

import { reconcileFailureFeedback } from './reconcile-feedback';
import { epistolaPluginSpecification } from '../../epistola.specification';

describe('reconcileFailureFeedback', () => {
  it('says the job is still running for STILL_RUNNING', () => {
    const feedback = reconcileFailureFeedback({
      status: 409,
      error: { outcome: 'STILL_RUNNING', epistolaStatus: 'IN_PROGRESS' },
    });

    expect(feedback.type).toBe('pending');
    expect(feedback.message).toContain('IN_PROGRESS');
  });

  it('still reads a bare 409 from an older backend as running', () => {
    const feedback = reconcileFailureFeedback({
      status: 409,
      error: { epistolaStatus: 'PENDING' },
    });

    expect(feedback.type).toBe('pending');
  });

  it.each([
    ['NOT_A_JOB', 422, 'epistolaAdminReconcileNotAJob'],
    ['UNKNOWN_TO_EPISTOLA', 422, 'epistolaAdminReconcileUnknownJob'],
    ['EPISTOLA_ERROR', 502, 'epistolaAdminReconcileEpistolaError'],
  ] as const)('names %s with a translated message', (outcome, status, key) => {
    const feedback = reconcileFailureFeedback({ status, error: { outcome } });

    expect(feedback).toEqual(expect.objectContaining({ type: 'error', messageKey: key }));
  });

  it("falls back to the server's message when there is no outcome", () => {
    const feedback = reconcileFailureFeedback({
      status: 400,
      error: { detail: 'Execution x has no epistolaWaitFor variable' },
    });

    expect(feedback).toEqual({
      type: 'error',
      message: 'Execution x has no epistolaWaitFor variable',
    });
  });

  it('has both translations for every message it can name', () => {
    const keys = [
      'epistolaAdminReconcileNotAJob',
      'epistolaAdminReconcileUnknownJob',
      'epistolaAdminReconcileEpistolaError',
    ];
    const translations = epistolaPluginSpecification.pluginTranslations as Record<
      string,
      Record<string, string>
    >;

    for (const language of ['nl', 'en']) {
      for (const key of keys) {
        expect(translations[language][key]).toBeTruthy();
      }
    }
  });
});
