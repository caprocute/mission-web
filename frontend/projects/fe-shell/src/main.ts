import { initFederation } from '@angular-architects/native-federation';

// main.ts chỉ dựng import map của Native Federation; mọi import thư viện dùng chung
// (kể cả @vt/*) phải nằm sau bước này, tức trong bootstrap.ts
initFederation('federation.manifest.json')
  .catch((err) => console.error('[federation]', err))
  .then(() => import('./bootstrap'))
  .catch((err) => console.error(err));
