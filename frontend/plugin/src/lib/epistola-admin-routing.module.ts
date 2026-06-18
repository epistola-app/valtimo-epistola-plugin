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
