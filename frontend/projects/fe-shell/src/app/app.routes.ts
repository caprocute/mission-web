import { inject } from '@angular/core';
import { Routes } from '@angular/router';
import { authGuard, permissionGuard } from '@vt/auth';
import { remoteRoutes } from './remotes';
import { PluginHost } from './plugins/plugin-host';
import { ShellLayout } from './layout/shell.layout';
import { SECTION_KEYS as K } from './layout/nav';

export const routes: Routes = [
  {
    path: '',
    component: ShellLayout,
    canActivate: [authGuard],
    children: [
      {
        path: '',
        loadComponent: () => import('./pages/home.page').then((m) => m.HomePage),
        title: 'Trang chủ',
      },
      // fe-task: QT-04, QT-05, QT-06
      {
        path: 'tasks',
        canMatch: [permissionGuard(...K.tasks)],
        loadChildren: remoteRoutes('feTask'),
        title: 'Việc của tôi',
      },
      // Remote đã có khung (WEB-015); danh mục ở frontend/tools/remotes/remotes.json.
      // Tuyến cha chặn bằng canMatch theo nhóm SCR (WEB-055, wiki/15a): đủ một chức năng là vào,
      // bị chặn thì sang trang "không có quyền" mà không tải remote; tuyến con chặn riêng từng SCR.
      // fe-okr: QT-02, QT-03 (SCR-0201, SCR-0202, SCR-0203, SCR-0301)
      {
        path: 'okr',
        canMatch: [permissionGuard(...K.okr)],
        loadChildren: remoteRoutes('feOkr'),
        title: 'Mục tiêu và OKR',
      },
      // fe-task: QT-04 (SCR-0402, SCR-0403); SCR-0401 ở /tasks/catalog
      {
        path: 'plans',
        canMatch: [permissionGuard(...K.plans)],
        loadChildren: remoteRoutes('feTask', './plan-routes'),
        title: 'Kế hoạch công tác quý',
      },
      // fe-evidence: QT-07 (SCR-0701, SCR-0703)
      {
        path: 'evidence',
        canMatch: [permissionGuard(...K.evidence)],
        loadChildren: remoteRoutes('feEvidence'),
        title: 'Sản phẩm và minh chứng',
      },
      // fe-appraisal: QT-09, QT-10 (SCR-0901 … SCR-0904, SCR-1001, SCR-1002)
      {
        path: 'appraisal',
        canMatch: [permissionGuard(...K.appraisal)],
        loadChildren: remoteRoutes('feAppraisal'),
        title: 'Đánh giá, xếp loại',
      },
      // fe-dossier: QT-11 (SCR-1101, SCR-1102)
      {
        path: 'dossier',
        canMatch: [permissionGuard(...K.dossier)],
        loadChildren: remoteRoutes('feDossier'),
        title: 'Kiểm điểm, hồ sơ số',
      },
      // fe-oversight: QT-12, QT-13, QT-14 (SCR-1201, SCR-1202, SCR-1301, SCR-1302, SCR-1401)
      {
        path: 'oversight',
        canMatch: [permissionGuard(...K.oversight)],
        loadChildren: remoteRoutes('feOversight'),
        title: 'Giám sát, hậu kiểm',
      },
      // fe-dashboard: QT-16 (SCR-1601 … SCR-1603)
      {
        path: 'dashboard',
        canMatch: [permissionGuard(...K.dashboard)],
        loadChildren: remoteRoutes('feDashboard'),
        title: 'Bảng điều hành',
      },
      // fe-config-studio: QT-15, QT-18 (SCR-1501 … SCR-1506, SCR-1801, SCR-1802)
      {
        path: 'config-studio',
        canMatch: [permissionGuard(...K.configStudio)],
        loadChildren: remoteRoutes('feConfigStudio'),
        title: 'Cấu hình nghiệp vụ',
      },
      // fe-admin: QT-01, QT-08 (SCR-0101 … SCR-0104, SCR-0801)
      {
        path: 'admin',
        canMatch: [permissionGuard(...K.admin)],
        loadChildren: remoteRoutes('feAdmin'),
        title: 'Quản trị',
      },
      // Plugin chuyên ngành theo UI Pack của tenant (ADR-05 lớp 4, WEB-051): `/plugin/<ngành>`
      {
        path: 'plugin',
        loadChildren: () => inject(PluginHost).routes(),
      },
      {
        path: 'loi',
        loadComponent: () => import('./pages/error.page').then((m) => m.ErrorPage),
        title: 'Chưa mở được trang',
      },
      {
        path: 'khong-co-quyen',
        loadComponent: () => import('./pages/forbidden.page').then((m) => m.ForbiddenPage),
        title: 'Không có quyền truy cập',
      },
    ],
  },
  { path: '**', loadComponent: () => import('./pages/not-found.page').then((m) => m.NotFoundPage) },
];
