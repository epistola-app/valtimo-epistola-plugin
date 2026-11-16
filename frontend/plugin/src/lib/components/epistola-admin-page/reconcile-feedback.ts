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

import { ReconcileOutcome, ReconcileResult } from '../../models';

/** Translation key for each reconcile outcome that means "nothing was delivered". */
const RECONCILE_FAILURE_KEYS: Partial<Record<ReconcileOutcome, string>> = {
  NOT_A_JOB: 'epistolaAdminReconcileNotAJob',
  UNKNOWN_TO_EPISTOLA: 'epistolaAdminReconcileUnknownJob',
  EPISTOLA_ERROR: 'epistolaAdminReconcileEpistolaError',
};

/**
 * What to tell the operator when a reconcile delivered nothing. The backend's `outcome` decides
 * whether waiting, retrying or sending the process back to its generate step helps; a 409 without
 * one (an older backend) still means the job is running.
 */
export function reconcileFailureFeedback(err: {
  status?: number;
  message?: string;
  error?: (Partial<ReconcileResult> & { detail?: string; message?: string }) | null;
}): {
  type: 'pending' | 'error';
  message: string;
  messageKey?: string;
} {
  const outcome = err?.error?.outcome;
  if (outcome === 'STILL_RUNNING' || (!outcome && err?.status === 409)) {
    const status = err?.error?.epistolaStatus ?? 'still pending';
    return { type: 'pending', message: `Epistola: ${status}. Try again in a moment.` };
  }
  const messageKey = outcome ? RECONCILE_FAILURE_KEYS[outcome] : undefined;
  const message = err?.error?.detail ?? err?.error?.message ?? err?.message ?? 'unknown error';
  return messageKey ? { type: 'error', message, messageKey } : { type: 'error', message };
}
