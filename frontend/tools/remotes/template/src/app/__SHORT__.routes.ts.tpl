import { Routes } from '@angular/router';

/**
 * Tuyến của remote __PROJECT__, shell gắn dưới `/__ROUTE__` (__QT__).
 * Luồng Màn hình nghiệp vụ thay trang khung bằng các màn hình __SCREENS__.
 */
export const ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./pages/__SHORT__-home.page').then((m) => m.__CLASS__HomePage),
    title: '__TITLE__',
  },
];

export default ROUTES;
