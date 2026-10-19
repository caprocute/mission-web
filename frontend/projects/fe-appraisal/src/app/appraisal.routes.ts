import { inject } from '@angular/core';
import { CanActivateFn, Router, Routes } from '@angular/router';
import { PermissionService, permissionGuard } from '@vt/auth';

const review = () => import('./pages/review/review.page').then((m) => m.ReviewPage);

const ratingSheet = () =>
  import('./pages/rating-sheet/rating-sheet.page').then((m) => m.RatingSheetPage);

const selfAssessment = () =>
  import('./pages/self-assessment/self-assessment.page').then((m) => m.SelfAssessmentPage);

/**
 * Trang đầu `/appraisal`: vai trò có SCR-0901 (`member`, `manager`) vào thẳng phiếu tự đánh giá, vai trò
 * có SCR-0902 (`appraiser`) vào hàng đợi thẩm định, vai trò còn lại có SCR-0903 (`decider`) vào danh
 * sách kết quả xếp loại; vai trò khác ở trang khung cho tới khi SCR-0904 … SCR-1002 được dựng.
 */
const firstScreen: CanActivateFn = (_route, state) => {
  const perms = inject(PermissionService);
  const target = perms.can('SCR-0901.xem')
    ? 'tu-danh-gia'
    : perms.can('SCR-0902.xem')
      ? 'tham-dinh'
      : perms.can('SCR-0903.xem')
        ? 'ket-qua'
        : null;
  if (!target) return true;
  const [path, query] = state.url.split('?');
  const url = path.replace(/\/$/, '') + '/' + target + (query ? '?' + query : '');
  return inject(Router).parseUrl(url);
};

/**
 * Tuyến của remote fe-appraisal, shell gắn dưới `/appraisal` (QT-09, QT-10). SCR-0901 Phiếu tự đánh
 * giá (WEB-035): `tu-danh-gia` là hồ sơ của tôi (`?flow=` chọn luồng), `tu-danh-gia/:id` mở một hồ sơ
 * theo mã (quản lý xem chỉ đọc). SCR-0902 Thẩm định (WEB-036): `tham-dinh` mở hồ sơ đầu hàng đợi,
 * `tham-dinh/:id` mở một hồ sơ. SCR-0903 Phiếu xếp loại (WEB-037): `ket-qua` là danh sách kết quả của tôi
 * và hồ sơ trong phạm vi, `ket-qua/:id` mở phiếu xếp loại của một hồ sơ. Tuyến con chặn theo chức năng của đúng SCR (wiki/15a).
 */
export const ROUTES: Routes = [
  {
    path: '',
    canActivate: [firstScreen],
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
  {
    path: 'tham-dinh',
    canActivate: [permissionGuard('SCR-0902.xem')],
    loadComponent: review,
    title: 'Thẩm định hồ sơ đánh giá',
  },
  {
    path: 'tham-dinh/:id',
    canActivate: [permissionGuard('SCR-0902.xem')],
    loadComponent: review,
    title: 'Thẩm định hồ sơ đánh giá',
  },
  {
    path: 'ket-qua',
    canActivate: [permissionGuard('SCR-0903.xem')],
    loadComponent: () => import('./pages/rating-sheet/results.page').then((m) => m.ResultsPage),
    title: 'Kết quả xếp loại',
  },
  {
    path: 'ket-qua/:id',
    canActivate: [permissionGuard('SCR-0903.xem')],
    loadComponent: ratingSheet,
    title: 'Phiếu xếp loại',
  },
];

export default ROUTES;
