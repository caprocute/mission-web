import { Routes } from '@angular/router';

/** Tuyến kế hoạch quý của remote fe-task (QT-04): SCR-0402 và SCR-0403, shell gắn dưới `/plans`. */
export const PLAN_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./pages/plan.page').then((m) => m.PlanPage),
    title: 'Kế hoạch công tác quý',
  },
  {
    path: ':id/review',
    loadComponent: () => import('./pages/plan-review.page').then((m) => m.PlanReviewPage),
    title: 'Phê duyệt kế hoạch',
  },
];

export default PLAN_ROUTES;
