import { Routes } from '@angular/router';
import { permissionGuard } from '@vt/auth';

/**
 * Tuyến của remote fe-dashboard, shell gắn dưới `/dashboard` (QT-16). Mỗi tuyến con chặn theo chức năng
 * của đúng SCR (wiki/15a); máy chủ vẫn kiểm quyền, lọc số liệu theo phạm vi và trả 403.
 */
export const ROUTES: Routes = [
  {
    // SCR-1601 Bảng điều hành lãnh đạo (WEB-047)
    path: '',
    loadComponent: () => import('./pages/dashboard-home.page').then((m) => m.DashboardHomePage),
    title: 'Bảng điều hành',
  },
  {
    // SCR-1603 bước đơn vị, cán bộ
    path: 'units',
    canActivate: [permissionGuard('SCR-1603.xem')],
    loadComponent: () => import('./pages/unit-drill.page').then((m) => m.UnitDrillPage),
    title: 'Kết quả đánh giá của đơn vị',
  },
  {
    // SCR-1603 Đi xuống tới minh chứng (WEB-047)
    path: 'trace/:objectType/:id',
    canActivate: [permissionGuard('SCR-1603.xem')],
    loadComponent: () => import('./pages/trace.page').then((m) => m.TracePage),
    title: 'Truy vết kết quả đánh giá',
  },
  {
    // SCR-1602 Trình dựng dashboard (WEB-048)
    path: 'dashboards',
    canActivate: [permissionGuard('SCR-1602.dung')],
    children: [
      {
        path: '',
        loadComponent: () => import('./pages/my-dashboards.page').then((m) => m.MyDashboardsPage),
        title: 'Dashboard của tôi',
      },
      {
        path: 'new',
        loadComponent: () =>
          import('./pages/dashboard-builder.page').then((m) => m.DashboardBuilderPage),
        title: 'Tạo dashboard',
      },
      {
        path: ':id',
        loadComponent: () => import('./pages/dashboard-view.page').then((m) => m.DashboardViewPage),
        title: 'Dashboard',
      },
      {
        path: ':id/edit',
        loadComponent: () =>
          import('./pages/dashboard-builder.page').then((m) => m.DashboardBuilderPage),
        title: 'Sửa dashboard',
      },
    ],
  },
];

export default ROUTES;
