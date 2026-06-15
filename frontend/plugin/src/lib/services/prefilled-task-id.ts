/**
 * Helpers for reading the active user task's id out of a Valtimo task form that was
 * prefilled server-side by the {@code epistola-task:} value resolver (see the backend
 * {@code EpistolaTaskValueResolverFactory}).
 *
 * <p>Background: the Epistola Formio components need the id of the user task whose form
 * they're rendered in, to authorize their backend requests ({@code OperatonTask:VIEW}).
 * The HTTP interceptor ({@link EpistolaTaskContextService}) only captures it in the
 * direct task-open flow — the task-list / case-detail flow bulk-fetches process links and
 * never fires the per-task {@code GET /api/v2/process-link/task/{id}} call it sniffs.
 *
 * <p>Form prefill, however, runs server-side in every flow. A form field with
 * {@code properties.sourceKey = "epistola-task:id"} is filled with the task id at prefill
 * time; this helper reads it back from the Formio root — robustly, regardless of how the
 * task was opened.
 */

/** The value-resolver source key that yields the current task id at prefill time. */
export const PREFILLED_TASK_ID_SOURCE_KEY = 'epistola-task:id';

/** Conventional key of the hidden carrier field that holds the prefilled task id. */
export const PREFILLED_TASK_ID_DATA_KEY = 'epistolaTaskInstanceId';

/**
 * Reads the prefilled task id from a Formio webform/wizard root, or null when absent.
 *
 * Looks in two places, in order:
 *  1. The (prefilled) form definition — any component whose {@code properties.sourceKey}
 *     is {@code epistola-task:id} carries the task id in its {@code defaultValue}. This works
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
