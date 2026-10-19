import { Routes } from '@angular/router';
import { permissionGuard } from '@vt/auth';

const planned = (heading: string, screen: string, task: string) => ({
  loadComponent: () => import('./pages/planned.page').then((m) => m.OkrPlannedPage),
  data: { heading, screen, task },
  title: heading,
});

const compose = () =>
  import('./pages/compose/objective-compose.page').then((m) => m.ObjectiveComposePage);

/**
 * Tuyến của remote fe-okr, shell gắn dưới `/okr` (QT-02, QT-03). Trang đầu là SCR-0201; tuyến con
 * chặn theo chức năng của đúng SCR (wiki/15a). SCR-0202 soạn mục tiêu (WEB-022); SCR-0203 phê duyệt
 * (WEB-023, `phe-duyet` theo `deepLink` của hộp việc); SCR-0301 giữ chỗ tới khi WEB-024 thay bằng màn hình thật.
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
    canActivate: [permissionGuard('SCR-0201.check-in')],
    ...planned('Check-in KR', 'SCR-0301', 'WEB-024'),
  },
];

export default ROUTES;
