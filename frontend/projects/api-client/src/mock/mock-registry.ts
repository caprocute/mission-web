import { MockModule, MockRequest, MockResponse, matchPath, problem } from './mock-router';
import { mockSession } from './mock-session';

/**
 * Danh mục fixture theo module API (`/api/v1/<module>`). Tệp này chỉ được nạp lười từ
 * `mockApiInterceptor`, nên không vào gói tải đầu. Từ WEB-052 pha 2, mỗi bộ giả lập còn được
 * tách thành chunk riêng và chỉ nạp khi có request tới module API của nó, nên trang chủ không
 * phải tải và phân tích dữ liệu giả lập của OKR, thẩm định hay quản trị.
 *
 * Thêm bộ giả lập mới: tạo thư mục `mock/<module>/` gồm `<module>.json` và `<module>.mock.ts`,
 * thêm một dòng vào MOCK_LOADERS (đúng thứ tự ưu tiên khớp) và khai báo các module API mà nó
 * phục vụ trong MOCK_PREFIXES. `mock-registry.spec.ts` báo lỗi nếu hai bảng lệch với tuyến thật.
 */
export const MOCK_LOADERS = {
  // trước TASKS_MOCK để `/tasks/source-docs` không rơi vào `/tasks/:id`
  sourceDocs: () => import('./source-docs/source-docs.mock').then((m) => m.SOURCE_DOCS_MOCK),
  tasks: () => import('./tasks/tasks.mock').then((m) => m.TASKS_MOCK),
  catalog: () => import('./catalog/catalog.mock').then((m) => m.CATALOG_MOCK),
  org: () => import('./org/org.mock').then((m) => m.ORG_MOCK),
  plans: () => import('./plans/plans.mock').then((m) => m.PLANS_MOCK),
  okr: () => import('./okr/okr.mock').then((m) => m.OKR_MOCK),
  tenants: () => import('./tenants/tenants.mock').then((m) => m.TENANTS_MOCK),
  orgTree: () => import('./org-tree/org-tree.mock').then((m) => m.ORG_TREE_MOCK),
  evidence: () => import('./evidence/evidence.mock').then((m) => m.EVIDENCE_MOCK),
  periods: () => import('./periods/periods.mock').then((m) => m.PERIODS_MOCK),
  appraisals: () => import('./appraisals/appraisals.mock').then((m) => m.APPRAISALS_MOCK),
  dossiers: () => import('./dossiers/dossiers.mock').then((m) => m.DOSSIERS_MOCK),
  orgSync: () => import('./org-sync/org-sync.mock').then((m) => m.ORG_SYNC_MOCK),
  reopen: () => import('./oversight/reopen.mock').then((m) => m.REOPEN_MOCK),
  ledger: () => import('./ledger/ledger.mock').then((m) => m.LEDGER_MOCK),
  complaints: () => import('./oversight/complaints.mock').then((m) => m.COMPLAINTS_MOCK),
  uiForms: () => import('./ui-forms/ui-forms.mock').then((m) => m.UI_FORMS_MOCK),
  uiPacks: () => import('./ui-packs/ui-packs.mock').then((m) => m.UI_PACKS_MOCK),
  workflowTemplates: () =>
    import('./workflow-templates/workflow-templates.mock').then((m) => m.WORKFLOW_TEMPLATES_MOCK),
  workflowInstances: () =>
    import('./workflow-instances/workflow-instances.mock').then((m) => m.WORKFLOW_INSTANCES_MOCK),
  roleAdmin: () => import('./role-admin/role-admin.mock').then((m) => m.ROLE_ADMIN_MOCK),
  alerts: () => import('./oversight/alerts.mock').then((m) => m.ALERTS_MOCK),
  featureFlags: () =>
    import('./feature-flags/feature-flags.mock').then((m) => m.FEATURE_FLAGS_MOCK),
  policies: () => import('./policies/policies.mock').then((m) => m.POLICIES_MOCK),
  analytics: () => import('./analytics/analytics.mock').then((m) => m.ANALYTICS_MOCK),
  mePreferences: () =>
    import('./me-preferences/me-preferences.mock').then((m) => m.ME_PREFERENCES_MOCK),
} satisfies Record<string, () => Promise<MockModule>>;

export type MockLoaderKey = keyof typeof MOCK_LOADERS;

