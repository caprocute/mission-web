import { Routes } from '@angular/router';

/**
 * Tuyến của remote fe-oversight, shell gắn dưới `/oversight` (QT-12, QT-13, QT-14).
 * Luồng Màn hình nghiệp vụ thay trang khung bằng các màn hình SCR-1201, SCR-1202, SCR-1301, SCR-1302, SCR-1401.
 */
export const ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./pages/oversight-home.page').then((m) => m.OversightHomePage),
    title: 'Giám sát, hậu kiểm',
  },
];

export default ROUTES;
