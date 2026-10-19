import { Routes } from '@angular/router';
import { permissionGuard, screenKeys } from '@vt/auth';

/**
 * Tuyến của remote fe-oversight, shell gắn dưới `/oversight` (QT-12, QT-13, QT-14). Mỗi tuyến con chặn
 * theo chức năng của đúng SCR (wiki/15a); máy chủ vẫn kiểm quyền và trả 403. SCR-1201, SCR-1202 (WEB-042)
 * và SCR-1401 (WEB-044) còn chờ.
 */
export const ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./pages/oversight-home.page').then((m) => m.OversightHomePage),
    title: 'Giám sát, hậu kiểm',
  },
  {
    // SCR-1301: không có khoá "xem" riêng, ai có một chức năng của màn hình thì vào được
    path: 'reopen-requests',
    canActivate: [permissionGuard(...screenKeys('SCR-1301'))],
    children: [
      {
        path: '',
        loadComponent: () =>
          import('./pages/reopen/reopen-list.page').then((m) => m.ReopenListPage),
        title: 'Hậu kiểm, mở lại kỳ',
      },
      {
        path: 'new',
        canActivate: [permissionGuard('SCR-1301.tao')],
        loadComponent: () =>
          import('./pages/reopen/reopen-create.page').then((m) => m.ReopenCreatePage),
        title: 'Tạo yêu cầu mở lại kỳ',
      },
      {
        path: ':id',
        loadComponent: () =>
          import('./pages/reopen/reopen-detail.page').then((m) => m.ReopenDetailPage),
        title: 'Yêu cầu mở lại kỳ',
      },
    ],
  },
  {
    path: 'ledger',
    canActivate: [permissionGuard('SCR-1302.tra-cuu')],
    loadComponent: () => import('./pages/ledger/ledger.page').then((m) => m.LedgerPage),
    title: 'Sổ cái và kiểm tra toàn vẹn',
  },
];

export default ROUTES;
