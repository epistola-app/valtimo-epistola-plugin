import { Injectable } from '@angular/core';
import { HttpEvent, HttpHandler, HttpInterceptor, HttpRequest } from '@angular/common/http';
import { Observable } from 'rxjs';
import { EpistolaTaskContextService } from './epistola-task-context.service';
import { extractTaskInstanceIdFromUrl } from './epistola-task-context.matcher';

/**
 * Sniffs Valtimo's task-open signal and pushes the active taskInstanceId into
 * {@link EpistolaTaskContextService}. The signal is the canonical
 * {@code GET /api/v2/process-link/task/{taskId}} call that
 * {@code TaskDetailContentComponent.loadTaskDetails(...)} fires unconditionally
 * before any task form is rendered (see @valtimo/task internals).
 *
 * <p>This interceptor does <b>not</b> modify the outgoing request. It only
 * captures the taskId from the URL.
 *
 * <p>Workaround for Valtimo 13.21 not exposing taskInstanceId through any
 * injectable service. Remove once upstream adds e.g.
 * {@code FormIoStateService.setTaskInstanceId(...)}.
 *
 * <p>The actual URL-matching logic lives in
 * {@link extractTaskInstanceIdFromUrl} so it can be unit-tested without an
 * Angular harness.
 */
@Injectable()
export class EpistolaTaskContextInterceptor implements HttpInterceptor {
  constructor(private readonly taskContext: EpistolaTaskContextService) {}

  intercept(request: HttpRequest<unknown>, next: HttpHandler): Observable<HttpEvent<unknown>> {
    const taskId = extractTaskInstanceIdFromUrl(request.method, request.url);
    if (taskId !== null && taskId !== this.taskContext.taskInstanceId) {
      this.taskContext.setTaskInstanceId(taskId);
    }
    return next.handle(request);
  }
}
