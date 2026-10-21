import { Routes } from '@angular/router';
import { firstPolicyTab } from './pages/policy/policy-tabs';
import { PermissionKey, permissionGuard } from '@vt/auth';

/** Ai mở được gói chính sách: người soạn, người phê duyệt xem kết quả kiểm thử khi ký. */
const POLICY_KEYS: PermissionKey[] = [
  'SCR-1501.soan',
  'SCR-1503.chay',
  'SCR-1503.xem-duyet',
  'SCR-1504.cau-hinh',
];

/**
 * Tuyến của remote fe-config-studio, shell gắn dưới `/config-studio` (QT-15, QT-18). Mỗi tuyến chặn
 * theo chức năng của SCR trong ma trận wiki/15a; máy chủ vẫn kiểm quyền và trả 403.
 */
export const ROUTES: Routes = [
  {
    path: '',
    canActivate: [permissionGuard(...POLICY_KEYS)],
    loadComponent: () => import('./pages/policy-packs.page').then((m) => m.PolicyPacksPage),
    title: 'Chính sách đánh giá',
  },
  {
    path: 'goi/:packId/:version',
    canActivate: [permissionGuard(...POLICY_KEYS)],
    loadComponent: () => import('./pages/policy/policy-shell.page').then((m) => m.PolicyShellPage),
    children: [
      { path: '', pathMatch: 'full', redirectTo: firstPolicyTab },
      {
        path: 'cong-thuc',
        canActivate: [permissionGuard('SCR-1501.soan')],
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
      {
        path: 'test-case',
        canActivate: [permissionGuard('SCR-1503.chay', 'SCR-1503.xem-duyet')],
        loadComponent: () => import('./pages/policy/test-case.page').then((m) => m.TestCasePage),
        title: 'Chạy test case vàng',
      },
      {
        path: 'luong-duyet',
        canActivate: [permissionGuard('SCR-1504.cau-hinh')],
        loadComponent: () => import('./pages/policy/workflow.page').then((m) => m.WorkflowPage),
        title: 'Cấu hình luồng duyệt',
      },
    ],
  },
];

export default ROUTES;
