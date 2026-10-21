import { inject } from '@angular/core';
import { CanActivateFn, Router, Routes } from '@angular/router';
import { PermissionService, permissionGuard, screenKeys } from '@vt/auth';
import { MEETING_KEYS, PACKAGE_KEYS } from './dossier-permissions';

/**
 * Trang đầu `/dossier`: vai trò có một chức năng của SCR-1101 vào thẳng danh sách hội nghị kiểm điểm, vai trò
 * chỉ có chức năng của SCR-1102 vào danh sách gói hồ sơ số; vai trò khác ở trang khung.
 */
const firstScreen: CanActivateFn = (_route, state) => {
  const perms = inject(PermissionService);
  const target = perms.canAny(...MEETING_KEYS)
    ? '/kiem-diem'
    : perms.canAny(...PACKAGE_KEYS)
      ? '/goi-ho-so'
      : null;
  if (!target) return true;
  const [path, query] = state.url.split('?');
  return inject(Router).parseUrl(path.replace(/\/$/, '') + target + (query ? '?' + query : ''));
};

/**
 * Tuyến của remote fe-dossier, shell gắn dưới `/dossier` (QT-11). SCR-1101 Hội nghị kiểm điểm (WEB-040):
 * `kiem-diem` là danh sách và hộp "Lập hội nghị", `kiem-diem/:id` là màn hình điều hành một hội nghị. Tuyến con
 * chặn theo chức năng của SCR-1101 (wiki/15a). SCR-1102 Gói hồ sơ số và ký số (WEB-041): `goi-ho-so` là danh
 * sách gói, `goi-ho-so/:id` là màn hình theo dõi sinh biểu mẫu, kiểm tra trước khi ký, gửi gói con và ký gói.
 */
export const ROUTES: Routes = [
  {
    path: '',
    canActivate: [firstScreen],
    loadComponent: () => import('./pages/dossier-home.page').then((m) => m.DossierHomePage),
    title: 'Kiểm điểm, hồ sơ số',
  },
  {
    path: 'kiem-diem',
    canActivate: [permissionGuard(...screenKeys('SCR-1101'))],
    loadComponent: () => import('./pages/meetings/meetings.page').then((m) => m.MeetingsPage),
    title: 'Hội nghị kiểm điểm',
  },
  {
    path: 'kiem-diem/:id',
    canActivate: [permissionGuard(...screenKeys('SCR-1101'))],
    loadComponent: () => import('./pages/meeting/meeting.page').then((m) => m.MeetingPage),
    title: 'Hội nghị kiểm điểm',
  },
  {
    path: 'goi-ho-so',
    canActivate: [permissionGuard(...screenKeys('SCR-1102'))],
    loadComponent: () => import('./pages/packages/packages.page').then((m) => m.PackagesPage),
    title: 'Gói hồ sơ số',
  },
  {
    path: 'goi-ho-so/:id',
    canActivate: [permissionGuard(...screenKeys('SCR-1102'))],
    loadComponent: () => import('./pages/package/package.page').then((m) => m.PackagePage),
    title: 'Gói hồ sơ số',
  },
];

export default ROUTES;
