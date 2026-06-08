import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { PluginTranslatePipeModule } from '@valtimo/plugin';
import { TabsModule } from 'carbon-components-angular/tabs';
import { TagModule } from 'carbon-components-angular/tag';
import { EpistolaAdminService } from '../../services/epistola-admin.service';
import {
  BpmnValidationReport,
  BpmnValidationViolation,
  CatalogRedeployResult,
  ChangelogRelease,
  ClasspathCatalog,
  ConnectionStatus,
  PendingJob,
  PluginUsageEntry,
} from '../../models';

/**
 * Combined view model for a single plugin configuration card.
 */
interface ConfigurationCard {
  configurationId: string;
  configurationTitle: string;
  tenantId: string;
  reachable: boolean;
  latencyMs: number;
  errorMessage?: string;
  serverVersion?: string;
  usageCount: number;
  problemCount: number;
  usageEntries: PluginUsageEntry[];
  pendingJobs: PendingJob[];
}

@Component({
  selector: 'epistola-admin-page',
  templateUrl: './epistola-admin-page.component.html',
  styleUrls: ['./epistola-admin-page.component.scss'],
  standalone: true,
  imports: [CommonModule, RouterModule, PluginTranslatePipeModule, TabsModule, TagModule],
})
export class EpistolaAdminPageComponent implements OnInit {
  cards: ConfigurationCard[] = [];
  selectedCard: ConfigurationCard | null = null;
  activeTab: 'actions' | 'pending' | 'catalogs' = 'actions';
  overviewTab: 'configurations' | 'validations' | 'changelog' = 'configurations';
  loading = false;
  pluginVersion: string | null = null;
  changelog: ChangelogRelease[] | null = null;
  changelogLoading = false;
  validationReport: BpmnValidationReport | null = null;
  reconcilingExecutionIds = new Set<string>();
  reconcileFeedback: {
    executionId: string;
    type: 'success' | 'pending' | 'error';
    message: string;
  } | null = null;

  catalogs: ClasspathCatalog[] = [];
  catalogsLoading = false;
  redeployingSlugs = new Set<string>();
  catalogFeedback: {
    slug: string;
    type: 'success' | 'error';
    message: string;
  } | null = null;

  private connectionStatuses: ConnectionStatus[] = [];
  private usageEntries: PluginUsageEntry[] = [];
  private pendingJobs: PendingJob[] = [];
  private connectionLoaded = false;
  private usageLoaded = false;
  private pendingLoaded = false;
  private deepLinkConfigId: string | null = null;

  constructor(
    private readonly adminService: EpistolaAdminService,
    private readonly route: ActivatedRoute,
    private readonly router: Router,
  ) {}

  /** Violations from the latest report (empty when healthy or not yet loaded). */
  get validationViolations(): BpmnValidationViolation[] {
    return this.validationReport?.violations ?? [];
  }

  /** Scan cadence in whole minutes, for the "refreshes every N min" note. */
  get refreshIntervalMinutes(): number {
    return Math.round((this.validationReport?.refreshIntervalMs ?? 600000) / 60000);
  }

  ngOnInit(): void {
    this.deepLinkConfigId = this.route.snapshot.queryParamMap.get('configurationId');
    const tab = this.route.snapshot.queryParamMap.get('tab');
    if (tab === 'pending' || tab === 'actions' || tab === 'catalogs') {
      this.activeTab = tab;
    }
    this.loadData();
    this.loadPluginVersion();
  }

  selectConfiguration(card: ConfigurationCard): void {
    this.selectedCard = card;
    this.activeTab = 'actions';
    this.updateUrl(card.configurationId, this.activeTab);
    this.loadCatalogs(card.configurationId);
  }

  backToOverview(): void {
    this.selectedCard = null;
    this.activeTab = 'actions';
    this.catalogs = [];
    this.catalogFeedback = null;
    this.updateUrl(null, null);
  }

