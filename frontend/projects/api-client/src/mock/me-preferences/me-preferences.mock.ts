import {
  HomeLayout,
  HomeLayoutNode,
  HomeLayoutOverlay,
  SavedFilter,
  UserPreferenceItem,
} from '../../lib/models/preference';
import { mockHomeBase } from '../mock-home-base';
import { MockModule, MockRequest, MockResponse, ok, problem } from '../mock-router';

/**
 * Giả lập tuỳ chọn cá nhân, bộ lọc đã lưu và bố cục trang chủ (CNH-02, CNH-03, CNH-04) theo `config.yaml`. Tuỳ chọn lưu
 * trong bộ nhớ của phiên; `GET /ui/layouts/home` ghép lớp phủ `ui.home.layout` lên bố cục gốc theo
 * đúng thuật toán của máy chủ (`HomeLayoutOverlay`): ghim, rồi `widgetOrder`, rồi phần còn lại;
 * widget bắt buộc không ẩn được; mã lạ bị bỏ qua. Bố cục gốc chép từ UI Pack mặc định 1.0.0.
 */
const BASE_HOME: HomeLayoutNode = {
  component: 'vt.layout.grid',
  props: { columns: { xs: 1, md: 2, xl: 3 } },
  children: [
    { component: 'vt.widget.announcement', props: { i18nKey: 'home.notice.q4' } },
    { component: 'vt.widget.myTasksToday', props: { limit: 7 }, mandatory: true },
    {
      component: 'vt.widget.okrProgress',
      props: { scope: 'unit' },
      visibleWhen: "user.roles.exists(r, r in ['leader', 'manager'])",
    },
    { component: 'vt.widget.pendingApprovals', visibleWhen: "'manager' in user.roles" },
  ],
};

const KEY =
  /^(ui\.display|ui\.home\.layout|ui\.dashboard\.default|ui\.table\.SCR-[0-9]{4}|notify\.channel_pref)$/;

/**
 * Dữ liệu giả lập giữ trong `sessionStorage` (giá trị C1) để tải lại trang vẫn còn, giống máy chủ
 * thật lưu theo người dùng; tab hoặc trình duyệt khác bắt đầu trống như một thiết bị mới.
 */
const PERSIST_KEY = 'vt.mock.mePreferences';

function restore(): { store: [string, UserPreferenceItem][]; filters: SavedFilter[]; seq: number } {
  try {
    const raw = globalThis.sessionStorage?.getItem(PERSIST_KEY);
    if (raw) return JSON.parse(raw);
  } catch {
    /* không đọc được: bắt đầu trống */
  }
  return { store: [], filters: [], seq: 0 };
}

const initial = restore();
let store = new Map<string, UserPreferenceItem>(initial.store);
let filters: SavedFilter[] = initial.filters;
let seq = initial.seq;

function persist(): void {
  try {
    globalThis.sessionStorage?.setItem(
      PERSIST_KEY,
      JSON.stringify({ store: [...store], filters, seq }),
    );
  } catch {
    /* trình duyệt chặn bộ nhớ: chỉ giữ trong trang */
  }
}

const FILTER_LIMIT = 20;
const SCREEN = /^SCR-[0-9]{4}$/;

function filterId(): string {
  seq += 1;
  return `00000000-0000-4000-8000-${String(seq).padStart(12, '0')}`;
}

function fieldError(field: string, message: string): MockResponse {
  return problem(422, 'Dữ liệu không hợp lệ', {
    code: 'COMMON-422-VALIDATION',
    errors: [{ field, message }],
  } as never);
}

/** Kiểm nội dung bộ lọc như máy chủ (tên 1–100 ký tự, không trùng, tối đa 20, một mặc định). */
function checkFilter(
  screen: string,
  body: { name?: unknown; criteria?: unknown },
  selfId: string | null,
): MockResponse | null {
  const name = typeof body.name === 'string' ? body.name.trim() : '';
  if (!name || name.length > 100)
    return fieldError('name', 'Tên bộ lọc phải có từ 1 đến 100 ký tự.');
  if (!body.criteria || typeof body.criteria !== 'object' || Array.isArray(body.criteria)) {
    return fieldError('criteria', 'Điều kiện lọc không đúng định dạng.');
  }
  const same = filters.filter((f) => f.screenCode === screen && f.id !== selfId);
  if (same.some((f) => f.name.toLowerCase() === name.toLowerCase())) {
    return fieldError('name', 'Đã có bộ lọc cùng tên trên danh sách này.');
  }
  if (!selfId && same.length >= FILTER_LIMIT) {
    return fieldError('name', `Mỗi danh sách lưu được tối đa ${FILTER_LIMIT} bộ lọc.`);
  }
  return null;
}

