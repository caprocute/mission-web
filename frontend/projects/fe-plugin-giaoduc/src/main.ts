import { initFederation } from '@angular-architects/native-federation';

// Chỉ initFederation rồi nạp bootstrap; mọi import thư viện dùng chung nằm trong bootstrap.ts.
initFederation()
  .catch((err) => console.error(err))
  .then(() => import('./bootstrap'))
  .catch((err) => console.error(err));