  setActiveTab(tab: 'actions' | 'pending' | 'catalogs'): void {
    this.activeTab = tab;
    this.updateUrl(this.selectedCard?.configurationId ?? null, tab);
  }

  setOverviewTab(tab: 'configurations' | 'validations' | 'changelog'): void {
    this.overviewTab = tab;
    if (tab === 'changelog' && this.changelog === null && !this.changelogLoading) {
      this.loadChangelog();
    }
  }

  private loadChangelog(): void {
    this.changelogLoading = true;
    this.adminService.getChangelog().subscribe({
      next: (releases) => {
        this.changelog = releases;
        this.changelogLoading = false;
      },
      error: () => {
        this.changelog = [];
        this.changelogLoading = false;
      },
    });
  }

  refresh(): void {
    this.selectedCard = null;
    this.loadData();
  }

  exportProcessLink(entry: PluginUsageEntry): void {
    this.adminService.exportProcessLink(entry.processLinkId).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        const anchor = document.createElement('a');
        anchor.href = url;
        anchor.download = `${entry.activityId}.process-link.json`;
        anchor.click();
        URL.revokeObjectURL(url);
      },
    });
  }

  /**
   * Manually reconcile a single stuck catch event. Pulls the current Epistola job
   * status and re-runs message correlation if the job is in a terminal state.
   * Refreshes the Pending list on success so the row drops out of the table.
   */
  reconcilePending(job: PendingJob): void {
    if (this.reconcilingExecutionIds.has(job.executionId)) {
      return;
    }
    this.reconcilingExecutionIds.add(job.executionId);
    this.reconcileFeedback = null;

    this.adminService.reconcilePending(job.executionId).subscribe({
      next: (result) => {
        this.reconcilingExecutionIds.delete(job.executionId);
        this.reconcileFeedback = {
          executionId: job.executionId,
          type: 'success',
          message: `OK (${result.epistolaStatus}, ${result.correlatedCount ?? 0} correlated)`,
        };
        this.loadData();
      },
      error: (err) => {
        this.reconcilingExecutionIds.delete(job.executionId);
        // 409 from the backend = job is still PENDING/IN_PROGRESS, surface as
        // informational rather than an error.
        if (err?.status === 409) {
          const status = err.error?.epistolaStatus ?? 'still pending';
          this.reconcileFeedback = {
            executionId: job.executionId,
            type: 'pending',
            message: `Epistola: ${status}. Try again in a moment.`,
          };
        } else {
          const message =
            err?.error?.detail ?? err?.error?.message ?? err?.message ?? 'unknown error';
          this.reconcileFeedback = {
            executionId: job.executionId,
            type: 'error',
            message,
          };
        }
      },
    });
  }

  isReconciling(job: PendingJob): boolean {
    return this.reconcilingExecutionIds.has(job.executionId);
  }

  /**
   * Load the classpath catalogs available to (re)deploy for the given
   * configuration. Lazy — only called when a configuration is opened, not for
   * every card in the overview.
   */
  private loadCatalogs(configurationId: string): void {
    this.catalogsLoading = true;
    this.catalogFeedback = null;
    this.adminService.getClasspathCatalogs(configurationId).subscribe({
      next: (catalogs) => {
        this.catalogs = catalogs;
        this.catalogsLoading = false;
      },
      error: () => {
        this.catalogs = [];
        this.catalogsLoading = false;
      },
    });
  }

  /**
   * Force-redeploy a single classpath catalog to the selected configuration's
   * Epistola installation. Explicit operator action — the backend bypasses the
   * templateSyncEnabled gate and the version-skip check. Reloads the catalog
   * list on success so the deployed version / up-to-date hint refreshes.
   */
  redeployCatalog(catalog: ClasspathCatalog): void {
    if (!this.selectedCard || this.redeployingSlugs.has(catalog.slug)) {
      return;
    }
    const configurationId = this.selectedCard.configurationId;
    this.redeployingSlugs.add(catalog.slug);
    this.catalogFeedback = null;

    this.adminService.redeployCatalog(configurationId, catalog.slug).subscribe({
      next: (result: CatalogRedeployResult) => {
        this.redeployingSlugs.delete(catalog.slug);
        this.catalogFeedback = {
          slug: catalog.slug,
          type: 'success',
          message: `OK — installed ${result.installed}, updated ${result.updated}, failed ${result.failed} (of ${result.total})`,
        };
        this.loadCatalogs(configurationId);
      },
      error: (err) => {
        this.redeployingSlugs.delete(catalog.slug);
        const message =
          err?.error?.errorMessage ??
          err?.error?.detail ??
          err?.error?.message ??
          err?.message ??
          'unknown error';
        this.catalogFeedback = {
          slug: catalog.slug,
          type: 'error',
          message,
        };
      },
    });
  }

  isRedeploying(catalog: ClasspathCatalog): boolean {
    return this.redeployingSlugs.has(catalog.slug);
  }

  private updateUrl(configurationId: string | null, tab: string | null): void {
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: {
        configurationId: configurationId ?? null,
        tab: tab ?? null,
      },
      replaceUrl: true,
    });
  }

  private loadData(): void {
    this.loading = true;
    this.connectionLoaded = false;
    this.usageLoaded = false;
    this.pendingLoaded = false;

    this.adminService.getConnectionStatus().subscribe({
      next: (statuses) => {
        this.connectionStatuses = statuses;
        this.connectionLoaded = true;
        this.tryBuildCards();
      },
      error: () => {
        this.connectionStatuses = [];
        this.connectionLoaded = true;
        this.tryBuildCards();
      },
    });

    this.adminService.getPluginUsage().subscribe({
      next: (entries) => {
        this.usageEntries = entries;
        this.usageLoaded = true;
        this.tryBuildCards();
      },
      error: () => {
        this.usageEntries = [];
        this.usageLoaded = true;
        this.tryBuildCards();
      },
    });

    this.adminService.getPendingJobs().subscribe({
      next: (jobs) => {
        this.pendingJobs = jobs;
        this.pendingLoaded = true;
        this.tryBuildCards();
      },
      error: () => {
        this.pendingJobs = [];
        this.pendingLoaded = true;
        this.tryBuildCards();
      },
    });

    // Validation report is independent of cards — load alongside but don't
    // gate the loading flag on it.
    this.adminService.getValidationReport().subscribe({
      next: (report) => {
        this.validationReport = report;
      },
      error: () => {
        this.validationReport = null;
      },
    });
  }

  private tryBuildCards(): void {
    if (!this.connectionLoaded || !this.usageLoaded || !this.pendingLoaded) {
      return;
    }

    this.cards = this.connectionStatuses.map((status) => {
      const entries = this.usageEntries.filter((e) => e.configurationId === status.configurationId);
      const jobs = this.pendingJobs.filter((j) => j.tenantId === status.tenantId);
      const problemCount = entries.reduce((sum, e) => sum + e.problems.length, 0);

      return {
        configurationId: status.configurationId,
        configurationTitle: status.configurationTitle,
        tenantId: status.tenantId,
        reachable: status.reachable,
        latencyMs: status.latencyMs,
        errorMessage: status.errorMessage,
        serverVersion: status.serverVersion,
        usageCount: entries.length,
        problemCount,
        usageEntries: entries,
        pendingJobs: jobs,
      };
    });

    // Restore deep link selection
    if (this.deepLinkConfigId) {
      const match = this.cards.find((c) => c.configurationId === this.deepLinkConfigId);
      if (match) {
        this.selectedCard = match;
        this.loadCatalogs(match.configurationId);
      }
      this.deepLinkConfigId = null;
    }

    this.loading = false;
  }

  private loadPluginVersion(): void {
    this.adminService.getVersions().subscribe({
      next: (info) => {
        this.pluginVersion = info.pluginVersion;
      },
    });
  }
}
