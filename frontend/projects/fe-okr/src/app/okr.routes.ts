import { Routes } from '@angular/router';

/**
 * Tuyến của remote fe-okr, shell gắn dưới `/okr` (QT-02, QT-03).
 * Luồng Màn hình nghiệp vụ thay trang khung bằng các màn hình SCR-0201, SCR-0202, SCR-0203, SCR-0301.
 */
export const ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./pages/okr-home.page').then((m) => m.OkrHomePage),
    title: 'Mục tiêu và OKR',
  },
];

export default ROUTES;
