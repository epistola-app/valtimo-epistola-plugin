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
