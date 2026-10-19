import { Routes } from '@angular/router';

/**
 * Tuyến của remote fe-dashboard, shell gắn dưới `/dashboard` (QT-16).
 * Luồng Màn hình nghiệp vụ thay trang khung bằng các màn hình SCR-1601 … SCR-1603.
 */
export const ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./pages/dashboard-home.page').then((m) => m.DashboardHomePage),
    title: 'Bảng điều hành',
  },
];

export default ROUTES;
