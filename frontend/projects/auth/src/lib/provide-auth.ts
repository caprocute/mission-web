import {
  EnvironmentProviders,
  inject,
  makeEnvironmentProviders,
  provideAppInitializer,
} from '@angular/core';
import { OAuthStorage, provideOAuthClient } from 'angular-oauth2-oidc';
import { VT_PERMISSION_OPTIONS, VtPermissionOptions } from './permission.service';

import { SessionService } from './session.service';
import { VtTokenStorage } from './token-storage';

export function provideVtAuth(options: Partial<VtPermissionOptions> = {}): EnvironmentProviders {
  return makeEnvironmentProviders([
    provideOAuthClient(),
    { provide: VT_PERMISSION_OPTIONS, useValue: { allowInferred: true, ...options } },
    // Token chỉ giữ trong bộ nhớ (TL-01), thay sessionStorage mặc định của thư viện
    { provide: OAuthStorage, useClass: VtTokenStorage },
    provideAppInitializer(() => inject(SessionService).init()),
  ]);
}
