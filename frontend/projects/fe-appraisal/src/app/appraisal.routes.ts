import { inject } from '@angular/core';
import { CanActivateFn, Router, Routes } from '@angular/router';
import { PermissionService, permissionGuard } from '@vt/auth';

const selfAssessment = () =>
  import('./pages/self-assessment/self-assessment.page').then((m) => m.SelfAssessmentPage);

/**
 * Trang đầu `/appraisal`: vai trò có SCR-0901 (`member`, `manager`) vào thẳng phiếu tự đánh giá; vai trò
 * khác ở trang khung cho tới khi SCR-0902 … SCR-1002 được dựng.
 */
const selfAssessmentFirst: CanActivateFn = (_route, state) => {
  if (!inject(PermissionService).can('SCR-0901.xem')) return true;
  const [path, query] = state.url.split('?');
  const url = path.replace(/\/$/, '') + '/tu-danh-gia' + (query ? '?' + query : '');
  return inject(Router).parseUrl(url);
};

/**
 * Tuyến của remote fe-appraisal, shell gắn dưới `/appraisal` (QT-09, QT-10). SCR-0901 Phiếu tự đánh
 * giá (WEB-035): `tu-danh-gia` là hồ sơ của tôi (`?flow=` chọn luồng), `tu-danh-gia/:id` mở một hồ sơ
 * theo mã (quản lý xem chỉ đọc). Tuyến con chặn theo chức năng của đúng SCR (wiki/15a).
 */
export const ROUTES: Routes = [
  {
    path: '',
    canActivate: [selfAssessmentFirst],
    loadComponent: () => import('./pages/appraisal-home.page').then((m) => m.AppraisalHomePage),
    title: 'Đánh giá, xếp loại',
  },
  {
    path: 'tu-danh-gia',
    canActivate: [permissionGuard('SCR-0901.xem')],
    loadComponent: selfAssessment,
    title: 'Phiếu tự đánh giá',
  },
  {
    path: 'tu-danh-gia/:id',
    canActivate: [permissionGuard('SCR-0901.xem')],
    loadComponent: selfAssessment,
    title: 'Phiếu tự đánh giá',
  },
];

export default ROUTES;
