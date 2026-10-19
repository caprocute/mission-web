import { Routes } from '@angular/router';

/**
 * Tuyến của remote fe-evidence, shell gắn dưới `/evidence` (QT-07).
 * Luồng Màn hình nghiệp vụ thay trang khung bằng các màn hình SCR-0701, SCR-0703.
 */
export const ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./pages/evidence-home.page').then((m) => m.EvidenceHomePage),
    title: 'Sản phẩm và minh chứng',
  },
];

export default ROUTES;
