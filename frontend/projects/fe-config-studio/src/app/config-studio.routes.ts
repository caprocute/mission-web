import { Routes } from '@angular/router';
import { firstPolicyTab } from './pages/policy/policy-tabs';
import { permissionGuard } from '@vt/auth';
import { FORM_KEYS, POLICY_KEYS, StudioLayout, UI_PACK_KEYS, studioLanding } from './studio-layout';

const SECTIONS: Routes = [
  {
    path: '',
    pathMatch: 'full',
    canActivate: [studioLanding, permissionGuard(...POLICY_KEYS)],
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
  {
    path: 'giao-dien',
    canActivate: [permissionGuard(...UI_PACK_KEYS)],
    loadComponent: () => import('./pages/ui-pack/ui-pack.page').then((m) => m.UiPackPage),
    title: 'Giao diện đơn vị',
  },
  {
    path: 'bieu-mau',
    canActivate: [permissionGuard(...FORM_KEYS)],
    loadComponent: () => import('./pages/form-builder/forms.page').then((m) => m.FormsPage),
    title: 'Biểu mẫu động',
  },
  {
    path: 'bieu-mau/:formKey/:formVersion',
    canActivate: [permissionGuard(...FORM_KEYS)],
    loadComponent: () =>
      import('./pages/form-builder/form-builder.page').then((m) => m.FormBuilderPage),
    title: 'Trình dựng biểu mẫu',
  },
];

/**
 * Tuyến của remote fe-config-studio, shell gắn dưới `/config-studio` (QT-15, QT-18). Mỗi tuyến chặn
 * theo chức năng của SCR trong ma trận wiki/15a; máy chủ vẫn kiểm quyền và trả 403.
 */
export const ROUTES: Routes = [{ path: '', component: StudioLayout, children: SECTIONS }];

export default ROUTES;
