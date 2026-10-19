import { Routes } from '@angular/router';

/**
 * Tuyến của remote fe-dossier, shell gắn dưới `/dossier` (QT-11).
 * Luồng Màn hình nghiệp vụ thay trang khung bằng các màn hình SCR-1101, SCR-1102.
 */
export const ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./pages/dossier-home.page').then((m) => m.DossierHomePage),
    title: 'Kiểm điểm, hồ sơ số',
  },
];

export default ROUTES;