/** Thứ tự ưu tiên khớp tuyến giữa các bộ giả lập (thứ tự khai báo của MOCK_LOADERS). */
export const MOCK_ORDER = Object.keys(MOCK_LOADERS) as MockLoaderKey[];

/** Module API (đoạn ngay sau `/api/v1/`) → các bộ giả lập có tuyến bắt đầu bằng đoạn đó. */
export const MOCK_PREFIXES: Record<string, MockLoaderKey[]> = {
  ai: ['sourceDocs', 'okr', 'analytics'],
  analytics: ['analytics'],
  appraisals: ['evidence', 'appraisals', 'complaints'],
  catalog: ['catalog'],
  config: ['tenants', 'roleAdmin', 'alerts', 'featureFlags', 'mePreferences'],
  dossiers: ['dossiers'],
  esign: ['plans', 'dossiers'],
  evidences: ['evidence'],
  files: ['evidence'],
  jobs: ['orgSync'],
  ledger: ['ledger'],
  okr: ['okr'],
  org: ['org', 'orgTree', 'orgSync'],
  oversight: ['reopen', 'complaints', 'alerts'],
  periods: ['periods'],
  policies: ['policies'],
  plans: ['plans'],
  products: ['evidence'],
  registry: ['tenants'],
  tasks: ['sourceDocs', 'tasks', 'analytics'],
  ui: ['uiForms', 'uiPacks', 'mePreferences'],
  workflow: ['workflowTemplates', 'workflowInstances'],
};

/** Đoạn module của đường dẫn `/api/v1/<module>/...`. */
export function mockPrefixOf(path: string): string {
  return path.split('/')[3] ?? '';
}

const loaded = new Map<MockLoaderKey, MockModule>();
const pending = new Map<MockLoaderKey, Promise<MockModule>>();

function load(key: MockLoaderKey): Promise<MockModule> {
  const done = loaded.get(key);
  if (done) return Promise.resolve(done);
  let p = pending.get(key);
  if (!p) {
    p = MOCK_LOADERS[key]().then((mod) => {
      loaded.set(key, mod);
      pending.delete(key);
      return mod;
    });
    pending.set(key, p);
  }
  return p;
}

const WRITE_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

/** Kết quả đã trả theo `Idempotency-Key` (máy chủ thật lưu 24–48 giờ, xem wiki trang 06). */
const idempotency = new Map<string, { fingerprint: string; res: MockResponse }>();

export async function handleMockRequest(
  req: Omit<MockRequest, 'pathParams'>,
): Promise<MockResponse> {
  const keys = MOCK_PREFIXES[mockPrefixOf(req.path)] ?? [];
  const mods = await Promise.all(keys.map(load));
  for (const mod of mods) {
    for (const route of mod.routes) {
      if (route.method !== req.method) continue;
      const pathParams = matchPath(route.pattern, req.path);
      if (!pathParams) continue;
      const key = WRITE_METHODS.has(req.method) ? req.headers.get('Idempotency-Key') : null;
      if (!key) return route.handle({ ...req, pathParams });
      const fingerprint = `${req.method} ${req.path} ${JSON.stringify(req.body ?? null)}`;
      const prev = idempotency.get(key);
      if (prev) {
        return prev.fingerprint === fingerprint
          ? prev.res
          : problem(
              422,
              'Yêu cầu này đã được gửi trước đó với nội dung khác, vui lòng tải lại trang',
            );
      }
      const res = route.handle({ ...req, pathParams });
      if (res.status < 500) idempotency.set(key, { fingerprint, res });
      return res;
    }
  }
  console.warn(`[mock-api] Chưa có fixture cho ${req.method} ${req.path}`);
  return problem(404, 'Không tìm thấy dữ liệu');
}

/**
 * Đưa mọi bộ giả lập về dữ liệu fixture ban đầu (dùng trong kiểm thử). Bộ chưa nạp vẫn ở trạng
 * thái ban đầu, nên chỉ cần đặt lại các bộ đã nạp.
 */
export function resetMockData(): void {
  idempotency.clear();
  // Phiên giả lập (MFA, ký số) dùng chung: đặt lại kể cả khi chưa nạp bộ giả lập nào (nạp lười)
  mockSession.reset();
  for (const mod of loaded.values()) mod.reset();
}
