import { MockModule, MockRequest, MockResponse, matchPath, problem } from './mock-router';
import { TASKS_MOCK } from './tasks/tasks.mock';
import { CATALOG_MOCK } from './catalog/catalog.mock';
import { ORG_MOCK } from './org/org.mock';
import { PLANS_MOCK } from './plans/plans.mock';
import { OKR_MOCK } from './okr/okr.mock';
import { SOURCE_DOCS_MOCK } from './source-docs/source-docs.mock';
import { TENANTS_MOCK } from './tenants/tenants.mock';
import { ORG_TREE_MOCK } from './org-tree/org-tree.mock';
import { EVIDENCE_MOCK } from './evidence/evidence.mock';
import { PERIODS_MOCK } from './periods/periods.mock';
import { APPRAISALS_MOCK } from './appraisals/appraisals.mock';
import { DOSSIERS_MOCK } from './dossiers/dossiers.mock';
import { ORG_SYNC_MOCK } from './org-sync/org-sync.mock';
import { ROLE_ADMIN_MOCK } from './role-admin/role-admin.mock';

/**
 * Danh mục fixture theo module API (`/api/v1/<module>`). Thêm module mới: tạo thư mục
 * `mock/<module>/` gồm `<module>.json` và `<module>.mock.ts`, rồi thêm vào mảng này.
 * Tệp này chỉ được nạp lười từ `mockApiInterceptor`, nên không vào gói tải đầu.
 */
export const MOCK_MODULES: MockModule[] = [
  // trước TASKS_MOCK để `/tasks/source-docs` không rơi vào `/tasks/:id`
  SOURCE_DOCS_MOCK,
  TASKS_MOCK,
  CATALOG_MOCK,
  ORG_MOCK,
  PLANS_MOCK,
  OKR_MOCK,
  TENANTS_MOCK,
  ORG_TREE_MOCK,
  EVIDENCE_MOCK,
  PERIODS_MOCK,
  APPRAISALS_MOCK,
  DOSSIERS_MOCK,
  ORG_SYNC_MOCK,
  ROLE_ADMIN_MOCK,
];

const WRITE_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

/** Kết quả đã trả theo `Idempotency-Key` (máy chủ thật lưu 24–48 giờ, xem wiki trang 06). */
const idempotency = new Map<string, { fingerprint: string; res: MockResponse }>();

export function handleMockRequest(req: Omit<MockRequest, 'pathParams'>): MockResponse {
  for (const mod of MOCK_MODULES) {
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
          : problem(422, 'Idempotency-Key đã dùng cho một yêu cầu khác');
      }
      const res = route.handle({ ...req, pathParams });
      if (res.status < 500) idempotency.set(key, { fingerprint, res });
      return res;
    }
  }
  console.warn(`[mock-api] Chưa có fixture cho ${req.method} ${req.path}`);
  return problem(404, `Chưa có dữ liệu giả lập cho ${req.method} ${req.path}`);
}

/** Đưa mọi module về dữ liệu fixture ban đầu (dùng trong kiểm thử). */
export function resetMockData(): void {
  idempotency.clear();
  for (const mod of MOCK_MODULES) mod.reset();
}
