import { createApplication } from '@angular/platform-browser';
import { createCustomElement } from '@angular/elements';
import { loadRuntimeConfig } from '@vt/api-client';
import { buildEmbedConfig } from './app/app.config';
import { RemoteManifest, embedManifest, embedRuntimeConfig, tenantAttr } from './app/embed-config';
import { setEmbedManifest } from './app/embed-routes';
import { OkrAppElement } from './app/okr-app.element';

export const ELEMENT_NAME = 'vt-okr-app';

/**
 * Dựng ứng dụng một lần rồi đăng ký phần tử `<vt-okr-app>`. `root` là gốc ứng dụng (nơi có
 * `config.json`); tenant lấy từ thuộc tính của phần tử đầu tiên trên trang cổng.
 */
export async function bootstrap(opts: {
  root: string;
  config: Promise<Response>;
  manifest: Promise<RemoteManifest>;
}): Promise<void> {
  if (customElements.get(ELEMENT_NAME)) return;
  const tenant = tenantAttr(document.querySelector(ELEMENT_NAME)?.getAttribute('tenant'));
  const cfg = embedRuntimeConfig(await loadRuntimeConfig(opts.config), {
    root: opts.root,
    pageUrl: location.href,
    tenant,
  });
  setEmbedManifest(opts.manifest.then((m) => embedManifest(m, opts.root)));
  const app = await createApplication(buildEmbedConfig(cfg, opts.root));
  customElements.define(
    ELEMENT_NAME,
    createCustomElement(OkrAppElement, { injector: app.injector }),
  );
}
