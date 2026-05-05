import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';
import { AuthGuardService } from '@valtimo/security';
import { EpistolaAdminPageComponent } from './components/epistola-admin-page/epistola-admin-page.component';
import { epistolaEnabledGuard } from './epistola-enabled.guard';

const routes: Routes = [
  {
    path: 'epistola',
    component: EpistolaAdminPageComponent,
    canActivate: [epistolaEnabledGuard, AuthGuardService],
    data: { title: 'Epistola', roles: ['ROLE_ADMIN'] },
  },
];

@NgModule({
  imports: [RouterModule.forChild(routes)],
  exports: [RouterModule],
})
export class EpistolaAdminRoutingModule {}
