import { Routes } from '@angular/router';
import { authGuard, roleGuard } from '@vt/auth';
import { remoteRoutes } from './remotes';
import { ShellLayout } from './layout/shell.layout';

const planned = (title: string, wave: string, screens: string) => ({
  loadComponent: () => import('./pages/planned.page').then((m) => m.PlannedPage),
  data: { title, wave, screens },
  title,
});

export const routes: Routes = [
  {
    path: '',
    component: ShellLayout,
    canActivate: [authGuard],
    children: [
      { path: '', loadComponent: () => import('./pages/home.page').then((m) => m.HomePage), title: 'Trang chủ' },
      // fe-task: QT-04, QT-05, QT-06
      { path: 'tasks', loadChildren: remoteRoutes('feTask'), title: 'Việc của tôi' },
      // Các remote còn lại theo lộ trình trong docs/KE-HOACH-PHAT-TRIEN-PHAN-HE-WEB.md
      { path: 'okr', ...planned('Mục tiêu và OKR', 'Giai đoạn 2', 'SCR-0201, SCR-0202, SCR-0203, SCR-0301') },
      { path: 'plans', ...planned('Kế hoạch công tác quý', 'Giai đoạn 2', 'SCR-0401, SCR-0402, SCR-0403') },
      { path: 'evidence', ...planned('Sản phẩm và minh chứng', 'Giai đoạn 2', 'SCR-0701, SCR-0703') },
      { path: 'appraisal', ...planned('Đánh giá, xếp loại', 'Giai đoạn 3', 'SCR-0901 … SCR-0904, SCR-1001, SCR-1002') },
      { path: 'dossier', ...planned('Kiểm điểm, hồ sơ số', 'Giai đoạn 3', 'SCR-1101, SCR-1102') },
      { path: 'oversight', ...planned('Giám sát, hậu kiểm', 'Giai đoạn 3', 'SCR-1201, SCR-1202, SCR-1301, SCR-1302, SCR-1401') },
      { path: 'dashboard', canActivate: [roleGuard('leader', 'manager', 'inspector')], ...planned('Bảng điều hành', 'Giai đoạn 4', 'SCR-1601 … SCR-1603') },
      { path: 'config-studio', canActivate: [roleGuard('policy_author', 'tenant_admin')], ...planned('Config Studio', 'Giai đoạn 4', 'SCR-1501 … SCR-1504, SCR-1801, SCR-1802') },
      { path: 'admin', canActivate: [roleGuard('platform_admin', 'tenant_admin')], ...planned('Quản trị tenant', 'Giai đoạn 1', 'SCR-0101 … SCR-0104, SCR-0801') },
    ],
  },
  { path: '**', loadComponent: () => import('./pages/not-found.page').then((m) => m.NotFoundPage) },
];
