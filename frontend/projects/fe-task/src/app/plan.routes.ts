import { Routes } from '@angular/router';

/** Tuyến kế hoạch quý của remote fe-task (QT-04), shell gắn dưới `/plans`. */
export const PLAN_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./pages/plan.page').then((m) => m.PlanPage),
    title: 'Kế hoạch công tác quý',
  },
];

export default PLAN_ROUTES;
