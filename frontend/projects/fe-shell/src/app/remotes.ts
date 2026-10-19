import { loadRemoteModule } from '@angular-architects/native-federation';
import { Routes } from '@angular/router';

/**
 * Nạp remote theo manifest (`federation.manifest.json`, do CDN/tenant cấp).
 * Remote lỗi hoặc chưa triển khai thì chuyển sang trang báo tạm thời, không làm hỏng shell.
 */
export function remoteRoutes(remoteName: string, exposed = './routes'): () => Promise<Routes> {
  return () =>
    loadRemoteModule(remoteName, exposed)
      .then((m) => (m.default ?? m.TASK_ROUTES ?? m.ROUTES) as Routes)
      .catch((err) => {
        console.error(`[federation] Không nạp được ${remoteName}`, err);
        return import('./pages/remote-unavailable.page').then((p) => [{ path: '**', component: p.RemoteUnavailablePage, data: { remoteName } }]);
      });
}
