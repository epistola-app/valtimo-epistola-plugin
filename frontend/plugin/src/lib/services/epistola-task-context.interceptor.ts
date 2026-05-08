import { inject, Injectable } from '@angular/core';
import { HttpEvent, HttpHandler, HttpInterceptor, HttpRequest } from '@angular/common/http';
import { Observable } from 'rxjs';
import { EpistolaTaskContextService } from './epistola-task-context.service';

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
 */
@Injectable()
export class EpistolaTaskContextInterceptor implements HttpInterceptor {
  private static readonly TASK_PROCESS_LINK_PATTERN =
    /\/api\/v2\/process-link\/task\/([0-9a-fA-F-]{36})(?:\?|$)/;

  private readonly taskContext = inject(EpistolaTaskContextService);

  intercept(request: HttpRequest<unknown>, next: HttpHandler): Observable<HttpEvent<unknown>> {
    if (request.method === 'GET') {
      const match = EpistolaTaskContextInterceptor.TASK_PROCESS_LINK_PATTERN.exec(request.url);
      if (match) {
        const taskId = match[1];
        if (taskId !== this.taskContext.taskInstanceId) {
          this.taskContext.setTaskInstanceId(taskId);
        }
      }
    }
    return next.handle(request);
  }
}
