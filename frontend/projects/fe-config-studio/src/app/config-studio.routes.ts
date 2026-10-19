import { Routes } from '@angular/router';
import { permissionGuard } from '@vt/auth';

/**
 * Tuyến của remote fe-config-studio, shell gắn dưới `/config-studio` (QT-15, QT-18). Mỗi tuyến chặn
 * theo chức năng của SCR trong ma trận wiki/15a; máy chủ vẫn kiểm quyền và trả 403.
 */
export const ROUTES: Routes = [
  {
    path: '',
    canActivate: [permissionGuard('SCR-1501.soan')],
    loadComponent: () => import('./pages/policy-packs.page').then((m) => m.PolicyPacksPage),
    title: 'Chính sách đánh giá',
  },
  {
    path: 'goi/:packId/:version',
    canActivate: [permissionGuard('SCR-1501.soan')],
    loadComponent: () => import('./pages/policy/policy-shell.page').then((m) => m.PolicyShellPage),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'cong-thuc' },
      {
        path: 'cong-thuc',
        loadComponent: () => import('./pages/policy/formula.page').then((m) => m.FormulaPage),
        title: 'Công thức tính điểm',
      },
      {
        path: 'bang-quyet-dinh',
        canActivate: [permissionGuard('SCR-1502.soan')],
        loadComponent: () =>
          import('./pages/policy/decision-table.page').then((m) => m.DecisionTablePage),
        title: 'Bảng quyết định xếp loại',
      },
    ],
  },
];

export default ROUTES;
