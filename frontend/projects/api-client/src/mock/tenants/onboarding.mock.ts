import { Job } from '../../lib/models/job';
import { CELL_LOAD_THRESHOLD, TENANCY_MODEL_LABEL, TenantStatus } from '../../lib/models/tenant';
import {
  CellOption,
  OnboardingCheck,
  PROVISION_STEPS,
  PolicyPackOption,
  TenantFlow,
  TenantIdp,
  TenantOnboarding,
  TenantOnboardingInput,
  TenantOnboardingPatch,
  modelAllowed,
  suggestTenancyModel,
} from '../../lib/models/tenant-onboarding';
import { MockRequest, MockResponse, MockRoute, ok, problem } from '../mock-router';
import { addMockJobSource } from '../mock-jobs';
import { OnboardingExtra, StoredTenant, load, nextOnboardingCode } from './tenant-store';
import fixture from './tenants.json';

/**
 * Giả lập trình khởi tạo tenant SCR-0102 (QT-01 bước 1–9) của SVC-03 tenant-registry: khai báo,
 * lưu nháp có `If-Match`, gợi ý mô hình và cell (BR-01-01, BR-01-02, MSG-01-03), kiểm tên miền
 * (MSG-01-04), Policy Pack (BR-01-03, BR-01-04, MSG-01-05), saga cấp phát 202 có bước con
 * (MSG-01-06; tên miền chứa `loi-tls` làm saga lỗi ở bước cấp chứng chỉ để thử MSG-01-07),
 * kiểm tra kết nối IdP, kiểm tra tổng thể và kích hoạt (BR-01-10).
 */

/** Thời gian chạy giả lập của saga cấp phát (ms); kiểm thử rút ngắn bằng `setProvisionDuration`. */
let provisionMs = 8_000;
export function setProvisionDuration(ms: number): void {
  provisionMs = ms;
}

const PACKS = fixture.policyPacks as PolicyPackOption[];
const packLabel = (id: string) => {
  const p = PACKS.find((x) => x.id === id);
  return p ? `${p.name} ${p.version}` : id;
};
const uiLabel = (id: string) => {
  const u = fixture.uiPacks.find((x) => x.id === id);
  return u ? `${u.name} ${u.version}` : id;
};

const MSG_01_04 = (domain: string) => `Tên miền ${domain} đã được sử dụng.`;
const MSG_01_05 = (pack: string) =>
  `Bộ quy định đánh giá ${pack} chưa đạt 100% bộ ca kiểm thử chuẩn nên chưa gắn được.`;
const MSG_01_07 = (step: string, reason: string) =>
  `Cấp phát lỗi ở bước ${step}: ${reason}. Hệ thống đã hoàn tác các bước trước.`;

function find(r: MockRequest): StoredTenant | undefined {
  return load().find((t) => t.id === r.pathParams['id'] && t.onboarding);
}

function view(t: StoredTenant): TenantOnboarding {
  const o = t.onboarding!;
  const { provision, highIssues: _h, ...rest } = o;
  void _h;
  return {
    ...rest,
    id: t.id,
    name: t.name,
    code: t.code,
    sector: t.sector,
    contractRef: t.contractRef ?? '',
    flows: t.flows,
    status: t.status,
    step: t.onboardingStep ?? 6,
    tenancyModel: t.tenancyModel,
    cellCode: t.cellCode || undefined,
    domain: t.domain || undefined,
    dataRegion: t.dataRegion,
    provisionLocation: provision ? `/api/v1/jobs/${provision.jobId}` : undefined,
    rowVersion: t.rowVersion,
  };
}

