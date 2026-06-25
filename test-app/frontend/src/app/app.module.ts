import { BrowserModule } from '@angular/platform-browser';
import { Injector, NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpBackend, provideHttpClient, withInterceptorsFromDi } from '@angular/common/http';
import { AppRoutingModule } from './app-routing.module';
import { AppComponent } from './app.component';
import { LayoutModule, TranslationManagementModule } from '@valtimo/layout';
import { TaskModule } from '@valtimo/task';
import { environment } from '../environments/environment';
import { SecurityModule } from '@valtimo/security';
import {
  BpmnJsDiagramModule,
  enableCustomFormioComponents,
  MenuModule,
  registerFormioCurrencyComponent,
  registerFormioFileSelectorComponent,
  registerFormioUploadComponent,
  registerFormioValueResolverSelectorComponent,
  WidgetModule,
} from '@valtimo/components';
import {
  CaseDetailTabAuditComponent,
  CaseDetailTabDocumentsComponent,
  CaseDetailTabNotesComponent,
  CaseDetailTabProgressComponent,
  CaseDetailTabSummaryComponent,
  CaseModule,
  DefaultTabs,
} from '@valtimo/case';
import { ProcessModule } from '@valtimo/process';
import { FormsModule, ReactiveFormsModule } from '@angular/forms';
import {
  CaseCountDataSourceModule,
  CaseCountsDataSourceModule,
  CaseGroupByDataSourceModule,
  DashboardModule,
  DisplayWidgetTypesModule,
} from '@valtimo/dashboard';
import { DocumentModule } from '@valtimo/document';
import { AccountModule } from '@valtimo/account';
import { ChoiceFieldModule } from '@valtimo/choice-field';
import { ResourceModule } from '@valtimo/resource';
import { FormModule } from '@valtimo/form';
import { SwaggerModule } from '@valtimo/swagger';
import { AnalyseModule } from '@valtimo/analyse';
import { ProcessManagementModule } from '@valtimo/process-management';
import { DecisionModule } from '@valtimo/decision';
import { MilestoneModule } from '@valtimo/milestone';
import { LoggerModule } from 'ngx-logger';
import { FormManagementModule } from '@valtimo/form-management';
import { ProcessLinkModule } from '@valtimo/process-link';
import { MigrationModule } from '@valtimo/migration';
import { CaseManagementModule } from '@valtimo/case-management';
import { BootstrapModule } from '@valtimo/bootstrap';
import {
  ConfigModule,
  ConfigService,
  MultiTranslateHttpLoaderFactory,
  ZGW_DOCUMENTEN_API_DOCUMENTS_COMPONENT_TOKEN,
} from '@valtimo/shared';
import { ZgwDocumentsTabComponent } from './zgw-documents/zgw-documents-tab.component';
import { TranslateLoader, TranslateModule } from '@ngx-translate/core';
import { FormFlowManagementModule } from '@valtimo/form-flow-management';
import { PluginManagementModule } from '@valtimo/plugin-management';
import {
  CatalogiApiPluginModule,
  catalogiApiPluginSpecification,
  DocumentenApiPluginModule,
  documentenApiPluginSpecification,
  ObjectenApiPluginModule,
  objectenApiPluginSpecification,
  ObjectTokenAuthenticationPluginModule,
  objectTokenAuthenticationPluginSpecification,
  ObjecttypenApiPluginModule,
  objecttypenApiPluginSpecification,
  OpenZaakPluginModule,
  openZaakPluginSpecification,
  ZakenApiPluginModule,
  zakenApiPluginSpecification,
  PLUGINS_TOKEN,
} from '@valtimo/plugin';
import { EpistolaPluginModule, epistolaPluginSpecification } from '@epistola.app/valtimo-plugin';
import { ObjectManagementModule } from '@valtimo/object-management';
import { ObjectModule } from '@valtimo/object';
import { AccessControlManagementModule } from '@valtimo/access-control-management';
import { DashboardManagementModule } from '@valtimo/dashboard-management';
import { CaseMigrationModule } from '@valtimo/case-migration';
import { LoggingModule } from '@valtimo/logging';
import { SseModule } from '@valtimo/sse';

export function tabsFactory() {
  return new Map<string, object>([
    [DefaultTabs.summary, CaseDetailTabSummaryComponent],
    [DefaultTabs.progress, CaseDetailTabProgressComponent],
    [DefaultTabs.audit, CaseDetailTabAuditComponent],
    [DefaultTabs.documents, CaseDetailTabDocumentsComponent],
    [DefaultTabs.notes, CaseDetailTabNotesComponent],
  ]);
}

@NgModule({
  declarations: [AppComponent],
  bootstrap: [AppComponent],
  imports: [
    CommonModule,
    BrowserModule,
    AppRoutingModule,
    LayoutModule,
    WidgetModule,
    BootstrapModule,
    ConfigModule.forRoot(environment),
    LoggerModule.forRoot(environment.logger),
    environment.authentication.module,
    SecurityModule,
    MenuModule,
    TaskModule,
    CaseMigrationModule,
    CaseModule.forRoot(tabsFactory),
    ProcessModule,
    BpmnJsDiagramModule,
    FormsModule,
    ReactiveFormsModule,
    DashboardModule,
    DashboardManagementModule,
    DocumentModule,
    AccountModule,
    ChoiceFieldModule,
    ResourceModule,
    FormModule,
    AnalyseModule,
    SwaggerModule,
    FormFlowManagementModule,
    ProcessManagementModule,
    DecisionModule,
    MilestoneModule,
    FormManagementModule,
    ProcessLinkModule,
    MigrationModule,
    CaseManagementModule,
    PluginManagementModule,
    ObjectenApiPluginModule,
    ObjecttypenApiPluginModule,
    ObjectTokenAuthenticationPluginModule,
    OpenZaakPluginModule,
    DocumentenApiPluginModule,
    ZakenApiPluginModule,
    CatalogiApiPluginModule,
    EpistolaPluginModule,
    ObjectModule,
    ObjectManagementModule,
    DisplayWidgetTypesModule,
    CaseCountDataSourceModule,
    CaseCountsDataSourceModule,
    CaseGroupByDataSourceModule,
    DashboardModule,
    AccessControlManagementModule,
    TranslateModule.forRoot({
      loader: {
        provide: TranslateLoader,
        useFactory: MultiTranslateHttpLoaderFactory,
        deps: [HttpBackend, ConfigService],
      },
    }),
    TranslationManagementModule,
    LoggingModule,
    SseModule,
  ],
  providers: [
    {
      provide: PLUGINS_TOKEN,
      useValue: [
        objectenApiPluginSpecification,
        objecttypenApiPluginSpecification,
        objectTokenAuthenticationPluginSpecification,
        openZaakPluginSpecification,
        documentenApiPluginSpecification,
        zakenApiPluginSpecification,
        catalogiApiPluginSpecification,
        epistolaPluginSpecification,
      ],
    },
    provideHttpClient(withInterceptorsFromDi()),
    // Fills Valtimo's ZGW documents-tab extension point so the case Documents tab lists the
    // case's Documenten-API documents (requires uploadProvider: DOCUMENTEN_API).
    { provide: ZGW_DOCUMENTEN_API_DOCUMENTS_COMPONENT_TOKEN, useValue: ZgwDocumentsTabComponent },
  ],
})
export class AppModule {
  constructor(injector: Injector) {
    enableCustomFormioComponents(injector);
    registerFormioCurrencyComponent(injector);
    registerFormioUploadComponent(injector);
    registerFormioFileSelectorComponent(injector);
    registerFormioValueResolverSelectorComponent(injector);
  }
}
