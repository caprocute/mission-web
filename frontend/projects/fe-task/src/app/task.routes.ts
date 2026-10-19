import { Routes } from '@angular/router';

/** Tuyến của remote fe-task, shell gắn dưới `/tasks`. */
export const TASK_ROUTES: Routes = [
  { path: '', loadComponent: () => import('./pages/my-tasks.page').then((m) => m.MyTasksPage), title: 'Việc của tôi' },
  { path: ':id', loadComponent: () => import('./pages/task-detail.page').then((m) => m.TaskDetailPage), title: 'Chi tiết nhiệm vụ' },
];

export default TASK_ROUTES;
