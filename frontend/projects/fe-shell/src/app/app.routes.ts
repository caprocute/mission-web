import { Routes } from '@angular/router';
import { authGuard, roleGuard } from '@vt/auth';
import { remoteRoutes } from './remotes';
import { ShellLayout } from './layout/shell.layout';

export const routes: Routes = [
  {
    path: '',
    component: ShellLayout,
    canActivate: [authGuard],
    children: [
      { path: '', loadComponent: () => import('./pages/home.page').then((m) => m.HomePage), title: 'Trang chủ' },
      // fe-task: QT-04, QT-05, QT-06
      { path: 'tasks', loadChildren: remoteRoutes('feTask'), title: 'Việc của tôi' },
      // Remote đã có khung (WEB-015); danh mục ở frontend/tools/remotes/remotes.json.
      // roleGuard giữ như cũ, chặn theo ma trận wiki/15a thuộc WEB-055.
      // fe-okr: QT-02, QT-03 (SCR-0201, SCR-0202, SCR-0203, SCR-0301)
      { path: 'okr', loadChildren: remoteRoutes('feOkr'), title: 'Mục tiêu và OKR' },
      // fe-task: QT-04 (SCR-0402, SCR-0403); SCR-0401 ở /tasks/catalog
      { path: 'plans', loadChildren: remoteRoutes('feTask', './plan-routes'), title: 'Kế hoạch công tác quý' },
      // fe-evidence: QT-07 (SCR-0701, SCR-0703)
      { path: 'evidence', loadChildren: remoteRoutes('feEvidence'), title: 'Sản phẩm và minh chứng' },
      // fe-appraisal: QT-09, QT-10 (SCR-0901 … SCR-0904, SCR-1001, SCR-1002)
      { path: 'appraisal', loadChildren: remoteRoutes('feAppraisal'), title: 'Đánh giá, xếp loại' },
      // fe-dossier: QT-11 (SCR-1101, SCR-1102)
      { path: 'dossier', loadChildren: remoteRoutes('feDossier'), title: 'Kiểm điểm, hồ sơ số' },
      // fe-oversight: QT-12, QT-13, QT-14 (SCR-1201, SCR-1202, SCR-1301, SCR-1302, SCR-1401)
      { path: 'oversight', loadChildren: remoteRoutes('feOversight'), title: 'Giám sát, hậu kiểm' },
      // fe-dashboard: QT-16 (SCR-1601 … SCR-1603)
      { path: 'dashboard', canActivate: [roleGuard('leader', 'manager', 'inspector')], loadChildren: remoteRoutes('feDashboard'), title: 'Bảng điều hành' },
      // fe-config-studio: QT-15, QT-18 (SCR-1501 … SCR-1504, SCR-1801, SCR-1802)
      { path: 'config-studio', canActivate: [roleGuard('policy_author', 'tenant_admin')], loadChildren: remoteRoutes('feConfigStudio'), title: 'Config Studio' },
      // fe-admin: QT-01, QT-08 (SCR-0101 … SCR-0104, SCR-0801)
      { path: 'admin', canActivate: [roleGuard('platform_admin', 'tenant_admin')], loadChildren: remoteRoutes('feAdmin'), title: 'Quản trị tenant' },
    ],
  },
  { path: '**', loadComponent: () => import('./pages/not-found.page').then((m) => m.NotFoundPage) },
];
