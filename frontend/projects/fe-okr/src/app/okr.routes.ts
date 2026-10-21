import { Routes } from '@angular/router';
import { permissionGuard } from '@vt/auth';

const compose = () =>
  import('./pages/compose/objective-compose.page').then((m) => m.ObjectiveComposePage);

/**
 * Tuyến của remote fe-okr, shell gắn dưới `/okr` (QT-02, QT-03). Trang đầu là SCR-0201; tuyến con
 * chặn theo chức năng của đúng SCR (wiki/15a). SCR-0202 soạn mục tiêu (WEB-022); SCR-0203 phê duyệt
 * (WEB-023, `phe-duyet` theo `deepLink` của hộp việc); SCR-0301 check-in KR (WEB-024); SCR-1701 trợ lý AI soạn
 * OKR (WEB-049).
 */
export const ROUTES: Routes = [
  {
    path: '',
    canActivate: [permissionGuard('SCR-0201.xem')],
    loadComponent: () => import('./pages/okr-tree.page').then((m) => m.OkrTreePage),
    title: 'Cây mục tiêu OKR',
  },
  {
    path: 'objectives/new',
    canActivate: [permissionGuard('SCR-0201.them')],
    loadComponent: compose,
    title: 'Soạn mục tiêu và KR',
  },
  {
    path: 'objectives/:id/edit',
    canActivate: [permissionGuard('SCR-0202.soan-don-vi', 'SCR-0202.soan-ca-nhan')],
    loadComponent: compose,
    title: 'Sửa mục tiêu và KR',
  },
  {
    path: 'phe-duyet',
    canActivate: [permissionGuard('SCR-0203.xem')],
    loadComponent: () => import('./pages/review/okr-review.page').then((m) => m.OkrReviewPage),
    title: 'Phê duyệt OKR',
  },
  {
    path: 'checkin',
    canActivate: [permissionGuard('SCR-0301.check-in')],
    loadComponent: () => import('./pages/checkin/checkin.page').then((m) => m.OkrCheckinPage),
    title: 'Check-in KR',
  },
  {
    path: 'tro-ly',
    canActivate: [permissionGuard('SCR-1701.hoi', 'SCR-1701.dung-goi-y', 'SCR-1701.trinh')],
    loadComponent: () =>
      import('./pages/assistant/okr-assistant.page').then((m) => m.OkrAssistantPage),
    title: 'Trợ lý AI soạn OKR',
  },
];

export default ROUTES;
