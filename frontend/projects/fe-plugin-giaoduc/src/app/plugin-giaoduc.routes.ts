import { Routes } from '@angular/router';
import { permissionGuard, screenKeys } from '@vt/auth';

/**
 * Tuyến của plugin Giáo dục, shell gắn dưới `/plugin/giaoduc` (ADR-05 lớp 4). Màn hình chỉ đọc dữ
 * liệu qua API công bố `/api/v1/tasks/mine`, nên chặn theo cùng chức năng SCR-0503 của ma trận.
 */
export const ROUTES: Routes = [
  {
    path: '',
    canActivate: [permissionGuard(...screenKeys('SCR-0503'))],
    loadComponent: () => import('./pages/chuyen-mon.page').then((m) => m.ChuyenMonPage),
    title: 'Chuyên môn giáo dục',
  },
];

export default ROUTES;