function filterVersion(r: MockRequest, current: SavedFilter): MockResponse | null {
  const h = r.headers.get('If-Match');
  if (!h) return problem(428, 'Thiếu điều kiện phiên bản khi ghi');
  return h.replace(/"/g, '') === String(current.rowVersion)
    ? null
    : problem(409, 'Bộ lọc đã được thay đổi trên thiết bị khác. Vui lòng tải lại.', {
        code: 'CONFIG-409-VERSION-CONFLICT',
      } as never);
}

function saveFilter(f: SavedFilter): SavedFilter {
  if (f.isDefault) {
    filters = filters.map((x) =>
      x.screenCode === f.screenCode && x.id !== f.id && x.isDefault
        ? { ...x, isDefault: false, rowVersion: x.rowVersion + 1 }
        : x,
    );
  }
  filters = [...filters.filter((x) => x.id !== f.id), f];
  persist();
  return f;
}

const widgetId = (n: HomeLayoutNode): string => n.id ?? n.component;

/**
 * Bố cục gốc: UI Pack của tenant khi shell đã đặt (WEB-051), giữ cờ bắt buộc của bố cục mặc định
 * cho widget trùng mã; không thì bố cục mặc định.
 */
function tenantBase(): HomeLayoutNode {
  const pack = mockHomeBase();
  if (!pack) return BASE_HOME;
  const mandatory = new Set((BASE_HOME.children ?? []).filter((c) => c.mandatory).map(widgetId));
  return {
    ...pack,
    children: (pack.children ?? []).map((c) =>
      mandatory.has(widgetId(c)) ? { ...c, mandatory: true } : c,
    ),
  };
}

/** Ghép lớp phủ như máy chủ; xuất ra để kiểm thử dùng chung. */
export function applyHomeOverlay(
  base: HomeLayoutNode,
  overlay: HomeLayoutOverlay | null,
): { layout: HomeLayoutNode; mandatory: string[] } {
  const children = base.children ?? [];
  const byId = new Map(children.map((c) => [widgetId(c), c]));
  const mandatory = children.filter((c) => c.mandatory).map(widgetId);
  if (!overlay) return { layout: structuredClone(base), mandatory };
  const known = (ids?: string[]) => (ids ?? []).filter((id) => byId.has(id));
  const pinned = known(overlay.pinnedWidgets);
  const hidden = new Set(
    known(overlay.hiddenWidgets).filter((id) => !mandatory.includes(id) && !pinned.includes(id)),
  );
  const ordered = new Set<string>(pinned);
  [...(overlay.widgetOrder ?? [])]
    .sort((a, b) => a.order - b.order)
    .forEach((p) => byId.has(p.id) && ordered.add(p.id));
  byId.forEach((_, id) => ordered.add(id));
  const layout = structuredClone(base);
  layout.children = [...ordered]
    .filter((id) => !hidden.has(id))
    .map((id) => structuredClone(byId.get(id)!));
  return { layout, mandatory };
}

function versionCheck(
  r: MockRequest,
  current: UserPreferenceItem | undefined,
): MockResponse | null {
  const h = r.headers.get('If-Match');
  if (!current) {
    return h && h.replace(/"/g, '') !== '0'
      ? problem(409, 'Tuỳ chọn đã được thay đổi trên thiết bị khác. Vui lòng tải lại.', {
          code: 'CONFIG-409-VERSION-CONFLICT',
        } as never)
      : null;
  }
  if (!h) return problem(428, 'Thiếu điều kiện phiên bản khi ghi');
  if (h.replace(/"/g, '') !== String(current.rowVersion)) {
    return problem(409, 'Tuỳ chọn đã được thay đổi trên thiết bị khác. Vui lòng tải lại.', {
      code: 'CONFIG-409-VERSION-CONFLICT',
    } as never);
  }
  return null;
}

const etag = (v: number) => ({ ETag: `"${v}"` });

export const ME_PREFERENCES_MOCK: MockModule = {
  name: 'me-preferences',
  routes: [
    {
      method: 'GET',
      pattern: '/api/v1/config/me/preferences',
      handle: () => ok({ schemaVersion: 1, items: Object.fromEntries(store) }),
    },
    {
      method: 'PUT',
      pattern: '/api/v1/config/me/preferences/:prefKey',
      handle: (r) => {
        const key = r.pathParams['prefKey'];
        const value = (r.body as { value?: unknown } | null)?.value;
        if (!KEY.test(key) || !value || typeof value !== 'object' || Array.isArray(value)) {
          return problem(422, 'Giá trị tuỳ chọn không đúng định dạng cho phép.');
        }
        const current = store.get(key);
        const blocked = versionCheck(r, current);
        if (blocked) return blocked;
        const next: UserPreferenceItem = {
          prefKey: key,
          value: value as Record<string, unknown>,
          schemaVersion: 1,
          updatedAt: new Date().toISOString(),
          rowVersion: (current?.rowVersion ?? 0) + 1,
        };
        store.set(key, next);
        persist();
        return { status: current ? 200 : 201, body: next, headers: etag(next.rowVersion) };
      },
    },
    {
      method: 'DELETE',
      pattern: '/api/v1/config/me/preferences/:prefKey',
      handle: (r) => {
        const key = r.pathParams['prefKey'];
        const current = store.get(key);
        if (!current) return problem(404, 'Không tìm thấy dữ liệu');
        const blocked = versionCheck(r, current);
        if (blocked) return blocked;
        store.delete(key);
        persist();
        return { status: 204 };
      },
    },
    {
      method: 'GET',
      pattern: '/api/v1/config/me/saved-filters',
      handle: (r) => {
        const screen = r.params.get('screenCode');
        const items = filters
          .filter((f) => !screen || f.screenCode === screen)
          .sort((a, b) => a.displayOrder - b.displayOrder);
        return ok({ items });
      },
    },
    {
      method: 'POST',
      pattern: '/api/v1/config/me/saved-filters',
      handle: (r) => {
        const body = (r.body ?? {}) as Partial<SavedFilter>;
        const screen = body.screenCode ?? '';
        if (!SCREEN.test(screen)) return fieldError('screenCode', 'Mã màn hình không hợp lệ.');
        const invalid = checkFilter(screen, body, null);
        if (invalid) return invalid;
        const f = saveFilter({
          id: filterId(),
          screenCode: screen,
          name: String(body.name).trim(),
          criteria: body.criteria as Record<string, unknown>,
          isDefault: body.isDefault === true,
          displayOrder: typeof body.displayOrder === 'number' ? body.displayOrder : 0,
          updatedAt: new Date().toISOString(),
          rowVersion: 1,
        });
        return {
          status: 201,
          body: f,
          headers: { ...etag(1), Location: `/api/v1/config/me/saved-filters/${f.id}` },
        };
      },
    },
    {
      method: 'PUT',
      pattern: '/api/v1/config/me/saved-filters/:id',
      handle: (r) => {
        const current = filters.find((f) => f.id === r.pathParams['id']);
        if (!current) return problem(404, 'Không tìm thấy dữ liệu');
        const blocked = filterVersion(r, current);
        if (blocked) return blocked;
        const body = (r.body ?? {}) as Partial<SavedFilter>;
        const invalid = checkFilter(current.screenCode, body, current.id);
        if (invalid) return invalid;
        const f = saveFilter({
          ...current,
          name: String(body.name).trim(),
          criteria: body.criteria as Record<string, unknown>,
          isDefault: body.isDefault === true,
          displayOrder:
            typeof body.displayOrder === 'number' ? body.displayOrder : current.displayOrder,
          updatedAt: new Date().toISOString(),
          rowVersion: current.rowVersion + 1,
        });
        return ok(f, etag(f.rowVersion));
      },
    },
    {
      method: 'DELETE',
      pattern: '/api/v1/config/me/saved-filters/:id',
      handle: (r) => {
        const current = filters.find((f) => f.id === r.pathParams['id']);
        if (!current) return problem(404, 'Không tìm thấy dữ liệu');
        const blocked = filterVersion(r, current);
        if (blocked) return blocked;
        filters = filters.filter((f) => f.id !== current.id);
        persist();
        return { status: 204 };
      },
    },
    {
      method: 'GET',
      pattern: '/api/v1/ui/layouts/home',
      handle: () => {
        const pref = store.get('ui.home.layout');
        const display = store.get('ui.display')?.value as
          { mode?: string; reducedSteps?: boolean } | undefined;
        const { layout, mandatory } = applyHomeOverlay(
          tenantBase(),
          (pref?.value as HomeLayoutOverlay | undefined) ?? null,
        );
        const body: HomeLayout = {
          uiPackVersion: '1.0.0',
          registryVersion: '2.4.0',
          mode: display?.mode === 'elderly' ? 'elderly' : 'default',
          reducedSteps: display?.mode === 'elderly' && display.reducedSteps === true,
          layout,
          personalizationMeta: {
            hasOverlay: !!pref,
            overlayVersionNo: pref?.rowVersion,
            mandatoryWidgetIds: mandatory,
          },
        };
        return ok(body, { ETag: `"home-${pref?.rowVersion ?? 0}"` });
      },
    },
  ],
  reset: () => {
    store = new Map();
    filters = [];
    seq = 0;
    try {
      globalThis.sessionStorage?.removeItem(PERSIST_KEY);
    } catch {
      /* bỏ qua */
    }
  },
};
