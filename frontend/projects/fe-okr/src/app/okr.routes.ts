import { Routes } from '@angular/router';
import { permissionGuard } from '@vt/auth';

const planned = (heading: string, screen: string, task: string) => ({
  loadComponent: () => import('./pages/planned.page').then((m) => m.OkrPlannedPage),
  data: { heading, screen, task },
  title: heading,
});

/**
 * Tuyến của remote fe-okr, shell gắn dưới `/okr` (QT-02, QT-03). Trang đầu là SCR-0201; tuyến con
 * chặn theo chức năng của đúng SCR (wiki/15a). SCR-0202, SCR-0203, SCR-0301 giữ chỗ tới khi
 * WEB-022, WEB-023, WEB-024 thay bằng màn hình thật.
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
    ...planned('Tạo, sửa mục tiêu và KR', 'SCR-0202', 'WEB-022'),
  },
  {
    path: 'checkin',
    canActivate: [permissionGuard('SCR-0201.check-in')],
    ...planned('Check-in KR', 'SCR-0301', 'WEB-024'),
  },
];

export default ROUTES;
