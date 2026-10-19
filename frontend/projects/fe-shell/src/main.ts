import { initFederation } from '@angular-architects/native-federation';
import { RemoteManifest, setRemoteManifest } from './app/federation-remotes';
import { startEarlyFetches } from './early-fetch';

// WEB-052: gửi yêu cầu config.json và UI Pack ngay, song song với initFederation
const early = startEarlyFetches();

// WEB-052: initFederation chỉ dựng import map của shell; remote được đăng ký khi tuyến hoặc
// widget của nó được nạp lần đầu (app/federation-remotes.ts), theo manifest đọc ở đây
setRemoteManifest(
  fetch('federation.manifest.json')
    .then((r) =>
      r.ok ? (r.json() as Promise<RemoteManifest>) : Promise.reject(new Error(`HTTP ${r.status}`)),
    )
    .catch((err) => {
      console.error('[federation] Không đọc được federation.manifest.json', err);
      return {};
    }),
);

// main.ts chỉ dựng import map của Native Federation; mọi import thư viện dùng chung
// (kể cả @vt/*) phải nằm sau bước này, tức trong bootstrap.ts
initFederation({})
  .catch((err) => console.error('[federation]', err))
  .then(() => import('./bootstrap'))
  .then((m) => m.bootstrap(early))
  .catch((err) => console.error(err));
