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

import type { MenuService } from '@valtimo/components';
import type { ConfigService, MenuItem } from '@valtimo/shared';
import { EpistolaMenuService, appendEpistolaMenuItem } from './epistola-menu.service';

jest.mock('@angular/core', () => ({ Injectable: () => () => undefined }));
jest.mock('@valtimo/components', () => ({}));
jest.mock('@valtimo/shared', () => ({ ROLE_ADMIN: 'ROLE_ADMIN' }));

describe('EpistolaMenuService', () => {
  it('appends Epistola to the admin menu once', () => {
    const items: MenuItem[] = [
      {
        title: 'Admin',
        roles: ['ROLE_ADMIN'],
        children: [{ title: 'Logs', link: ['/logging'] }],
      },
    ];

    const result = appendEpistolaMenuItem(items);
    const secondResult = appendEpistolaMenuItem(result);

    expect(result[0].children).toEqual([
      { title: 'Logs', link: ['/logging'] },
      { title: 'Epistola', link: ['/epistola'], sequence: 18 },
    ]);
    expect(secondResult).toBe(result);
  });

  it('updates configured menu items and reloads Valtimo menu processing', () => {
    const config = {
      menu: {
        menuItems: [
          {
            title: 'Admin',
            roles: ['ROLE_ADMIN'],
            children: [{ title: 'Logs', link: ['/logging'] }],
          },
        ],
      },
    };
    const menuService = {
      registerAppendMenuItemsFunction: jest.fn(),
      reload: jest.fn(),
    } as unknown as MenuService;
    const configService = { config } as unknown as ConfigService;

    new EpistolaMenuService(menuService, configService);

    expect(config.menu.menuItems[0].children).toContainEqual({
      title: 'Epistola',
      link: ['/epistola'],
      sequence: 18,
    });
    expect(menuService.registerAppendMenuItemsFunction).toHaveBeenCalledTimes(1);
    expect(menuService.reload).toHaveBeenCalledTimes(1);
  });
});
