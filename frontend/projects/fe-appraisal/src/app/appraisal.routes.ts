import { Routes } from '@angular/router';

/**
 * Tuyến của remote fe-appraisal, shell gắn dưới `/appraisal` (QT-09, QT-10).
 * Luồng Màn hình nghiệp vụ thay trang khung bằng các màn hình SCR-0901 … SCR-0904, SCR-1001, SCR-1002.
 */
export const ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./pages/appraisal-home.page').then((m) => m.AppraisalHomePage),
    title: 'Đánh giá, xếp loại',
  },
];

export default ROUTES;
