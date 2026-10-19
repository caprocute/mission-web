/** Phân trang theo con trỏ, `limit` tối đa 200 (quy ước API mục 13). */
export interface CursorPage<T> {
  items: T[];
  nextCursor: string | null;
}

export interface CursorQuery {
  cursor?: string;
  limit?: number;
}

export const MAX_PAGE_LIMIT = 200;
