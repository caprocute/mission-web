import { initFederation } from '@angular-architects/native-federation';

// WEB-013: gói nhúng chạy trong trang của cổng khác, nên mọi đường dẫn lấy theo địa chỉ của chính
// gói (`<gốc>/embed/main.js`), không theo địa chỉ trang. Gốc ứng dụng là thư mục cha của /embed/.
const base = new URL('./', import.meta.url).href;
const root = new URL('../', base).href;

const config = fetch(new URL('config.json', root), { cache: 'no-cache' });
const manifest = fetch(new URL('federation.manifest.json', root))
  .then((r) => (r.ok ? (r.json() as Promise<Record<string, string>>) : {}))
  .catch((err) => {
    console.error('[embed] Không đọc được federation.manifest.json', err);
    return {};
  });

// main.ts chỉ dựng import map; mọi import thư viện dùng chung nằm trong bootstrap.ts
initFederation({}, { deployUrl: base })
  .catch((err) => console.error('[federation]', err))
  .then(() => import('./bootstrap'))
  .then((m) => m.bootstrap({ root, config, manifest }))
  .catch((err) => console.error('[embed]', err));
