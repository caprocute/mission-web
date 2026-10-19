import { inject } from '@angular/core';
import { CanActivateFn, Router, Routes } from '@angular/router';
import { permissionGuard } from '@vt/auth';
import { ADMIN_CAN } from './admin-permissions';

/** Trang đầu `/admin`: quản trị nền tảng vào thẳng Danh sách tenant (SCR-0101). */
const platformHome: CanActivateFn = (_route, state) => {
  if (!inject(ADMIN_CAN)('SCR-0101.xem')) return true;
  const base = state.url.split('?')[0].replace(/\/$/, '');
  return inject(Router).parseUrl(`${base}/tenants`);
};

/**
 * Tuyến của remote fe-admin, shell gắn dưới `/admin` (QT-01, QT-08). Mỗi tuyến con chặn theo chức
 * năng của đúng SCR (wiki/15a); máy chủ vẫn kiểm quyền và trả 403.
 */
export const ROUTES: Routes = [
  {
    path: '',
    canActivate: [platformHome],
    loadComponent: () => import('./pages/admin-home.page').then((m) => m.AdminHomePage),
    title: 'Quản trị tenant',
  },
  {
    path: 'tenants',
    canActivate: [permissionGuard('SCR-0101.xem')],
    loadComponent: () => import('./pages/tenants.page').then((m) => m.TenantsPage),
    title: 'Danh sách tenant',
  },
  {
    path: 'tenants/new',
    canActivate: [permissionGuard('SCR-0102.buoc-1-3')],
    loadComponent: () =>
      import('./pages/tenant-onboarding.page').then((m) => m.TenantOnboardingPage),
    title: 'Khởi tạo tenant',
  },
  {
    // mở lại bản nháp `?step=n`; bước 4, 5 do quản trị tenant (AC-02) làm, AC-01 theo dõi
    path: 'tenants/:id/onboarding',
    canActivate: [permissionGuard('SCR-0102.buoc-4-5')],
    loadComponent: () =>
      import('./pages/tenant-onboarding.page').then((m) => m.TenantOnboardingPage),
    title: 'Khởi tạo tenant',
  },
  {
    path: 'org',
    canActivate: [permissionGuard('SCR-0103.xem')],
    loadComponent: () => import('./pages/org/org-tree.page').then((m) => m.OrgTreePage),
    title: 'Cây tổ chức',
  },
  {
    path: 'tenants/:id',
    canActivate: [permissionGuard('SCR-0101.xem')],
    loadComponent: () => import('./pages/tenant-detail.page').then((m) => m.TenantDetailPage),
    title: 'Chi tiết tenant',
  },
];

export default ROUTES;
