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

const EPISTOLA_MENU_ITEM: MenuItem = {
  link: ['/epistola'],
  title: 'Epistola',
  sequence: 18,
};

function isAdminMenu(item: MenuItem): boolean {
  return !!item.roles?.includes(ROLE_ADMIN) && Array.isArray(item.children);
}

function hasEpistolaMenuItem(item: MenuItem): boolean {
  return !!item.children?.some((child) => child.link?.[0] === EPISTOLA_MENU_ITEM.link?.[0]);
}

export function appendEpistolaMenuItem(items: MenuItem[]): MenuItem[] {
  let changed = false;
  const menuItems = items.map((item) => {
    if (!isAdminMenu(item) || hasEpistolaMenuItem(item)) {
      return item;
    }
    changed = true;
    return {
      ...item,
      children: [...item.children!, EPISTOLA_MENU_ITEM],
    };
  });

  return changed ? menuItems : items;
}

/**
 * Registers the Epistola admin page menu item under the Admin > Other section.
 * Registration is invoked by EpistolaRegistrationService during environment
 * initialization, before Valtimo builds the configured menu.
 */
@Injectable()
export class EpistolaMenuService {
  private registered = false;

  constructor(private readonly menuService: MenuService) {}

  register(): void {
    if (this.registered) {
      return;
    }

    this.registered = true;
    this.menuService.registerAppendMenuItemsFunction(
      (items: MenuItem[]): Observable<MenuItem[]> => {
        return of(appendEpistolaMenuItem(items));
      },
    );
  }
}
