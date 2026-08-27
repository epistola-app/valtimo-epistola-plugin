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
 * Helpers for reading the active user task's id out of a Valtimo task form that was
 * prefilled server-side by the {@code epistola:} value resolver (see the backend
 * {@code EpistolaTaskValueResolverFactory}).
 *
 * <p>Background: the Epistola Formio components need the id of the user task whose form
 * they're rendered in, to authorize their backend requests ({@code OperatonTask:VIEW}).
 * Valtimo exposes no service that carries the task id to a custom Formio component at
 * runtime, and earlier URL-sniffing only worked in the direct task-open flow (the
 * task-list / case-detail flow bulk-fetches process links and never fires the per-task
 * call).
 *
 * <p>Form prefill, however, runs server-side in every flow. A form field with
 * {@code properties.sourceKey = "epistola:taskId"} is filled with the task id at prefill
 * time (by the backend {@code EpistolaTaskValueResolverFactory}); this helper reads it back
 * from the Formio root — robustly, regardless of how the task was opened.
 */

/** The value-resolver source key that yields the current task id at prefill time. */
export const PREFILLED_TASK_ID_SOURCE_KEY = 'epistola:taskId';

/** Conventional key of the hidden carrier field that holds the prefilled task id. */
export const PREFILLED_TASK_ID_DATA_KEY = 'epistolaTaskId';

/**
 * Hidden Formio child component that carries the prefilled task id. It is embedded as a
 * nested component inside each Epistola task component's schema, so dropping that component
 * brings the carrier with it — the form author never adds a separate field. Valtimo's
 * server-side prefill fills its {@code defaultValue} from the {@code epistola:taskId}
 * value resolver; {@link readPrefilledTaskId} reads it back from the form definition.
 *
 * {@code persistent: false} keeps the value out of the submission, so the task id never
 * lands in the case document / process variables.
 */
export const PREFILLED_TASK_ID_CARRIER = {
  type: 'hidden',
  key: PREFILLED_TASK_ID_DATA_KEY,
  input: true,
  persistent: false,
  label: 'Epistola Task Id',
  properties: { sourceKey: PREFILLED_TASK_ID_SOURCE_KEY },
};

/**
 * Returns a `components` array guaranteed to hold exactly one task-id carrier, preserving
 * any other children already present.
 *
 * <p>Why this exists: Formio's {@code Component.get schema()} serializes only the properties
 * that <b>differ</b> from the registered default schema ({@code getModifiedSchema}), and an
 * array that deep-equals the default is treated as "unmodified" and dropped. Because the
 * carrier is declared in each component's default {@code schema}, every form saved from the
 * Formio builder came out <i>without</i> it — and Valtimo's prefill runs server-side against
 * that stored JSON, where no component class exists to re-apply the default. The task-bound
 * components therefore re-add the carrier after Formio's filter (see
 * {@code withPrefilledTaskIdCarrier} in {@code valtimo-formio-adapter.ts}).
 *
 * <p>Idempotent: hand-authored forms (e.g. the classpath retry form) already carry it. Any extra
 * carriers beyond the first are dropped, so re-saving a form that picked up duplicates repairs it.
 */
export function ensureTaskIdCarrier(components: unknown): any[] {
  const existing = Array.isArray(components) ? components : [];
  let kept = false;
  const deduped = existing.filter((child: unknown) => {
    if (!isTaskIdCarrier(child)) {
      return true;
    }
    if (kept) {
      return false;
    }
    kept = true;
    return true;
  });
  return kept ? deduped : [...deduped, { ...PREFILLED_TASK_ID_CARRIER }];
}

/**
 * Whether a child component is a task-id carrier.
 *
 * <p>Matched on {@code type: 'hidden'} plus the source key, deliberately <b>not</b> on the key.
 * Formio's builder uniquifies keys across a form, so the carrier of the second Epistola component
 * on a form is renamed to {@code epistolaTaskId2}, {@code …3}, and so on. (That rename is
 * harmless — prefill resolves by each field's own key and {@link readPrefilledTaskId} searches by
 * source key — but a key-based match would fail to recognise it and append a duplicate carrier.)
 *
 * <p>The {@code type} check is what keeps this from being too lax: Formio merges a component's
 * default schema into the stored one with {@code _.defaultsDeep}, which merges arrays
 * <i>element-wise</i>, so an unrelated first child of a stored component silently inherits the
 * default carrier's {@code properties.sourceKey}. Such a child keeps its own {@code type}
 * (e.g. {@code textfield}), because {@code defaultsDeep} only fills in what is missing.
 */
function isTaskIdCarrier(child: unknown): boolean {
  const candidate = child as any;
  return (
    candidate?.type === 'hidden' &&
    candidate?.properties?.sourceKey === PREFILLED_TASK_ID_SOURCE_KEY
  );
}

/**
 * Reads the prefilled task id from a Formio webform/wizard root, or null when absent.
 *
 * Looks in two places, in order:
 *  1. The (prefilled) form definition — any component whose {@code properties.sourceKey}
 *     is {@code epistola:taskId} carries the task id in its {@code defaultValue}. This works
 *     even when the carrier is a hidden field that Formio doesn't surface into submission data.
 *  2. The submission data under {@link PREFILLED_TASK_ID_DATA_KEY}, for a rendered sibling
 *     hidden field whose value Formio copied into {@code root.data}.
 */
export function readPrefilledTaskId(root: any): string | null {
  if (!root) {
    return null;
  }

  const fromForm = findSourceKeyDefaultValue(root.form, PREFILLED_TASK_ID_SOURCE_KEY);
  if (typeof fromForm === 'string' && fromForm.length > 0) {
    return fromForm;
  }

  const fromData = root.data?.[PREFILLED_TASK_ID_DATA_KEY];
  if (typeof fromData === 'string' && fromData.length > 0) {
    return fromData;
  }

  return null;
}

/**
 * Deep-walks a form definition node looking for a component whose
 * {@code properties.sourceKey} equals {@code sourceKey}, and returns its
 * {@code defaultValue} (the prefilled value). Returns null when not found.
 */
function findSourceKeyDefaultValue(node: any, sourceKey: string): string | null {
  if (Array.isArray(node)) {
    for (const item of node) {
      const found = findSourceKeyDefaultValue(item, sourceKey);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  if (node && typeof node === 'object') {
    if (node.properties?.sourceKey === sourceKey && typeof node.defaultValue === 'string') {
      return node.defaultValue;
    }
    for (const key of Object.keys(node)) {
      const found = findSourceKeyDefaultValue(node[key], sourceKey);
      if (found != null) {
        return found;
      }
    }
  }

  return null;
}
