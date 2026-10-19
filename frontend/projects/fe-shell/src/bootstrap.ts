import { bootstrapApplication } from '@angular/platform-browser';
import { loadRuntimeConfig } from '@vt/api-client';
import { buildAppConfig } from './app/app.config';
import { App } from './app/app';

// Nạp /config.json trước khi bootstrap để mọi provider có cấu hình của cell
loadRuntimeConfig()
  .then((cfg) => bootstrapApplication(App, buildAppConfig(cfg)))
  .catch((err) => console.error(err));
