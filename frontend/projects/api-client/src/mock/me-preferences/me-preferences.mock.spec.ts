import { HttpHeaders, HttpParams } from '@angular/common/http';
import { beforeEach, describe, expect, it } from 'vitest';
import { MockRequest, matchPath } from '../mock-router';
import { ME_PREFERENCES_MOCK, applyHomeOverlay } from './me-preferences.mock';

function call(method: string, path: string, body: unknown = null, headers: Record<string, string> = {}) {
  for (const r of ME_PREFERENCES_MOCK.routes) {
    const pathParams = r.method === method ? matchPath(r.pattern, path) : null;
    if (pathParams) {
      const req: MockRequest = {
        method,
        path,
        params: new HttpParams(),
        headers: new HttpHeaders(headers),
        body,
        pathParams,
      };
      return r.handle(req);
    }
  }
  throw new Error(`không có tuyến ${method} ${path}`);
}

const PREF = '/api/v1/config/me/preferences/ui.home.layout';

describe('ME_PREFERENCES_MOCK (CNH-03)', () => {
  beforeEach(() => ME_PREFERENCES_MOCK.reset());

  it('tạo mới 201, ghi tiếp cần If-Match (428), sai phiên bản thì 409', () => {
    const value = { hiddenWidgets: ['vt.widget.announcement'] };
    expect(call('PUT', PREF, { value }).status).toBe(201);
    expect(call('PUT', PREF, { value }).status).toBe(428);
    const conflict = call('PUT', PREF, { value }, { 'If-Match': '"9"' });
    expect(conflict.status).toBe(409);
    expect((conflict.body as { code: string }).code).toBe('CONFIG-409-VERSION-CONFLICT');
    expect(call('PUT', PREF, { value }, { 'If-Match': '"1"' }).status).toBe(200);
  });

  it('bố cục trang chủ áp lớp phủ, không ẩn khối bắt buộc, xoá thì về mặc định', () => {
    call('PUT', PREF, {
      value: {
        hiddenWidgets: ['vt.widget.announcement', 'vt.widget.myTasksToday'],
        widgetOrder: [{ id: 'vt.widget.pendingApprovals', order: 0 }],
      },
    });
    const home = call('GET', '/api/v1/ui/layouts/home').body as {
      layout: { children: { component: string }[] };
      personalizationMeta: { hasOverlay: boolean; mandatoryWidgetIds: string[] };
    };
    expect(home.layout.children.map((c) => c.component)).toEqual([
      'vt.widget.pendingApprovals',
      'vt.widget.myTasksToday',
      'vt.widget.okrProgress',
    ]);
    expect(home.personalizationMeta).toMatchObject({ hasOverlay: true, mandatoryWidgetIds: ['vt.widget.myTasksToday'] });
    expect(call('DELETE', PREF, null, { 'If-Match': '"1"' }).status).toBe(204);
    const reset = call('GET', '/api/v1/ui/layouts/home').body as { layout: { children: unknown[] } };
    expect(reset.layout.children).toHaveLength(4);
  });

  it('ghim thắng ẩn, mã lạ bị bỏ qua', () => {
    const base = { component: 'g', children: [{ component: 'a' }, { component: 'b' }] };
    const { layout } = applyHomeOverlay(base, { pinnedWidgets: ['b', 'x'], hiddenWidgets: ['b'] });
    expect(layout.children?.map((c) => c.component)).toEqual(['b', 'a']);
  });
});
