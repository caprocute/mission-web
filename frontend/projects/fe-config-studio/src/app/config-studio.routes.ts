import { Routes } from '@angular/router';

/**
 * Tuyến của remote fe-config-studio, shell gắn dưới `/config-studio` (QT-15, QT-18).
 * Luồng Màn hình nghiệp vụ thay trang khung bằng các màn hình SCR-1501 … SCR-1504, SCR-1801, SCR-1802.
 */
export const ROUTES: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./pages/config-studio-home.page').then((m) => m.ConfigStudioHomePage),
    title: 'Config Studio',
  },
];

export default ROUTES;
