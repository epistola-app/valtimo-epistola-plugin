import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';
import { AuthGuardService } from '@valtimo/security';
import { EpistolaAdminPageComponent } from './components/epistola-admin-page/epistola-admin-page.component';

const routes: Routes = [
  {
    path: 'epistola',
    component: EpistolaAdminPageComponent,
    canActivate: [AuthGuardService],
    data: { title: 'Epistola', roles: ['ROLE_ADMIN'] },
  },
];

@NgModule({
  imports: [RouterModule.forChild(routes)],
  exports: [RouterModule],
})
export class EpistolaAdminRoutingModule {}
