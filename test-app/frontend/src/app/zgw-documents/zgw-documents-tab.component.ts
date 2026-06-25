import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Component, OnInit } from '@angular/core';
import { ActivatedRoute, ActivatedRouteSnapshot } from '@angular/router';
import { ConfigService } from '@valtimo/shared';

/**
 * Documents tab for the ZGW / Documenten-API upload provider.
 *
 * OSS Valtimo defines the `ZGW_DOCUMENTEN_API_DOCUMENTS_COMPONENT_TOKEN` extension point but
 * ships no component for it, so the standard documents tab renders "not found" under
 * `UploadProvider.DOCUMENTEN_API`. This fills that slot: it lists the case's ZGW documents
 * (the zaak's zaakinformatieobjecten produced by the Epistola subsidy flow's
 * store-temp-document + link-document-to-zaak steps) via the existing backend endpoints — no
 * custom backend, no ResourceService.
 *
 * Registered in AppModule:
 *   {provide: ZGW_DOCUMENTEN_API_DOCUMENTS_COMPONENT_TOKEN, useValue: ZgwDocumentsTabComponent}
 */
interface ZgwDocument {
  fileId: string;
  pluginConfigurationId: string;
  bestandsnaam: string;
  titel: string;
  auteur: string;
  status: string;
  bestandsomvang: number;
  creatiedatum: string;
}

@Component({
  selector: 'app-zgw-documents-tab',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="zgw-documents" data-testid="zgw-documents-tab">
      <ng-container *ngIf="!loading; else loadingTpl">
        <p *ngIf="error" class="zgw-documents__error" data-testid="zgw-documents-error">
          {{ error }}
        </p>
        <p
          *ngIf="!error && documents.length === 0"
          class="zgw-documents__empty"
          data-testid="zgw-documents-empty"
        >
          Geen documenten gekoppeld aan deze zaak.
        </p>
        <table
          *ngIf="!error && documents.length > 0"
          class="zgw-documents__table"
          data-testid="zgw-documents-table"
        >
          <thead>
            <tr>
              <th>Titel</th>
              <th>Bestandsnaam</th>
              <th>Auteur</th>
              <th>Status</th>
              <th>Grootte</th>
              <th>Datum</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr
              *ngFor="let doc of documents"
              [attr.data-testid]="'zgw-documents-row-' + doc.fileId"
            >
              <td>{{ doc.titel || doc.bestandsnaam }}</td>
              <td>{{ doc.bestandsnaam }}</td>
              <td>{{ doc.auteur }}</td>
              <td>{{ doc.status }}</td>
              <td>{{ formatSize(doc.bestandsomvang) }}</td>
              <td>{{ doc.creatiedatum | date: 'yyyy-MM-dd' }}</td>
              <td>
                <button
                  type="button"
                  class="zgw-documents__download"
                  (click)="download(doc)"
                  [attr.data-testid]="'zgw-documents-download-' + doc.fileId"
                >
                  Download
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </ng-container>
      <ng-template #loadingTpl>
        <p data-testid="zgw-documents-loading">Documenten laden…</p>
      </ng-template>
    </div>
  `,
  styles: [
    `
      .zgw-documents {
        padding: 1rem;
      }
      .zgw-documents__table {
        width: 100%;
        border-collapse: collapse;
      }
      .zgw-documents__table th,
      .zgw-documents__table td {
        text-align: left;
        padding: 0.5rem 0.75rem;
        border-bottom: 1px solid #e0e0e0;
      }
      .zgw-documents__download {
        cursor: pointer;
        background: none;
        border: none;
        color: #0f62fe;
        text-decoration: underline;
        padding: 0;
      }
      .zgw-documents__error {
        color: #da1e28;
      }
    `,
  ],
})
export class ZgwDocumentsTabComponent implements OnInit {
  public documents: ZgwDocument[] = [];
  public loading = true;
  public error = '';

  private readonly api = this.configService.config.valtimoApi.endpointUri;
  private readonly documentId = this.resolveDocumentId();

  constructor(
    private readonly http: HttpClient,
    private readonly route: ActivatedRoute,
    private readonly configService: ConfigService,
  ) {}

  public ngOnInit(): void {
    if (!this.documentId) {
      this.error = 'Geen document-id gevonden.';
      this.loading = false;
      return;
    }
    this.http
      .get<{ content: ZgwDocument[] }>(`${this.api}v2/zaken-api/document/${this.documentId}/files`)
      .subscribe({
        next: (response) => {
          this.documents = response?.content ?? [];
          this.loading = false;
        },
        error: () => {
          this.error = 'Kon de documenten van deze zaak niet laden.';
          this.loading = false;
        },
      });
  }

  public download(doc: ZgwDocument): void {
    this.http
      .get(
        `${this.api}v1/documenten-api/${doc.pluginConfigurationId}/files/${doc.fileId}/download`,
        {
          responseType: 'blob',
        },
      )
      .subscribe((blob) => {
        const url = URL.createObjectURL(blob);
        const anchor = document.createElement('a');
        anchor.href = url;
        anchor.download = doc.bestandsnaam || 'document.pdf';
        anchor.click();
        URL.revokeObjectURL(url);
      });
  }

  public formatSize(bytes: number): string {
    if (!bytes && bytes !== 0) return '';
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  }

  /** The documents tab is created dynamically; walk the route tree to find the case documentId. */
  private resolveDocumentId(): string {
    let snapshot: ActivatedRouteSnapshot | null = this.route.snapshot;
    while (snapshot) {
      const id = snapshot.paramMap.get('documentId');
      if (id) return id;
      snapshot = snapshot.parent;
    }
    return '';
  }
}
