import { MAX_PAGE_LIMIT } from '../../lib/paging';
import { CatalogItem } from '../../lib/models/catalog';
import { MockModule, ok } from '../mock-router';
import fixture from './catalog.json';

/**
 * Giả lập API `/api/v1/catalog` của MOD-02 (QT-04). Hiện chỉ có danh sách mục áp dụng cho
 * người dùng, đủ cho khối đăng ký nhiệm vụ của SCR-0503; WEB-025 bổ sung cho SCR-0401.
 */
export const CATALOG_MOCK: MockModule = {
  name: 'catalog',
  reset: () => undefined,
  routes: [
    {
      method: 'GET',
      pattern: '/api/v1/catalog/items',
      handle: (r) => {
        const limit = Math.min(Number(r.params.get('limit') ?? 50) || 50, MAX_PAGE_LIMIT);
        const q = r.params.get('q')?.toLowerCase();
        let items = fixture.items as CatalogItem[];
        if (q)
          items = items.filter(
            (i) => i.name.toLowerCase().includes(q) || i.code.toLowerCase().includes(q),
          );
        return ok({ items: items.slice(0, limit), nextCursor: null });
      },
    },
  ],
};
