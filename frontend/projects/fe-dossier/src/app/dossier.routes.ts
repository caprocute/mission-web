import { inject } from '@angular/core';
import { CanActivateFn, Router, Routes } from '@angular/router';
import { PermissionService, permissionGuard } from '@vt/auth';
import { MEETING_KEYS } from './dossier-permissions';

/**
 * Trang đầu `/dossier`: vai trò có một chức năng của SCR-1101 vào thẳng danh sách hội nghị kiểm điểm; vai trò
 * khác ở trang khung cho tới khi SCR-1102 được dựng.
 */
const firstScreen: CanActivateFn = (_route, state) => {
  if (!inject(PermissionService).canAny(...MEETING_KEYS)) return true;
  const [path, query] = state.url.split('?');
  return inject(Router).parseUrl(
    path.replace(/\/$/, '') + '/kiem-diem' + (query ? '?' + query : ''),
  );
};

/**
 * Tuyến của remote fe-dossier, shell gắn dưới `/dossier` (QT-11). SCR-1101 Hội nghị kiểm điểm (WEB-040):
 * `kiem-diem` là danh sách và hộp "Lập hội nghị", `kiem-diem/:id` là màn hình điều hành một hội nghị. Tuyến con
 * chặn theo chức năng của SCR-1101 (wiki/15a); SCR-1102 bổ sung khi làm WEB-041.
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
    canActivate: [permissionGuard(...MEETING_KEYS)],
    loadComponent: () => import('./pages/meetings/meetings.page').then((m) => m.MeetingsPage),
    title: 'Hội nghị kiểm điểm',
  },
  {
    path: 'kiem-diem/:id',
    canActivate: [permissionGuard(...MEETING_KEYS)],
    loadComponent: () => import('./pages/meeting/meeting.page').then((m) => m.MeetingPage),
    title: 'Hội nghị kiểm điểm',
  },
];

export default ROUTES;
