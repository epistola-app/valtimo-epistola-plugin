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

import { Injectable } from '@angular/core';
import { MenuService } from '@valtimo/components';
import { MenuItem, ROLE_ADMIN } from '@valtimo/shared';
import { Observable, of } from 'rxjs';

/**
 * Registers the Epistola admin page menu item under the Admin > Other section.
 * Instantiated eagerly via ENVIRONMENT_INITIALIZER so the menu item
 * appears without any manual configuration in the host application.
 */
@Injectable()
export class EpistolaMenuService {
  constructor(private readonly menuService: MenuService) {
    this.menuService.registerAppendMenuItemsFunction(
      (items: MenuItem[]): Observable<MenuItem[]> => {
        return of(
          items.map((item) => {
            const isAdminMenu = item.roles?.includes(ROLE_ADMIN) && item.children;
            if (!isAdminMenu) {
              return item;
            }
            return {
              ...item,
              children: [
                ...item.children!,
                {
                  link: ['/epistola'],
                  title: 'Epistola',
                  sequence: 18,
                },
              ],
            };
          }),
        );
      },
    );
  }
}
