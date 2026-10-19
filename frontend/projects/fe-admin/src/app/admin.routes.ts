import { Routes } from '@angular/router';

/**
 * Tuyến của remote fe-admin, shell gắn dưới `/admin` (QT-01, QT-08).
 * Luồng Màn hình nghiệp vụ thay trang khung bằng các màn hình SCR-0101 … SCR-0104, SCR-0801.
 */
export const ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./pages/admin-home.page').then((m) => m.AdminHomePage),
    title: 'Quản trị tenant',
  },
];

export default ROUTES;
