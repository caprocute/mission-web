import {
  EnvironmentProviders,
  inject,
  makeEnvironmentProviders,
  provideAppInitializer,
} from '@angular/core';
import { provideOAuthClient } from 'angular-oauth2-oidc';
import { VT_PERMISSION_OPTIONS, VtPermissionOptions } from './permission.service';
import { SessionService } from './session.service';

export function provideVtAuth(options: Partial<VtPermissionOptions> = {}): EnvironmentProviders {
  return makeEnvironmentProviders([
    provideOAuthClient(),
    { provide: VT_PERMISSION_OPTIONS, useValue: { allowInferred: true, ...options } },
    provideAppInitializer(() => inject(SessionService).init()),
  ]);
}
