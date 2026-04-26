import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { PluginTranslatePipeModule } from '@valtimo/plugin';
import { EpistolaAdminService } from '../../services/epistola-admin.service';
import { ConnectionStatus, PluginUsageEntry } from '../../models';

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
}

@Component({
  selector: 'epistola-admin-page',
  templateUrl: './epistola-admin-page.component.html',
  styleUrls: ['./epistola-admin-page.component.scss'],
  standalone: true,
  imports: [CommonModule, RouterModule, PluginTranslatePipeModule],
})
export class EpistolaAdminPageComponent implements OnInit {
  cards: ConfigurationCard[] = [];
  selectedCard: ConfigurationCard | null = null;
  loading = false;
  pluginVersion: string | null = null;

  private connectionStatuses: ConnectionStatus[] = [];
  private usageEntries: PluginUsageEntry[] = [];
  private connectionLoaded = false;
  private usageLoaded = false;
  private deepLinkConfigId: string | null = null;

  constructor(
    private readonly adminService: EpistolaAdminService,
    private readonly route: ActivatedRoute,
    private readonly router: Router,
  ) {}

  ngOnInit(): void {
    this.deepLinkConfigId = this.route.snapshot.queryParamMap.get('configurationId');
    this.loadData();
    this.loadPluginVersion();
  }

  selectConfiguration(card: ConfigurationCard): void {
    this.selectedCard = card;
    this.updateUrl(card.configurationId);
  }

  backToOverview(): void {
    this.selectedCard = null;
    this.updateUrl(null);
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

  private updateUrl(configurationId: string | null): void {
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { configurationId: configurationId ?? null },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  private loadData(): void {
    this.loading = true;
    this.connectionLoaded = false;
    this.usageLoaded = false;

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
  }

  private tryBuildCards(): void {
    if (!this.connectionLoaded || !this.usageLoaded) {
      return;
    }

    this.cards = this.connectionStatuses.map((status) => {
      const entries = this.usageEntries.filter((e) => e.configurationId === status.configurationId);
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
