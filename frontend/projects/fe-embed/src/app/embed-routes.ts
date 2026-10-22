import { Component, inject } from '@angular/core';
import { Router, Routes } from '@angular/router';
import { loadRemoteModule } from '@angular-architects/native-federation';
import { authGuard, permissionGuard, screenKeys } from '@vt/auth';
import { MessageModule } from 'primeng/message';
import { EMBED_APP_ROOT } from './memory-location';
import type { RemoteManifest } from './embed-config';

let manifest: Promise<RemoteManifest> = Promise.resolve({});

/** bootstrap.ts gọi một lần với manifest đã đổi sang địa chỉ tuyệt đối. */
export function setEmbedManifest(m: Promise<RemoteManifest>): void {
  manifest = m;
}

/** Màn hình chưa mở được trong phần nhúng: mời mở ứng dụng đầy đủ ở thẻ mới. */
@Component({
  selector: 'vt-embed-unavailable',
  imports: [MessageModule],
  template: `
    <p-message severity="info">
      Nội dung này chưa hiển thị được trong cổng đang dùng.
      <a [href]="fullAppUrl" target="_blank" rel="noopener">Mở trong ứng dụng đầy đủ</a>
    </p-message>
  `,
})
export class EmbedUnavailablePage {
  protected readonly fullAppUrl = new URL(
    inject(Router).url.replace(/^\/+/, ''),
    inject(EMBED_APP_ROOT),
  ).href;
}

/** `permissionGuard` chặn theo ma trận phân quyền: báo không có quyền, không nêu vai trò cần có. */
@Component({
  selector: 'vt-embed-forbidden',
  template: `
    <section role="alert" class="vt-embed-forbidden">
      <h2>Không có quyền truy cập</h2>
      <p>Vai trò hiện tại của đồng chí không có chức năng nào trong mục này.</p>
      <p>Nếu cho rằng đây là nhầm lẫn, vui lòng liên hệ quản trị viên của đơn vị.</p>
    </section>
  `,
})
export class EmbedForbiddenPage {}

/** Nạp tuyến của remote theo manifest; remote lỗi thì hiện trang báo, không làm hỏng cổng. */
function remoteRoutes(remoteName: string, exposedModule = './routes'): () => Promise<Routes> {
  return async () => {
    try {
      const remoteEntry = (await manifest)[remoteName];
      if (!remoteEntry) throw new Error(`Không có remote "${remoteName}" trong manifest`);
      const m = await loadRemoteModule<{ default?: Routes; TASK_ROUTES?: Routes }>({
        remoteEntry,
        exposedModule,
      });
      return (m.default ?? m.TASK_ROUTES) as Routes;
    } catch (err) {
      console.error(`[embed] Không nạp được ${remoteName}`, err);
      return [{ path: '**', component: EmbedUnavailablePage }];
    }
  };
}

/**
 * Tuyến của phần nhúng (WEB-013): hiện chỉ mở mục "Việc của tôi" (fe-task, SCR-0503 và các màn hình
 * con), chặn theo cùng nhóm chức năng như shell (wiki/15a); tuyến khác mời mở ứng dụng đầy đủ.
 */
export const EMBED_ROUTES: Routes = [
  {
    path: '',
    canActivate: [authGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'tasks' },
      {
        path: 'tasks',
        canMatch: [
          permissionGuard(
            ...screenKeys('SCR-0401', 'SCR-0501', 'SCR-0502', 'SCR-0503', 'SCR-0601', 'SCR-0602'),
          ),
        ],
        loadChildren: remoteRoutes('feTask'),
      },
      { path: 'khong-co-quyen', component: EmbedForbiddenPage },
      { path: '**', component: EmbedUnavailablePage },
    ],
  },
];
