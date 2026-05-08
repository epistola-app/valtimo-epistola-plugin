import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { PluginTranslatePipeModule } from '@valtimo/plugin';
import { TabsModule } from 'carbon-components-angular/tabs';
import { TagModule } from 'carbon-components-angular/tag';
import { EpistolaAdminService } from '../../services/epistola-admin.service';
import {
  BpmnValidationViolation,
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
  activeTab: 'actions' | 'pending' = 'actions';
  overviewTab: 'configurations' | 'validations' = 'configurations';
  loading = false;
  pluginVersion: string | null = null;
  validationViolations: BpmnValidationViolation[] = [];
  reconcilingExecutionIds = new Set<string>();
  reconcileFeedback: {
    executionId: string;
    type: 'success' | 'pending' | 'error';
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

  ngOnInit(): void {
    this.deepLinkConfigId = this.route.snapshot.queryParamMap.get('configurationId');
    const tab = this.route.snapshot.queryParamMap.get('tab');
    if (tab === 'pending' || tab === 'actions') {
      this.activeTab = tab;
    }
    this.loadData();
    this.loadPluginVersion();
  }

  selectConfiguration(card: ConfigurationCard): void {
    this.selectedCard = card;
    this.activeTab = 'actions';
    this.updateUrl(card.configurationId, this.activeTab);
  }

  backToOverview(): void {
    this.selectedCard = null;
    this.activeTab = 'actions';
    this.updateUrl(null, null);
  }

  setActiveTab(tab: 'actions' | 'pending'): void {
    this.activeTab = tab;
    this.updateUrl(this.selectedCard?.configurationId ?? null, tab);
  }

  setOverviewTab(tab: 'configurations' | 'validations'): void {
    this.overviewTab = tab;
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

    // Validation violations are independent of cards — load alongside but don't
    // gate the loading flag on them.
    this.adminService.getValidationViolations().subscribe({
      next: (violations) => {
        this.validationViolations = violations;
      },
      error: () => {
        this.validationViolations = [];
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
