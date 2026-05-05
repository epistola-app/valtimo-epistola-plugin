import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { isEpistolaEnabled } from './epistola-runtime-config';

export const epistolaEnabledGuard: CanActivateFn = () => {
  if (isEpistolaEnabled()) return true;
  return inject(Router).parseUrl('/');
};
