import { Routes } from '@angular/router';
import { loadRemote } from './federation-remotes';

/**
 * Nạp remote theo manifest (`federation.manifest.json`, do CDN/tenant cấp).
 * Remote chỉ được đăng ký khi tuyến của nó được nạp lần đầu (WEB-052, `federation-remotes.ts`).
 * Remote lỗi hoặc chưa triển khai thì chuyển sang trang báo tạm thời, không làm hỏng shell.
 */
export function remoteRoutes(remoteName: string, exposed = './routes'): () => Promise<Routes> {
  return () =>
    loadRemote<{ default?: Routes; TASK_ROUTES?: Routes; ROUTES?: Routes }>(remoteName, exposed)
      .then((m) => (m.default ?? m.TASK_ROUTES ?? m.ROUTES) as Routes)
      .catch((err) => {
        console.error(`[federation] Không nạp được ${remoteName}`, err);
        return import('./pages/remote-unavailable.page').then((p) => [{ path: '**', component: p.RemoteUnavailablePage, data: { remoteName } }]);
      });
}
