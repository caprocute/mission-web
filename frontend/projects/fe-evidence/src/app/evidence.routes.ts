import { Routes } from '@angular/router';

/**
 * Tuyến của remote fe-evidence, shell gắn dưới `/evidence` (QT-07): `?task=` từ SCR-0601 "Nộp sản phẩm",
 * `/evidence/:id` là SCR-0701 Nộp minh chứng.
 */
export const ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./pages/evidence-list.page').then((m) => m.EvidenceListPage),
    title: 'Sản phẩm và minh chứng',
  },
  {
    path: ':id',
    loadComponent: () => import('./pages/evidence-submit.page').then((m) => m.EvidenceSubmitPage),
    title: 'Nộp minh chứng',
  },
];

export default ROUTES;