const ifMatch = (r: MockRequest) => r.headers.get('If-Match')?.replace(/"/g, '');
const conflict = () =>
  problem(409, 'Hồ sơ khởi tạo đã được người khác thay đổi. Vui lòng tải lại.', {
    code: 'TENANT-409-CONFLICT',
  });
const fieldError = (status: number, title: string, field: string, message = title) =>
  problem(status, title, { errors: [{ field, message }] } as never);

function log(t: StoredTenant, to: TenantStatus, reason: string) {
  t.statusLog = [{ at: new Date().toISOString(), from: t.status, to, reason }, ...t.statusLog];
  t.status = to;
}

function domainTaken(domain: string, except?: string): boolean {
  const d = domain.trim().toLowerCase();
  return load().some((t) => t.id !== except && t.domains.some((x) => x.domain === d));
}

function cellOptions(model: string, accounts: number): CellOption[] {
  const all = load();
  return fixture.cells
    .filter((c) => c.tenancyModel === model)
    .map((c) => {
      const mine = all.filter((t) => t.cellCode === c.code && t.status !== 'DA_CHAM_DUT');
      const accountCount = mine.reduce((s, t) => s + t.accountCount, 0);
      const loadAfter = (accountCount + accounts) / c.capacityAccounts;
      // cell silo dành riêng cho một tenant: đã có tenant thì không thêm được
      const dedicatedTaken = model === 'silo' && mine.length > 0;
      return {
        ...(c as Omit<CellOption, 'accountCount' | 'tenantCount' | 'loadAfter' | 'eligible'>),
        accountCount,
        tenantCount: mine.length,
        loadAfter,
        eligible: c.status === 'HOAT_DONG' && !dedicatedTaken && loadAfter < CELL_LOAD_THRESHOLD,
      };
    })
    .sort((a, b) => a.loadAfter - b.loadAfter);
}

function create(r: MockRequest): MockResponse {
  const b = r.body as TenantOnboardingInput;
  if (!b?.name?.trim()) return fieldError(422, 'Đồng chí chưa nhập tên đơn vị triển khai.', 'name');
  if (!/^[a-z0-9_]{2,32}$/.test(b.code ?? '')) {
    return fieldError(
      422,
      'Mã đơn vị chỉ gồm chữ thường không dấu, chữ số và dấu gạch dưới.',
      'code',
    );
  }
  if (load().some((t) => t.code === b.code)) {
    return fieldError(409, `Mã đơn vị ${b.code} đã được sử dụng.`, 'code');
  }
  if (!b.contractRef?.trim() || b.contractRef.length > 50) {
    return fieldError(422, 'Số hợp đồng là bắt buộc, tối đa 50 ký tự.', 'contractRef');
  }
  if (!b.flows?.length)
    return fieldError(422, 'Đồng chí cần chọn ít nhất một luồng đánh giá áp dụng.', 'flows');
  if (!(b.expectedAccounts > 0))
    return fieldError(422, 'Số tài khoản dự kiến phải lớn hơn 0.', 'expectedAccounts');
  const id = `t-${b.code}`;
  const domain = `${b.code.replace(/_/g, '-')}.okr.viettel.vn`;
  const model = suggestTenancyModel(b.expectedAccounts, b.isolationRequired);
  const extra: OnboardingExtra = {
    onboardingCode: nextOnboardingCode(),
    contractSignedOn: b.contractSignedOn,
    expectedAccounts: b.expectedAccounts,
    expectedUnits: b.expectedUnits,
    isolationRequired: b.isolationRequired,
    adminName: b.adminName,
    adminEmail: b.adminEmail,
    startedAt: new Date().toISOString(),
    kekMode: model === 'pool' ? 'DUNG_CHUNG' : 'RIENG',
    policyPacks: {},
    uiPackId: 'ui-mac-dinh-3.0',
    idps: [],
    hrSources: [],
    highIssues: 0,
  };
  const t: StoredTenant = {
    id,
    code: b.code,
    name: b.name.trim(),
    domain,
    cellCode: '',
    tenancyModel: model,
    accountCount: 0,
    policyPacks: [],
    uiPack: 'Mặc định v3.0',
    status: 'KHOI_TAO',
    onboardingStep: 1,
    rowVersion: 0,
    sector: b.sector,
    flows: b.flows,
    contractRef: b.contractRef.trim(),
    maxAccounts: b.expectedAccounts,
    expectedAccounts: b.expectedAccounts,
    dataRegion: 'HOALAC',
    domains: [{ domain, kind: 'PRIMARY' }],
    statusLog: [
      { at: new Date().toISOString(), to: 'KHOI_TAO', reason: 'Khai báo đơn vị triển khai' },
    ],
    onboarding: extra,
  };
  load().unshift(t);
  return { status: 201, body: view(t), headers: { Location: `/api/v1/config/tenants/${id}` } };
}

/** Trường của bước 1–3 chỉ sửa được khi chưa cấp phát. */
const SETUP_FIELDS: (keyof TenantOnboardingPatch)[] = [
  'name',
  'contractRef',
  'contractSignedOn',
  'flows',
  'expectedAccounts',
  'expectedUnits',
  'isolationRequired',
  'tenancyModel',
  'cellCode',
  'domain',
  'dataRegion',
  'kekMode',
  'policyPacks',
  'uiPackId',
];

function patch(r: MockRequest): MockResponse {
  const t = find(r);
  if (!t) return problem(404, 'Không tìm thấy hồ sơ khởi tạo');
  if (ifMatch(r) !== String(t.rowVersion)) return conflict();
  const p = (r.body ?? {}) as TenantOnboardingPatch;
  const o = t.onboarding!;
  const touchesSetup = SETUP_FIELDS.some((k) => k in p);
  if (touchesSetup && t.status !== 'KHOI_TAO') {
    return problem(
      409,
      'Đơn vị đã được cấp phát hạ tầng, không sửa được thông tin từ bước 1 đến bước 3.',
      {
        code: 'TENANT-409-STATE',
      },
    );
  }
  const accounts = p.expectedAccounts ?? o.expectedAccounts;
  const isolation = p.isolationRequired ?? o.isolationRequired;
  const model = p.tenancyModel ?? t.tenancyModel;
  if (p.tenancyModel && !modelAllowed(p.tenancyModel, accounts, isolation)) {
    return fieldError(
      422,
      // BR-01-01
      'Đơn vị trên 1.000.000 tài khoản hoặc thuộc diện cách ly phải dùng mức cô lập Cụm riêng.',
      'tenancyModel',
    );
  }
  if (p.cellCode) {
    const opt = cellOptions(model, accounts).find((c) => c.code === p.cellCode);
    if (!opt || !opt.eligible) {
      return fieldError(
        422,
        // BR-01-02
        `Cụm máy chủ ${p.cellCode} vượt ngưỡng 85% dung lượng sau khi thêm đơn vị hoặc đang không sử dụng được.`,
        'cellCode',
      );
    }
  }
  if (p.domain !== undefined) {
    const d = p.domain.trim().toLowerCase();
    if (!/^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$/.test(d)) {
      return fieldError(422, 'Tên miền không hợp lệ.', 'domain');
    }
    if (domainTaken(d, t.id)) return fieldError(422, MSG_01_04(d), 'domain');
  }
  if (model === 'silo' && (p.kekMode ?? o.kekMode) === 'DUNG_CHUNG') {
    return fieldError(
      422,
      'Mức cô lập Cụm riêng bắt buộc dùng khoá mã hoá riêng của đơn vị.',
      'kekMode',
    );
  }
  if (p.policyPacks) {
    for (const id of Object.values(p.policyPacks)) {
      const pack = PACKS.find((x) => x.id === id);
      if (!pack) return fieldError(422, 'Bộ quy định đánh giá không tồn tại.', 'policyPacks');
      if (pack.goldenPassRate < 100) {
        return fieldError(422, MSG_01_05(`${pack.name} ${pack.version}`), 'policyPacks');
      }
    }
  }
  // ghi
  if (p.name) t.name = p.name.trim();
  if (p.contractRef !== undefined) t.contractRef = p.contractRef;
  if (p.flows) t.flows = p.flows;
  if (p.tenancyModel) t.tenancyModel = p.tenancyModel;
  if (p.cellCode !== undefined) t.cellCode = p.cellCode;
  if (p.dataRegion) t.dataRegion = p.dataRegion;
  if (p.expectedAccounts) t.maxAccounts = t.expectedAccounts = p.expectedAccounts;
  if (p.domain !== undefined) {
    t.domain = p.domain.trim().toLowerCase();
    t.domains = [{ domain: t.domain, kind: 'PRIMARY' }];
  }
  if (p.policyPacks) t.policyPacks = Object.values(p.policyPacks).map(packLabel);
  if (p.uiPackId) t.uiPack = uiLabel(p.uiPackId);
  const keep = [
    'contractSignedOn',
    'expectedAccounts',
    'expectedUnits',
    'isolationRequired',
    'adminName',
    'adminEmail',
    'kekMode',
    'policyPacks',
    'uiPackId',
    'idps',
    'hrSources',
  ] as const;
  for (const k of keep) {
    if (p[k] !== undefined) (o as unknown as Record<string, unknown>)[k] = p[k];
  }
  if (p.step && p.step > (t.onboardingStep ?? 1)) t.onboardingStep = Math.min(6, p.step);
  autoReady(t);
  t.rowVersion++;
  return ok(view(t));
}

function remove(r: MockRequest): MockResponse {
  const t = find(r);
  if (!t) return problem(404, 'Không tìm thấy hồ sơ khởi tạo');
  if (ifMatch(r) !== String(t.rowVersion)) return conflict();
  if (t.status !== 'KHOI_TAO') {
    return problem(409, 'Chỉ huỷ được yêu cầu đang ở trạng thái Khởi tạo, chưa cấp phát hạ tầng.', {
      code: 'TENANT-409-STATE',
    });
  }
  const all = load();
  all.splice(all.indexOf(t), 1);
  return { status: 204 };
}

function provision(r: MockRequest): MockResponse {
  const t = find(r);
  if (!t) return problem(404, 'Không tìm thấy hồ sơ khởi tạo');
  const o = t.onboarding!;
  // hợp đồng: provision bắt buộc `If-Match` (428 khi thiếu, 409 khi lệch), 202 + `/api/v1/jobs/{id}`
  const tag = ifMatch(r);
  if (tag === undefined) return problem(428, 'Thiếu phiên bản bản ghi (If-Match).');
  const location = (jobId: string) => ({
    status: 202,
    body: { id: jobId, type: 'TENANT_PROVISION', status: 'CHO', progress: 0 } satisfies Job,
    headers: { Location: `/api/v1/jobs/${jobId}` },
  });
  if (t.status === 'DANG_CAP_PHAT' && o.provision) return location(o.provision.jobId);
  if (tag !== String(t.rowVersion)) return conflict();
  if (t.status !== 'KHOI_TAO')
    return problem(409, 'Đơn vị đã được cấp phát hạ tầng.', { code: 'TENANT-409-STATE' });
  if (!t.cellCode || !t.domain)
    return problem(422, 'Đồng chí chưa chọn cụm máy chủ và tên miền ở bước 2.');
  const missing = t.flows.find((f) => !o.policyPacks[f]);
  if (missing) {
    return fieldError(
      422,
      // BR-01-04
      `Chưa gắn bộ quy định đánh giá cho luồng ${missing === 'DANG' ? 'Đảng' : 'Nhà nước'}.`,
      'policyPacks',
    );
  }
  o.provision = {
    jobId: crypto.randomUUID(),
    startedAt: Date.now(),
    failAt: t.domain.includes('loi-tls') ? PROVISION_STEPS.indexOf('Cấp chứng chỉ TLS') : undefined,
  };
  delete o.provisionError;
  t.onboardingStep = 3;
  log(
    t,
    'DANG_CAP_PHAT',
    `Xác nhận cụm máy chủ ${t.cellCode}, mức cô lập ${TENANCY_MODEL_LABEL[t.tenancyModel]}`,
  );
  t.rowVersion++;
  provisionJobs.set(o.provision.jobId, t.id);
  return location(o.provision.jobId);
}

/** `GET /api/v1/jobs/{id}` của saga cấp phát: tìm tenant đang hoặc đã chạy job này. */
const provisionJobs = new Map<string, string>();

function provisionJobById(jobId: string): MockResponse | null {
  const tenantId = provisionJobs.get(jobId);
  const t =
    load().find((x) => x.onboarding?.provision?.jobId === jobId) ??
    (tenantId ? load().find((x) => x.id === tenantId && x.onboarding) : undefined);
  return t ? provisionJob(t, jobId) : null;
}
addMockJobSource(provisionJobById);

function provisionJob(t: StoredTenant, jobId: string): MockResponse {
  const o = t.onboarding!;
  const base = { id: jobId, type: 'TENANT_PROVISION' };
  if (!o.provision || o.provision.jobId !== jobId) {
    // tác vụ đã kết thúc: trả kết quả cuối theo trạng thái tenant
    if (o.provisionError) {
      return ok({
        ...base,
        status: 'THAT_BAI',
        problem: {
          type: 'about:blank',
          status: 500,
          title: MSG_01_07(o.provisionError.step, o.provisionError.reason),
        },
      } satisfies Job);
    }
    return ok({ ...base, status: 'HOAN_TAT', progress: 100 } satisfies Job);
  }
  const ratio = Math.min(1, (Date.now() - o.provision.startedAt) / provisionMs);
  const idx = Math.min(PROVISION_STEPS.length - 1, Math.floor(ratio * PROVISION_STEPS.length));
  const failAt = o.provision.failAt;
  if (failAt !== undefined && idx >= failAt) {
    const step = PROVISION_STEPS[failAt];
    const reason = 'máy chủ cấp chứng chỉ không xác minh được tên miền sau 3 lần thử';
    o.provision = undefined;
    o.provisionError = { step, reason };
    t.onboardingStep = 3;
    log(t, 'KHOI_TAO', `Cấp phát lỗi ở bước ${step}, đã hoàn tác`);
    t.rowVersion++;
    return ok({
      ...base,
      status: 'THAT_BAI',
      message: step,
      problem: { type: 'about:blank', status: 500, title: MSG_01_07(step, reason) },
    } satisfies Job);
  }
  if (ratio >= 1) {
    o.provision = undefined;
    o.provisionedMinutes = t.tenancyModel === 'pool' ? 4 : t.tenancyModel === 'bridge' ? 74 : 2880;
    o.idps = o.idps.length
      ? o.idps
      : [{ kind: 'KEYCLOAK_ORG', name: `Không gian đăng nhập "${t.code}"`, status: 'DAT' }];
    t.onboardingStep = 4;
    t.domains = t.domains.map((d) => ({ ...d, verifiedAt: new Date().toISOString() }));
    log(t, 'DANG_CAU_HINH', 'Hoàn tất cấp phát hạ tầng');
    t.rowVersion++;
    return ok({
      ...base,
      status: 'HOAN_TAT',
      progress: 100,
      message: 'Cấp phát hoàn tất',
    } satisfies Job);
  }
  return ok({
    ...base,
    status: 'DANG_CHAY',
    progress: Math.round(ratio * 100),
    message: PROVISION_STEPS[idx],
  } satisfies Job);
}

function testIdp(r: MockRequest): MockResponse {
  const t = find(r);
  if (!t) return problem(404, 'Không tìm thấy hồ sơ khởi tạo');
  const idps = ((r.body as { idps?: TenantIdp[] })?.idps ?? []).map((i): TenantIdp => {
    if (i.kind === 'KEYCLOAK_ORG' || i.kind === 'VNEID')
      return { ...i, status: 'DAT', message: undefined };
    const url = i.url?.trim() ?? '';
    const scheme = i.kind === 'LDAP' ? /^ldaps:\/\//.test(url) : /^https:\/\//.test(url);
    if (!scheme) {
      return {
        ...i,
        status: 'LOI',
        message:
          i.kind === 'LDAP'
            ? 'Địa chỉ phải bắt đầu bằng ldaps:// (kết nối có mã hoá).'
            : 'Địa chỉ phải bắt đầu bằng https://.',
      };
    }
    if (url.includes('loi')) {
      return { ...i, status: 'LOI', message: 'Không kết nối được: quá thời gian chờ 10 giây.' };
    }
    return {
      ...i,
      status: 'DAT',
      message:
        i.kind === 'LDAP' ? 'Kết nối thành công.' : 'Đọc được cấu hình và khoá xác thực đăng nhập.',
    };
  });
  t.onboarding!.idps = idps;
  autoReady(t);
  t.rowVersion++;
  return ok({ items: idps });
}

function checks(t: StoredTenant): OnboardingCheck[] {
  const o = t.onboarding!;
  const cell = cellOptions(t.tenancyModel, 0).find((c) => c.code === t.cellCode);
  const flowsOk = t.flows.every((f: TenantFlow) => {
    const pack = PACKS.find((p) => p.id === o.policyPacks[f]);
    return pack && pack.goldenPassRate === 100;
  });
  const idpOk = o.idps.length > 0 && o.idps.every((i) => i.status === 'DAT');
  const provisioned = !['KHOI_TAO', 'DANG_CAP_PHAT'].includes(t.status);
  return [
    {
      code: 'DUNG_LUONG',
      label: 'Dung lượng cụm máy chủ dưới 85%',
      result: cell && cell.loadAfter < CELL_LOAD_THRESHOLD ? 'DAT' : 'LOI',
      detail: cell
        ? `${t.cellCode} đang dùng ${Math.round(cell.loadAfter * 100)}%`
        : 'Chưa chọn cụm máy chủ',
    },
    {
      code: 'TEN_MIEN',
      label: 'Tên miền và chứng chỉ TLS',
      result: provisioned && t.domains[0]?.verifiedAt ? 'DAT' : 'LOI',
      detail: provisioned ? `${t.domain} đã xác minh, chứng chỉ cấp tự động` : 'Chưa cấp phát',
    },
    {
      code: 'POLICY_PACK',
      label: 'Bộ quy định đánh giá đã phát hành, đạt 100% bộ ca kiểm thử chuẩn',
      result: flowsOk ? 'DAT' : 'LOI',
    },
    {
      code: 'IDP',
      label: 'Hệ thống đăng nhập hoạt động',
      result: idpOk ? 'DAT' : 'LOI',
      detail: idpOk ? undefined : 'Còn hệ thống đăng nhập chưa kiểm tra kết nối hoặc kết nối lỗi',
    },
    {
      code: 'LECH_CAO',
      // BR-01-10
      label: 'Không còn lệch nhân sự mức Cao',
      result: o.highIssues === 0 ? 'DAT' : 'LOI',
      detail: o.highIssues
        ? `Còn ${o.highIssues.toLocaleString('vi-VN')} mục lệch mức Cao cần đối soát ở màn hình Đồng bộ và đối soát nhân sự`
        : undefined,
    },
    {
      code: 'BAN_SAO',
      label: 'Bản sao dự phòng',
      result: 'CANH_BAO',
      detail: 'Bản sao dự phòng đang trễ 6 phút (ngưỡng cho phép 15 phút)',
    },
  ];
}

/** `DANG_CAU_HINH` → `SAN_SANG` khi đủ IdP, cây tổ chức và không còn lệch mức Cao (tự động kiểm tra). */
function autoReady(t: StoredTenant) {
  if (t.status !== 'DANG_CAU_HINH' || (t.onboardingStep ?? 0) < 6) return;
  if (checks(t).every((c) => c.result !== 'LOI')) {
    log(t, 'SAN_SANG', 'Đủ điều kiện kích hoạt');
    t.rowVersion++;
  }
}

function activate(r: MockRequest): MockResponse {
  const t = find(r);
  if (!t) return problem(404, 'Không tìm thấy hồ sơ khởi tạo');
  if (ifMatch(r) !== String(t.rowVersion)) return conflict();
  autoReady(t);
  const failed = checks(t).filter((c) => c.result === 'LOI');
  if (t.status !== 'SAN_SANG' || failed.length) {
    return problem(
      409,
      `Chưa kích hoạt được: ${failed.map((c) => c.label).join('; ') || 'đơn vị chưa sẵn sàng'}.`,
      {
        code: 'TENANT-409-NOT-READY',
      },
    );
  }
  log(t, 'DANG_VAN_HANH', 'Kích hoạt, đã gửi thư cho đầu mối quản trị');
  t.onboardingStep = undefined;
  t.rowVersion++;
  return ok(view(t));
}

export const ONBOARDING_ROUTES: MockRoute[] = [
  { method: 'POST', pattern: '/api/v1/config/tenants', handle: create },
  {
    method: 'GET',
    pattern: '/api/v1/config/tenants/cell-options',
    handle: (r) =>
      ok({
        items: cellOptions(
          r.params.get('model') ?? 'bridge',
          Number(r.params.get('accounts') ?? 0),
        ),
      }),
  },
  {
    method: 'GET',
    pattern: '/api/v1/config/tenants/domain-check',
    handle: (r) =>
      ok({
        available: !domainTaken(r.params.get('domain') ?? '', r.params.get('tenant') ?? undefined),
      }),
  },
  {
    method: 'GET',
    pattern: '/api/v1/config/policy-packs',
    handle: (r) =>
      ok({ items: PACKS.filter((p) => !r.params.get('flow') || p.flow === r.params.get('flow')) }),
  },
  {
    method: 'GET',
    pattern: '/api/v1/config/ui-packs',
    handle: () => ok({ items: fixture.uiPacks }),
  },
  {
    method: 'GET',
    pattern: '/api/v1/config/tenants/:id/onboarding',
    handle: (r) => {
      const t = find(r);
      return t ? ok(view(t)) : problem(404, 'Không tìm thấy hồ sơ khởi tạo');
    },
  },
  { method: 'PATCH', pattern: '/api/v1/config/tenants/:id', handle: patch },
  { method: 'DELETE', pattern: '/api/v1/config/tenants/:id', handle: remove },
  { method: 'POST', pattern: '/api/v1/config/tenants/:id/provision', handle: provision },
  { method: 'POST', pattern: '/api/v1/config/tenants/:id/idp/test', handle: testIdp },
  {
    method: 'GET',
    pattern: '/api/v1/config/tenants/:id/checks',
    handle: (r) => {
      const t = find(r);
      if (!t) return problem(404, 'Không tìm thấy hồ sơ khởi tạo');
      autoReady(t);
      return ok({ items: checks(t) });
    },
  },
  { method: 'POST', pattern: '/api/v1/config/tenants/:id/activate', handle: activate },
];
