import { HttpHeaders, HttpParams } from '@angular/common/http';
import { describe, expect, it } from 'vitest';
import { SearchResultPage } from '../../lib/models/notification';
import { SEARCH_MOCK, fold } from './search.mock';

function get(q: string, type?: string) {
  let params = new HttpParams().set('q', q);
  if (type) params = params.set('type', type);
  return SEARCH_MOCK.routes[0].handle({
    method: 'GET',
    path: '/api/v1/search',
    params,
    headers: new HttpHeaders(),
    body: null,
    pathParams: {},
  });
}

describe('SEARCH_MOCK (WEB-012)', () => {
  it('bỏ dấu nhưng giữ độ dài để vị trí khớp dùng được trên chuỗi gốc', () => {
    expect(fold('Đảng ủy')).toBe('dang uy');
    expect(fold('Đảng ủy')).toHaveLength('Đảng ủy'.length);
  });

  it('tìm không phân biệt dấu, trả đoạn trích và vị trí khớp', () => {
    const res = get('chuyen doi so');
    const page = res.body as SearchResultPage;
    expect(res.status).toBe(200);
    const first = page.items[0];
    expect(first.type).toBe('TASK');
    expect(first.deepLink).toMatch(/^\/tasks\//);
    const h = first.highlights![0];
    expect(fold(first.snippet!.slice(h.start, h.start + h.length))).toBe('chuyen doi so');
  });

  it('lọc theo loại và từ chối từ khoá quá ngắn', () => {
    const docs = get('kiểm điểm', 'SOURCE_DOC').body as SearchResultPage;
    expect(docs.items.every((i) => i.type === 'SOURCE_DOC')).toBe(true);
    expect(get('a').status).toBe(400);
  });
});
