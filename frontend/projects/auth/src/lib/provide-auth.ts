import { EnvironmentProviders, inject, makeEnvironmentProviders, provideAppInitializer } from '@angular/core';
import { provideOAuthClient } from 'angular-oauth2-oidc';
import { SessionService } from './session.service';

export function provideVtAuth(): EnvironmentProviders {
  return makeEnvironmentProviders([
    provideOAuthClient(),
    provideAppInitializer(() => inject(SessionService).init()),
  ]);
}
