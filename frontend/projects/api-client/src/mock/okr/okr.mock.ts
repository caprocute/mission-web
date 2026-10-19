import { MockModule, ok } from '../mock-router';
import fixture from './okr.json';

/** Giả lập `/api/v1/okr/key-results` (KR để liên kết nhiệm vụ, WEB-028); WEB-021 mở rộng. */
export const OKR_MOCK: MockModule = {
  name: 'okr',
  reset: () => undefined,
  routes: [
    {
      method: 'GET',
      pattern: '/api/v1/okr/key-results',
      handle: () => ok({ items: fixture.keyResults, nextCursor: null }),
    },
  ],
};
