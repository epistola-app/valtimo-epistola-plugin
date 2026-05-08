import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';

/**
 * Holds the currently-open Operaton user task instance id so that custom
 * Formio components rendered inside a Valtimo task form (preview, download,
 * retry-form) can include it in backend requests for PBAC.
 *
 * Populated by {@link EpistolaTaskContextInterceptor}, which sniffs Valtimo's
 * canonical "load process link for task" GET (`/api/v2/process-link/task/{taskId}`)
 * — the request always fires when a task opens, before the form renders.
 *
 * <p><b>Why this exists:</b> Valtimo 13.21 does not expose the active task
 * instance id through any service that custom Formio components can inject
 * (`FormIoStateService` carries documentId and processInstanceId only;
 * `TaskDetailContentComponent.taskInstanceId$` is private to that component
 * and Formio elements live in their own injector tree). This service is a
 * workaround until upstream exposes `taskInstanceId` via `FormIoStateService`.
 */
@Injectable({ providedIn: 'root' })
export class EpistolaTaskContextService {
  private readonly _taskInstanceId$ = new BehaviorSubject<string | null>(null);

  readonly taskInstanceId$: Observable<string | null> = this._taskInstanceId$.asObservable();

  get taskInstanceId(): string | null {
    return this._taskInstanceId$.value;
  }

  setTaskInstanceId(id: string | null): void {
    this._taskInstanceId$.next(id);
  }
}
