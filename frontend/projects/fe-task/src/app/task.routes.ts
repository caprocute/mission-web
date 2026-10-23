import { inject } from '@angular/core';
import { CanActivateFn, Router, Routes } from '@angular/router';
import { PermissionService, permissionGuard, screenKeys } from '@vt/auth';

const MY_TASKS = screenKeys('SCR-0503');
const ASSIGN = screenKeys('SCR-0501');
const CATALOG = screenKeys('SCR-0401');
const SOURCE_DOCS = screenKeys('SCR-0502');
/** Hàng chờ duyệt: đăng ký nhiệm vụ (QT-05 bước 3) và đề nghị gia hạn, điều chỉnh (SCR-0601). */
const APPROVALS = ['SCR-0501.giao', 'SCR-0601.duyet-de-nghi'] as const;

/**
 * Trang đầu `/tasks` là SCR-0503 của `member`. Vai trò không có SCR-0503 nhưng có màn hình khác của
 * mục "Việc của tôi" được chuyển tới màn hình đó thay vì gặp trang không có quyền: `manager` không
 * kèm `member` sang Giao việc (SCR-0501), `policy_author`, `leader` sang Thư viện danh mục (SCR-0401).
 */
const myTasksOrFirstAllowed: CanActivateFn = (route, state) => {
  const permissions = inject(PermissionService);
  if (!permissions.canAny(...MY_TASKS)) {
    const base = state.url.split('?')[0].replace(/\/$/, '');
    if (permissions.canAny(...ASSIGN)) return inject(Router).parseUrl(base + '/assign');
    if (permissions.canAny(...CATALOG)) return inject(Router).parseUrl(base + '/catalog');
  }
  return permissionGuard(...MY_TASKS)(route, state);
};

/**
 * Tuyến của remote fe-task, shell gắn dưới `/tasks`. Mỗi tuyến con chặn theo chức năng của đúng SCR
 * của nó (WEB-055, wiki/15a); dùng `canActivate` thay vì `canMatch` để `assign` bị chặn không rơi
 * xuống khớp `:id`.
 */
export const TASK_ROUTES: Routes = [
  {
    path: '',
    canActivate: [myTasksOrFirstAllowed],
    loadComponent: () => import('./pages/my-tasks.page').then((m) => m.MyTasksPage),
    title: 'Việc của tôi',
  },
  {
    path: 'catalog',
    canActivate: [permissionGuard(...CATALOG)],
    loadComponent: () => import('./pages/catalog.page').then((m) => m.CatalogPage),
    title: 'Thư viện danh mục công việc',
  },
  {
    path: 'documents',
    canActivate: [permissionGuard(...SOURCE_DOCS)],
    loadComponent: () => import('./pages/source-docs.page').then((m) => m.SourceDocsPage),
    title: 'Văn bản đến',
  },
  {
    path: 'documents/:id',
    canActivate: [permissionGuard(...SOURCE_DOCS)],
    loadComponent: () => import('./pages/from-document.page').then((m) => m.FromDocumentPage),
    title: 'Tạo nhiệm vụ từ văn bản',
  },
  {
    path: 'approvals',
    canActivate: [permissionGuard(...APPROVALS)],
    loadComponent: () => import('./pages/approvals.page').then((m) => m.ApprovalsPage),
    title: 'Chờ duyệt',
  },
  {
    path: 'assign',
    canActivate: [permissionGuard(...ASSIGN)],
    loadComponent: () => import('./pages/assign-task.page').then((m) => m.AssignTaskPage),
    title: 'Giao việc',
  },
  {
    path: 'assign/:id',
    canActivate: [permissionGuard(...ASSIGN)],
    loadComponent: () => import('./pages/assign-task.page').then((m) => m.AssignTaskPage),
    title: 'Giao việc',
  },
  {
    path: ':id',
    canActivate: [permissionGuard(...screenKeys('SCR-0601', 'SCR-0602'))],
    loadComponent: () => import('./pages/task-detail.page').then((m) => m.TaskDetailPage),
    title: 'Chi tiết nhiệm vụ',
  },
];

export default TASK_ROUTES;
