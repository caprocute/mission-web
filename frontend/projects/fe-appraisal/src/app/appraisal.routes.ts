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
 * có SCR-0902 (`appraiser`) vào hàng đợi thẩm định, vai trò có SCR-0904 (`decider`) vào quyết định hàng
 * loạt, vai trò còn lại có SCR-0903 vào danh sách kết quả xếp loại, rồi tới SCR-1001 Xếp loại năm và
 * SCR-1002 Phân nhóm triển vọng; vai trò khác ở trang khung.
 */
const firstScreen: CanActivateFn = (_route, state) => {
  const perms = inject(PermissionService);
  const target = perms.can('SCR-0901.xem')
    ? 'tu-danh-gia'
    : perms.can('SCR-0902.xem')
      ? 'tham-dinh'
      : perms.can('SCR-0904.xem')
        ? 'quyet-dinh'
        : perms.can('SCR-0903.xem')
          ? 'ket-qua'
          : perms.can('SCR-1001.xem')
            ? 'xep-loai-nam'
            : perms.can('SCR-1002.xem')
              ? 'phan-nhom'
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
 * và hồ sơ trong phạm vi, `ket-qua/:id` mở phiếu xếp loại của một hồ sơ. SCR-0904 Quyết định xếp loại
 * hàng loạt (WEB-038): `quyet-dinh`. SCR-1001 Xếp loại năm và SCR-1002 Phân nhóm triển vọng (WEB-039):
 * `xep-loai-nam`, `phan-nhom` (`?period=` chọn kỳ năm, bỏ trống là kỳ năm mới nhất). Tuyến con chặn theo chức năng của đúng SCR (wiki/15a).
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
  {
    path: 'quyet-dinh',
    canActivate: [permissionGuard('SCR-0904.xem')],
    loadComponent: () =>
      import('./pages/batch-decision/batch-decision.page').then((m) => m.BatchDecisionPage),
    title: 'Quyết định xếp loại hàng loạt',
  },
  {
    path: 'xep-loai-nam',
    canActivate: [permissionGuard('SCR-1001.xem')],
    loadComponent: () => import('./pages/annual/annual.page').then((m) => m.AnnualPage),
    title: 'Xếp loại năm',
  },
  {
    path: 'phan-nhom',
    canActivate: [permissionGuard('SCR-1002.xem')],
    loadComponent: () => import('./pages/prospect/prospect.page').then((m) => m.ProspectPage),
    title: 'Phân nhóm triển vọng',
  },
];

export default ROUTES;
