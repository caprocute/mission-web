import { bootstrapApplication } from '@angular/platform-browser';
import { loadRuntimeConfig } from '@vt/api-client';
import { buildAppConfig } from './app/app.config';
import { App } from './app/app';
import type { EarlyFetches } from './early-fetch';

/**
 * Nạp /config.json trước khi bootstrap để mọi provider có cấu hình của cell. `early` là các yêu
 * cầu main.ts đã gửi sẵn (WEB-052); không có thì tự tải.
 */
export function bootstrap(early?: EarlyFetches): Promise<unknown> {
  return loadRuntimeConfig(early?.config)
    .then((cfg) => bootstrapApplication(App, buildAppConfig(cfg, early?.uiPack)))
    .catch((err) => console.error(err));
}
