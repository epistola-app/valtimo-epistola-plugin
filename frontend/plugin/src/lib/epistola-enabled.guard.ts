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

import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { isEpistolaEnabled } from './epistola-runtime-config';

export const epistolaEnabledGuard: CanActivateFn = () => {
  if (isEpistolaEnabled()) return true;
  return inject(Router).parseUrl('/');
};
