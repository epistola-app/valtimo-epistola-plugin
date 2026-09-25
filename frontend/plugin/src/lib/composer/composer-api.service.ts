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

import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ConfigService } from '@valtimo/shared';
import { CatalogInfo, TemplateInfo } from '../models';
import { EpistolaPluginService } from '../services';

/** One configured Epistola connection, as offered to an author. */
export interface PluginConfigurationInfo {
  id: string;
  title: string;
  tenantId: string | null;
}

/**
 * Body of a {@link EpistolaComposerApiService.composerPrepare} call. The browser names the task and one
 * of the templates its form offers; everything else — the catalog, the mapping, the case — is read
 * server-side from the form definition behind that task (ADR 0006).
 */
export interface ComposerPrepareRequest {
  taskId: string;
  templateId: string;
}

/** A letter ready to be filled in: the mapped data plus a form for whatever the mapping left empty. */
export interface ComposerPrepareResponse {
  templateId: string;
  label: string;
  catalogId: string;
  data: Record<string, unknown>;
  form: any;
  /** True when the mapping filled everything, so the employee is asked nothing. */
  complete: boolean;
}

/** Body of a {@link EpistolaComposerApiService.composerPreviewToBlob} call. */
export interface ComposerPreviewRequest {
  taskId: string;
  templateId: string;
  data: Record<string, unknown>;
}

/**
 * The same two calls from a start form, where no task exists yet: an ad-hoc letter on an open
 * dossier. The process is named by its version-stable key, and the case by the id a server-side
 * value resolver prefilled into the form.
 */
export interface ComposerStartPrepareRequest {
  processDefinitionKey: string;
  documentId?: string | null;
  templateId: string;
}

/** Body of a {@link EpistolaComposerApiService.composerPreviewStartToBlob} call. */
export interface ComposerStartPreviewRequest extends ComposerStartPrepareRequest {
  data: Record<string, unknown>;
}

/**
 * Everything the letter composer calls, in one service.
 *
 * <p>Kept apart from {@link EpistolaPluginService} on purpose: the composer is a feature of its own,
 * and its endpoints mean nothing to the rest of the plugin. The catalogs and templates it needs for
 * its settings widget come from the shared service rather than being fetched twice, so the composer
 * has exactly one seam into the rest of the plugin.
 */
@Injectable({ providedIn: 'root' })
export class EpistolaComposerApiService {
  private readonly apiEndpoint: string;

  constructor(
    private readonly http: HttpClient,
    private readonly configService: ConfigService,
    private readonly epistolaPluginService: EpistolaPluginService,
  ) {
    this.apiEndpoint = `${this.configService.config.valtimoApi.endpointUri}v1/plugin/epistola`;
  }

  /**
   * The Epistola connections an author can choose from. Used where there is no process link to
   * inherit one from — the letter composer's settings live in a form, not on a service task.
   */
  getConfigurations(): Observable<PluginConfigurationInfo[]> {
    return this.http.get<PluginConfigurationInfo[]>(`${this.apiEndpoint}/configurations`);
  }

  /**
   * Resolve a composer letter for the caller's task: what the mapping produced, and a Formio form
   * asking for the template fields it left empty.
   */
  composerPrepare(request: ComposerPrepareRequest): Observable<ComposerPrepareResponse> {
    return this.http.post<ComposerPrepareResponse>(`${this.apiEndpoint}/composer/prepare`, request);
  }

  /**
   * Render a preview of a composed letter with the data assembled so far. Same
   * {@code X-Skip-Interceptor: 422} treatment as the other previews, so a template that refuses
   * this data shows inline instead of as a toast.
   */
  composerPreviewToBlob(request: ComposerPreviewRequest): Observable<Blob> {
    return this.http.post(`${this.apiEndpoint}/composer/preview`, request, {
      responseType: 'blob',
      headers: new HttpHeaders().set('X-Skip-Interceptor', '422'),
    });
  }

  /** {@link composerPrepare} for a letter composed on a start form. */
  composerPrepareStart(request: ComposerStartPrepareRequest): Observable<ComposerPrepareResponse> {
    return this.http.post<ComposerPrepareResponse>(
      `${this.apiEndpoint}/composer/prepare/start`,
      request,
    );
  }

  /** {@link composerPreviewToBlob} for a letter composed on a start form. */
  composerPreviewStartToBlob(request: ComposerStartPreviewRequest): Observable<Blob> {
    return this.http.post(`${this.apiEndpoint}/composer/preview/start`, request, {
      responseType: 'blob',
      headers: new HttpHeaders().set('X-Skip-Interceptor', '422'),
    });
  }

  /** The catalogs of a connection, as the settings widget offers them. */
  getCatalogs(pluginConfigurationId: string): Observable<CatalogInfo[]> {
    return this.epistolaPluginService.getCatalogs(pluginConfigurationId);
  }

  /** The templates of a catalog, as the settings widget offers them. */
  getTemplates(pluginConfigurationId: string, catalogId: string): Observable<TemplateInfo[]> {
    return this.epistolaPluginService.getTemplates(pluginConfigurationId, catalogId);
  }
}
