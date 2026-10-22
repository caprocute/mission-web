import { HomeLayout, HomeLayoutNode, HomeLayoutOverlay, UserPreferenceItem } from '../../lib/models/preference';
import { MockModule, MockRequest, MockResponse, ok, problem } from '../mock-router';

/**
 * Giả lập tuỳ chọn cá nhân và bố cục trang chủ (CNH-02, CNH-03) theo `config.yaml`. Tuỳ chọn lưu
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

const KEY = /^(ui\.display|ui\.home\.layout|ui\.dashboard\.default|ui\.table\.SCR-[0-9]{4}|notify\.channel_pref)$/;

let store = new Map<string, UserPreferenceItem>();

const widgetId = (n: HomeLayoutNode): string => n.id ?? n.component;

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
  layout.children = [...ordered].filter((id) => !hidden.has(id)).map((id) => structuredClone(byId.get(id)!));
  return { layout, mandatory };
}

function versionCheck(r: MockRequest, current: UserPreferenceItem | undefined): MockResponse | null {
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
        return { status: 204 };
      },
    },
    {
      method: 'GET',
      pattern: '/api/v1/ui/layouts/home',
      handle: () => {
        const pref = store.get('ui.home.layout');
        const display = store.get('ui.display')?.value as { mode?: string; reducedSteps?: boolean } | undefined;
        const { layout, mandatory } = applyHomeOverlay(
          BASE_HOME,
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
  },
};
