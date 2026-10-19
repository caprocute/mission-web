import { Routes } from '@angular/router';

/** Tuyến của remote fe-task, shell gắn dưới `/tasks`. */
export const TASK_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./pages/my-tasks.page').then((m) => m.MyTasksPage),
    title: 'Việc của tôi',
  },
  {
    path: 'catalog',
    loadComponent: () => import('./pages/catalog.page').then((m) => m.CatalogPage),
    title: 'Thư viện danh mục công việc',
  },
  {
    path: 'assign',
    loadComponent: () => import('./pages/assign-task.page').then((m) => m.AssignTaskPage),
    title: 'Giao việc',
  },
  {
    path: 'assign/:id',
    loadComponent: () => import('./pages/assign-task.page').then((m) => m.AssignTaskPage),
    title: 'Giao việc',
  },
  {
    path: ':id',
    loadComponent: () => import('./pages/task-detail.page').then((m) => m.TaskDetailPage),
    title: 'Chi tiết nhiệm vụ',
  },
];

export default TASK_ROUTES;
