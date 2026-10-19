import { PlanItemRef } from '../../lib/models/plan';
import { MockModule, ok, resolveRelativeDates } from '../mock-router';
import fixture from './plans.json';

/** Dòng kế hoạch đã duyệt, ngày tương đối đã quy đổi. */
export const approvedPlanItems = (): PlanItemRef[] =>
  resolveRelativeDates(structuredClone(fixture.items) as PlanItemRef[]);

/** Giả lập `/api/v1/plans/items` (dòng kế hoạch đã duyệt, WEB-028); WEB-026 mở rộng. */
export const PLANS_MOCK: MockModule = {
  name: 'plans',
  reset: () => undefined,
  routes: [
    {
      method: 'GET',
      pattern: '/api/v1/plans/items',
      handle: () => ok({ items: approvedPlanItems(), nextCursor: null }),
    },
  ],
};
