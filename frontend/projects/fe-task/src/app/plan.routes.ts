import { Routes } from '@angular/router';
import { permissionGuard, screenKeys } from '@vt/auth';

/**
 * Tuyến kế hoạch quý của remote fe-task (QT-04), shell gắn dưới `/plans`: lập kế hoạch SCR-0402 và
 * phê duyệt SCR-0403, mỗi tuyến chặn riêng theo ma trận (WEB-055, wiki/15a).
 */
export const PLAN_ROUTES: Routes = [
  {
    path: '',
    canActivate: [permissionGuard(...screenKeys('SCR-0402'))],
    loadComponent: () => import('./pages/plan.page').then((m) => m.PlanPage),
    title: 'Kế hoạch công tác quý',
  },
  {
    path: ':id/review',
    canActivate: [permissionGuard(...screenKeys('SCR-0403'))],
    loadComponent: () => import('./pages/plan-review.page').then((m) => m.PlanReviewPage),
    title: 'Phê duyệt kế hoạch',
  },
];

export default PLAN_ROUTES;
