import { Job } from '../../lib/models/job';
import {
  CELL_LOAD_THRESHOLD,
  ConfigTenant,
  TENANCY_MODEL_LABEL,
  TenancyModel,
  TenantStatus,
} from '../../lib/models/tenant';
import {
  ISOLATED_SECTORS,
  PROVISION_STEPS,
  ReadinessCheck,
  RegistryCell,
  TenantIdp,
  TenantIdpTestResult,
  TenantOnboardingInput,
  TenantOnboardingPatch,
  TenantSector,
  modelAllowed,
  registryModel,
  suggestTenancyModel,
} from '../../lib/models/tenant-onboarding';
import { MockRequest, MockResponse, MockRoute, ok, problem } from '../mock-router';
import { addMockJobSource } from '../mock-jobs';
import {
  OnboardingExtra,
  PACK_CATALOG,
  StoredTenant,
  load,
  nextOnboardingCode,
  packLabel,
  tenantDetail,
} from './tenant-store';
import fixture from './tenants.json';

/**
 * Giả lập trình khởi tạo tenant SCR-0102 (QT-01 bước 1–9) của SVC-03 tenant-registry theo
 * `config.yaml` (`/config/tenants/**`) và `platform.yaml` (`/registry/cells`): khai báo
 * `ConfigTenantCreate`, lưu nháp merge-patch có `If-Match` (trường lạ trả 422 như DecodeStrict của
 * máy chủ), gợi ý mô hình và cell (BR-01-01, BR-01-02, MSG-01-03), tên miền trùng 409 (MSG-01-04),
 * Policy Pack (BR-01-03, BR-01-04, MSG-01-05), saga cấp phát 202 job `PROVISION` có bước con
 * (MSG-01-06; tên miền chứa `loi-tls` làm saga lỗi ở bước cấp chứng chỉ để thử MSG-01-07), kiểm tra
 * kết nối một IdP theo `idpAlias`, kiểm tra tổng thể `readinessChecks` và kích hoạt (BR-01-10).
 * Bước wizard `onboardingStep` do máy chủ tính từ dữ liệu sau mỗi lần ghi (như `Step` của SVC-03).
 */

/** Thời gian chạy giả lập của saga cấp phát (ms); kiểm thử rút ngắn bằng `setProvisionDuration`. */
let provisionMs = 8_000;
export function setProvisionDuration(ms: number): void {
  provisionMs = ms;
}

const DEFAULT_UI_PACK = fixture.uiPacks[0];
const uiLabel = (id: string) => {
  const u = fixture.uiPacks.find((x) => x.id === id);
  return u ? `${u.name} v${u.version}` : 'Bộ giao diện của đơn vị';
};

const MSG_01_04 = (domain: string) => `Tên miền ${domain} đã được sử dụng.`;
const MSG_01_05 = (pack: string) =>
  `Bộ quy định đánh giá ${pack} chưa đạt 100% bộ ca kiểm thử chuẩn nên chưa gắn được.`;
const MSG_01_07 = (step: string, reason: string) =>
  `Cấp phát lỗi ở bước ${step}: ${reason}. Hệ thống đã hoàn tác các bước trước.`;

const DOMAIN_RE = /^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$/;
const SECTORS: readonly TenantSector[] = [
  'DANG',
  'CHINH_QUYEN',
  'SU_NGHIEP',
  'TRUONG_HOC',
  'BENH_VIEN',
  'LLVT',
  'DOANH_NGHIEP',
];

/** Trường của `ConfigTenantCreate` và `ConfigTenantPatch` (máy chủ từ chối trường khác). */
const CREATE_KEYS = [
  'code',
  'name',
  'sector',
  'contractRef',
  'contractValidTo',
  'flows',
  'expectedAccounts',
  'expectedUnits',
  'adminContact',
];
const PATCH_KEYS = [
  'name',
  'contractRef',
  'contractValidTo',
  'flows',
  'expectedAccounts',
  'expectedUnits',
  'tenancyModel',
  'cellCode',
  'domain',
  'dataRegion',
  'kekMode',
  'policyBindings',
  'uiPackVersionId',
  'idps',
  'staffSources',
  'adminContact',
];
const IDP_KEYS = [
  'id',
  'idpAlias',
  'protocol',
  'issuer',
  'isVneid',
  'emailDomains',
  'status',
  'lastTestedAt',
  'lastTestOk',
];
/** Trường của bước 1–3 chỉ sửa được khi chưa cấp phát. */
const SETUP_KEYS = PATCH_KEYS.filter((k) => !['idps', 'staffSources'].includes(k));

function find(r: MockRequest): StoredTenant | undefined {
  return load().find((t) => t.id === r.pathParams['id'] && t.onboarding);
}

const ifMatch = (r: MockRequest) => r.headers.get('If-Match')?.replace(/"/g, '');
const conflict = () =>
  problem(409, 'Hồ sơ khởi tạo đã được người khác thay đổi. Vui lòng tải lại.', {
    code: 'CONFIG-409-VERSION-CONFLICT',
  });
const wrongState = (title: string) => problem(409, title, { code: 'CONFIG-409-TENANT-STATE' });
const fieldError = (status: number, title: string, field: string, code?: string) =>
  problem(status, title, { code, errors: [{ field, message: title }] } as never);

function unknownKeys(body: unknown, allowed: string[]): string | undefined {
  if (!body || typeof body !== 'object') return undefined;
  return Object.keys(body).find((k) => !allowed.includes(k));
}
const strictError = (key: string) =>
  fieldError(422, 'Yêu cầu có trường không hợp lệ.', key, 'CONFIG-422-VALIDATION');

function log(t: StoredTenant, to: TenantStatus, reason: string) {
  t.statusLog = [{ at: new Date().toISOString(), from: t.status, to, reason }, ...t.statusLog];
  t.status = to;
}

function domainTaken(domain: string, except?: string): boolean {
  const d = domain.trim().toLowerCase();
  return load().some((t) => t.id !== except && t.domains.some((x) => x.domain === d));
}

/** `registry.cell` kèm số tenant và tài khoản đang phục vụ (`RegistryCell`). */
function registryCells(): RegistryCell[] {
  const all = load();
  return fixture.cells.map((c, i) => {
    const mine = all.filter((t) => t.cellCode === c.code && t.status !== 'DA_CHAM_DUT');
    return {
      id: `0192f1a2-0000-7c00-8a00-${String(0xc001 + i).padStart(12, '0')}`,
      code: c.code,
      name: c.name,
      primaryDc: c.primaryDc as RegistryCell['primaryDc'],
      standbyDc: c.standbyDc ?? null,
      tenancyModel: registryModel(c.tenancyModel as TenancyModel),
      apiBaseUrl: `https://api.${c.code.toLowerCase()}.okrkpi.vn`,
      capacityAccounts: c.capacityAccounts,
      status: c.status as RegistryCell['status'],
      tenantCount: mine.length,
      accountCount: mine.reduce((s, t) => s + t.accountCount, 0),
    };
  });
}

/** Tải sau khi thêm `accounts` tài khoản vào cell, và cell có nhận thêm được không (BR-01-02). */
function cellFit(code: string, model: TenancyModel, accounts: number, self?: string) {
  const c = registryCells().find((x) => x.code === code);
  if (!c || c.tenancyModel !== registryModel(model)) return null;
  const others = load().filter(
    (t) => t.cellCode === code && t.id !== self && t.status !== 'DA_CHAM_DUT',
  );
  const used = others.reduce((s, t) => s + t.accountCount, 0);
  const loadAfter = (used + accounts) / c.capacityAccounts;
  const dedicatedTaken = model === 'silo' && others.length > 0;
  return {
    loadAfter,
    eligible: c.status === 'HOAT_DONG' && !dedicatedTaken && loadAfter < CELL_LOAD_THRESHOLD,
  };
}

const isolated = (t: StoredTenant) => ISOLATED_SECTORS.includes(t.sector as TenantSector);
const suggestion = (t: StoredTenant) =>
  suggestTenancyModel(t.expectedAccounts ?? t.maxAccounts, isolated(t));
const provisioned = (t: StoredTenant) => !['KHOI_TAO', 'DANG_CAP_PHAT'].includes(t.status);

/** Kiểm tra tổng thể của bước 6 (`ReadinessCheck`, QT-01 bước 9). */
function readiness(t: StoredTenant): ReadinessCheck[] {
  const o = t.onboarding!;
  const fit = t.cellCode ? cellFit(t.cellCode, t.tenancyModel, t.accountCount, t.id) : null;
  const packsOk = t.flows.every((f) => {
    const b = o.policyBindings.find((x) => x.flow === f);
    const p = PACK_CATALOG.find((x) => x.id === b?.packVersionId);
    return !!p && p.goldenPassRate >= 1;
  });
  const idpOk = o.idps.length > 0 && o.idps.every((i) => i.lastTestOk === true);
  const done = provisioned(t);
  return [
    {
      code: 'CAPACITY',
      passed: !!fit && fit.loadAfter < CELL_LOAD_THRESHOLD,
      detail: fit
        ? `${t.cellCode} đang dùng ${Math.round(fit.loadAfter * 100)}%`
        : 'Chưa chọn cụm máy chủ',
    },
    {
      code: 'DOMAIN',
      passed: done && !!t.domains[0]?.verifiedAt,
      detail: done ? `${t.domain} đã xác minh` : 'Chưa cấp phát',
    },
    {
      code: 'TLS',
      passed: done,
      detail: done ? 'Chứng chỉ cấp tự động' : 'Chưa cấp phát',
    },
    { code: 'POLICY_PACK_GOLDEN', passed: packsOk },
    {
      code: 'IDP',
      passed: idpOk,
      detail: idpOk ? undefined : 'Còn hệ thống đăng nhập chưa kiểm tra kết nối hoặc kết nối lỗi',
    },
    {
      code: 'ORG_TREE',
      passed: done,
      detail: done ? undefined : 'Chưa cấp phát',
    },
    {
      code: 'HIGH_DISCREPANCY',
      passed: o.highIssues === 0,
      detail: o.highIssues
        ? `Còn ${o.highIssues.toLocaleString('vi-VN')} mục lệch mức Cao cần đối soát ở màn hình Đồng bộ và đối soát nhân sự`
        : undefined,
    },
  ];
}

/** `ConfigTenant` của tenant đang khởi tạo, kèm gợi ý mô hình và kiểm tra tổng thể. */
export function onboardingView(t: StoredTenant): ConfigTenant {
  return {
    ...tenantDetail(t, readiness(t)),
    suggestedTenancyModel: suggestion(t),
  };
}

/** Bước wizard từ dữ liệu (như `Step` của SVC-03 tenant-registry). */
function deriveStep(t: StoredTenant): number | undefined {
  const o = t.onboarding!;
  switch (t.status) {
    case 'KHOI_TAO':
      return t.cellCode && t.domain && o.kekMode ? 3 : 2;
    case 'DANG_CAP_PHAT':
      return 4;
    case 'DANG_CAU_HINH':
      if (!o.idps.length) return 4;
      if (!o.staffSources.length) return 5;
      return 6;
    case 'SAN_SANG':
      return 6;
    default:
      return undefined;
  }
}

function create(r: MockRequest): MockResponse {
  const b = r.body as TenantOnboardingInput;
  const extra = unknownKeys(b, CREATE_KEYS);
  if (extra) return strictError(extra);
  if (!b?.name?.trim()) return fieldError(422, 'Đồng chí chưa nhập tên đơn vị triển khai.', 'name');
  if (!/^[a-z0-9_]{2,32}$/.test(b.code ?? '')) {
    return fieldError(
      422,
      'Mã đơn vị chỉ gồm chữ thường không dấu, chữ số và dấu gạch dưới.',
      'code',
    );
  }
  if (!SECTORS.includes(b.sector)) {
    return fieldError(422, 'Đồng chí chưa chọn khối, lĩnh vực.', 'sector');
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
  if (!b.adminContact?.fullName?.trim()) {
    return fieldError(422, 'Đồng chí chưa nhập đầu mối quản trị.', 'adminContact.fullName');
  }
  if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(b.adminContact.email ?? '')) {
    return fieldError(422, 'Thư điện tử đầu mối không hợp lệ.', 'adminContact.email');
  }
  const id = `t-${b.code}`;
  const domain = `${b.code.replace(/_/g, '-')}.okr.viettel.vn`;
  if (domainTaken(domain)) {
    return fieldError(409, MSG_01_04(domain), 'domain', 'CONFIG-409-DOMAIN-TAKEN');
  }
  const model = suggestTenancyModel(b.expectedAccounts, ISOLATED_SECTORS.includes(b.sector));
  const now = new Date().toISOString();
  const ob: OnboardingExtra = {
    onboardingCode: nextOnboardingCode(),
    expectedUnits: b.expectedUnits,
    adminContact: { fullName: b.adminContact.fullName.trim(), email: b.adminContact.email },
    createdAt: now,
    policyBindings: [],
    uiPackVersionId: DEFAULT_UI_PACK.id,
    idps: [],
    staffSources: [],
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
    uiPack: uiLabel(DEFAULT_UI_PACK.id),
    status: 'KHOI_TAO',
    rowVersion: 0,
    sector: b.sector,
    flows: b.flows,
    contractRef: b.contractRef.trim(),
    contractValidTo: b.contractValidTo,
    maxAccounts: b.expectedAccounts,
    expectedAccounts: b.expectedAccounts,
    dataRegion: 'HOALAC',
    domains: [{ domain, kind: 'PRIMARY' }],
    statusLog: [{ at: now, to: 'KHOI_TAO', reason: 'Khai báo đơn vị triển khai' }],
    onboarding: ob,
  };
  t.onboardingStep = deriveStep(t);
  load().unshift(t);
  return {
    status: 201,
    body: onboardingView(t),
    headers: { Location: `/api/v1/config/tenants/${id}`, ETag: `"${t.rowVersion}"` },
  };
}

/** Kiểm IdP trong thân PATCH; giữ kết quả kiểm tra cũ khi cấu hình kết nối không đổi. */
function mergeIdps(old: TenantIdp[], next: TenantIdp[]): TenantIdp[] | MockResponse {
  const seen = new Set<string>();
  const out: TenantIdp[] = [];
  for (const [i, idp] of next.entries()) {
    const extra = unknownKeys(idp, IDP_KEYS);
    if (extra) return strictError(`idps[${i}].${extra}`);
    const alias = idp.idpAlias?.trim() ?? '';
    if (!alias) {
      return fieldError(
        422,
        'Đồng chí chưa nhập tên định danh của hệ thống đăng nhập.',
        `idps[${i}].idpAlias`,
      );
    }
    if (seen.has(alias)) {
      return fieldError(422, `Tên định danh ${alias} bị trùng.`, `idps[${i}].idpAlias`);
    }
    seen.add(alias);
    if (idp.protocol !== 'OIDC' && idp.protocol !== 'SAML') {
      return fieldError(422, 'Chuẩn liên kết không hợp lệ.', `idps[${i}].protocol`);
    }
    if (!idp.issuer?.trim()) {
      return fieldError(422, 'Đồng chí chưa nhập địa chỉ hệ thống đăng nhập.', `idps[${i}].issuer`);
    }
    const prev = old.find(
      (o) => o.idpAlias === alias && o.protocol === idp.protocol && o.issuer === idp.issuer.trim(),
    );
    out.push({
      id: prev?.id ?? crypto.randomUUID(),
      idpAlias: alias,
      protocol: idp.protocol,
      issuer: idp.issuer.trim(),
      isVneid: !!idp.isVneid,
      emailDomains: idp.emailDomains,
      status: 'HOAT_DONG',
      lastTestedAt: prev?.lastTestedAt,
      lastTestOk: prev?.lastTestOk,
    });
  }
  return out;
}

function patch(r: MockRequest): MockResponse {
  const t = find(r);
  if (!t) return problem(404, 'Không tìm thấy hồ sơ khởi tạo');
  if (ifMatch(r) === undefined) return problem(428, 'Thiếu phiên bản bản ghi (If-Match).');
  if (ifMatch(r) !== String(t.rowVersion)) return conflict();
  const p = (r.body ?? {}) as TenantOnboardingPatch;
  const extra = unknownKeys(p, PATCH_KEYS);
  if (extra) return strictError(extra);
  const o = t.onboarding!;
  if (SETUP_KEYS.some((k) => k in p) && t.status !== 'KHOI_TAO') {
    return wrongState(
      'Đơn vị đã được cấp phát hạ tầng, không sửa được thông tin từ bước 1 đến bước 3.',
    );
  }
  if (('idps' in p || 'staffSources' in p) && !['DANG_CAU_HINH', 'SAN_SANG'].includes(t.status)) {
    return wrongState('Chỉ khai báo đăng nhập và nguồn nhân sự sau khi cấp phát hạ tầng xong.');
  }
  if (p.flows !== undefined && !p.flows.length) {
    return fieldError(422, 'Đồng chí cần chọn ít nhất một luồng đánh giá áp dụng.', 'flows');
  }
  if (p.expectedAccounts !== undefined && !(p.expectedAccounts > 0)) {
    return fieldError(422, 'Số tài khoản dự kiến phải lớn hơn 0.', 'expectedAccounts');
  }
  const accounts = p.expectedAccounts ?? t.expectedAccounts ?? t.maxAccounts;
  const suggest = suggestTenancyModel(accounts, isolated(t));
  const model = p.tenancyModel ?? t.tenancyModel;
  if (p.tenancyModel && !modelAllowed(p.tenancyModel, suggest)) {
    return fieldError(
      422,
      // BR-01-01
      'Đơn vị trên 1.000.000 tài khoản hoặc thuộc diện cách ly phải dùng mức cô lập Cụm riêng.',
      'tenancyModel',
      'CONFIG-422-SILO-REQUIRED',
    );
  }
  if (p.cellCode) {
    const fit = cellFit(p.cellCode, model, accounts, t.id);
    if (!fit || !fit.eligible) {
      return fieldError(
        422,
        // BR-01-02
        `Cụm máy chủ ${p.cellCode} vượt ngưỡng 85% dung lượng sau khi thêm đơn vị hoặc đang không sử dụng được.`,
        'cellCode',
        'CONFIG-422-CELL-OVER-CAPACITY',
      );
    }
  }
  if (p.domain !== undefined) {
    const d = p.domain.trim().toLowerCase();
    if (!DOMAIN_RE.test(d)) return fieldError(422, 'Tên miền không hợp lệ.', 'domain');
    if (domainTaken(d, t.id)) {
      return fieldError(409, MSG_01_04(d), 'domain', 'CONFIG-409-DOMAIN-TAKEN');
    }
  }
  if (model === 'silo' && (p.kekMode ?? o.kekMode) === 'DUNG_CHUNG_CELL') {
    return fieldError(
      422,
      'Mức cô lập Cụm riêng bắt buộc dùng khoá mã hoá riêng của đơn vị.',
      'kekMode',
    );
  }
  if (p.policyBindings) {
    for (const b of p.policyBindings) {
      const pack = PACK_CATALOG.find((x) => x.id === b.packVersionId && x.flow === b.flow);
      if (!pack) {
        return fieldError(422, 'Bộ quy định đánh giá không tồn tại.', 'policyBindings');
      }
      if (pack.goldenPassRate < 1) {
        return fieldError(
          422,
          MSG_01_05(packLabel(pack.id)),
          'policyBindings',
          'CONFIG-422-POLICY-PACK-NOT-READY',
        );
      }
    }
  }
  let idps: TenantIdp[] | undefined;
  if (p.idps) {
    const merged = mergeIdps(o.idps, p.idps);
    if (!Array.isArray(merged)) return merged;
    idps = merged;
  }
  if (p.staffSources?.some((s) => !['CSDL_CBCCVC', 'CSDL_DANG_VIEN', 'SCIM'].includes(s.type))) {
    return fieldError(422, 'Nguồn nhân sự không hợp lệ.', 'staffSources');
  }
  if (p.adminContact && (!p.adminContact.fullName?.trim() || !p.adminContact.email)) {
    return fieldError(422, 'Đồng chí chưa nhập đầu mối quản trị.', 'adminContact.fullName');
  }
  // ghi
  if (p.name) t.name = p.name.trim();
  if (p.contractRef !== undefined) t.contractRef = p.contractRef;
  if (p.contractValidTo !== undefined) t.contractValidTo = p.contractValidTo;
  if (p.flows) t.flows = p.flows;
  if (p.tenancyModel) t.tenancyModel = p.tenancyModel;
  if (p.cellCode !== undefined) t.cellCode = p.cellCode;
  if (p.dataRegion) t.dataRegion = p.dataRegion;
  if (p.expectedAccounts) t.maxAccounts = t.expectedAccounts = p.expectedAccounts;
  if (p.expectedUnits !== undefined) o.expectedUnits = p.expectedUnits;
  if (p.domain !== undefined) {
    t.domain = p.domain.trim().toLowerCase();
    t.domains = [{ domain: t.domain, kind: 'PRIMARY' }];
  }
  if (p.kekMode) o.kekMode = p.kekMode;
  if (p.policyBindings) {
    o.policyBindings = p.policyBindings.map(({ flow, packVersionId }) => ({ flow, packVersionId }));
    t.policyPacks = o.policyBindings.map((b) => packLabel(b.packVersionId));
  }
  if (p.uiPackVersionId) {
    o.uiPackVersionId = p.uiPackVersionId;
    t.uiPack = uiLabel(p.uiPackVersionId);
  }
  if (p.adminContact) o.adminContact = { ...p.adminContact };
  if (idps) o.idps = idps;
  if (p.staffSources) {
    o.staffSources = p.staffSources.map(({ type, connectorId }) => ({
      type,
      connectorId,
      connected: true,
    }));
  }
  t.onboardingStep = deriveStep(t);
  autoReady(t);
  t.rowVersion++;
  return ok(onboardingView(t), { ETag: `"${t.rowVersion}"` });
}

function remove(r: MockRequest): MockResponse {
  const t = find(r);
  if (!t) return problem(404, 'Không tìm thấy hồ sơ khởi tạo');
  if (ifMatch(r) === undefined) return problem(428, 'Thiếu phiên bản bản ghi (If-Match).');
  if (ifMatch(r) !== String(t.rowVersion)) return conflict();
  if (t.status !== 'KHOI_TAO') {
    return wrongState('Chỉ huỷ được yêu cầu đang ở trạng thái Khởi tạo, chưa cấp phát hạ tầng.');
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
    body: { id: jobId, type: 'PROVISION', status: 'CHO', progress: 0 } satisfies Job,
    headers: { Location: `/api/v1/jobs/${jobId}` },
  });
  if (t.status === 'DANG_CAP_PHAT' && o.provision) return location(o.provision.jobId);
  if (tag !== String(t.rowVersion)) return conflict();
  if (t.status !== 'KHOI_TAO') return wrongState('Đơn vị đã được cấp phát hạ tầng.');
  if (!t.cellCode || !t.domain) {
    return problem(422, 'Đồng chí chưa chọn cụm máy chủ và tên miền ở bước 2.', {
      code: 'CONFIG-422-VALIDATION',
    });
  }
  const missing = t.flows.find((f) => !o.policyBindings.some((b) => b.flow === f));
  if (missing) {
    return fieldError(
      422,
      // BR-01-04
      `Chưa gắn bộ quy định đánh giá cho luồng ${missing === 'DANG' ? 'Đảng' : 'Nhà nước'}.`,
      'policyBindings',
      'CONFIG-422-FLOW-PACK-MISSING',
    );
  }
  o.provision = {
    jobId: crypto.randomUUID(),
    startedAt: Date.now(),
    failAt: t.domain.includes('loi-tls') ? PROVISION_STEPS.indexOf('Cấp chứng chỉ TLS') : undefined,
  };
  o.provisionJobId = o.provision.jobId;
  delete o.provisionError;
  log(
    t,
    'DANG_CAP_PHAT',
    `Xác nhận cụm máy chủ ${t.cellCode}, mức cô lập ${TENANCY_MODEL_LABEL[t.tenancyModel]}`,
  );
  t.onboardingStep = deriveStep(t);
  t.rowVersion++;
  return location(o.provision.jobId);
}

/** `GET /api/v1/jobs/{id}` của saga cấp phát: tìm tenant đang hoặc đã chạy job này. */
function provisionJobById(jobId: string): MockResponse | null {
  const t = load().find((x) => x.onboarding?.provisionJobId === jobId);
  return t ? provisionJob(t, jobId) : null;
}
addMockJobSource(provisionJobById);

function provisionJob(t: StoredTenant, jobId: string): MockResponse {
  const o = t.onboarding!;
  const base = { id: jobId, type: 'PROVISION' };
  if (!o.provision || o.provision.jobId !== jobId) {
    // tác vụ đã kết thúc: trả kết quả cuối theo trạng thái tenant
    if (o.provisionError) {
      return ok({
        ...base,
        status: 'THAT_BAI',
        message: o.provisionError.step,
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
    log(t, 'KHOI_TAO', `Cấp phát lỗi ở bước ${step}, đã hoàn tác`);
    t.onboardingStep = deriveStep(t);
    t.rowVersion++;
    return provisionJob(t, jobId);
  }
  if (ratio >= 1) {
    o.provision = undefined;
    t.domains = t.domains.map((d) => ({ ...d, verifiedAt: new Date().toISOString() }));
    log(t, 'DANG_CAU_HINH', 'Hoàn tất cấp phát hạ tầng');
    t.onboardingStep = deriveStep(t);
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

/** Thử kết nối một IdP: địa chỉ phải là https, chứa `loi` thì giả lập quá thời gian chờ. */
function probe(idp: Pick<TenantIdp, 'protocol' | 'issuer'>): TenantIdpTestResult {
  const issuer = idp.issuer?.trim() ?? '';
  const checkedAt = new Date().toISOString();
  if (!/^https:\/\//.test(issuer)) {
    return {
      success: false,
      protocol: idp.protocol,
      latencyMs: 0,
      message: 'Địa chỉ phải bắt đầu bằng https://.',
      checkedAt,
    };
  }
  if (issuer.includes('loi')) {
    return {
      success: false,
      protocol: idp.protocol,
      latencyMs: 10_000,
      message: 'Không kết nối được: quá thời gian chờ 10 giây.',
      checkedAt,
    };
  }
  return {
    success: true,
    protocol: idp.protocol,
    latencyMs: 412,
    message:
      idp.protocol === 'SAML'
        ? 'Đọc được tài liệu mô tả và chứng chỉ ký của hệ thống đăng nhập.'
        : 'Đọc được cấu hình và khoá xác thực đăng nhập.',
    checkedAt,
  };
}

function testIdp(r: MockRequest): MockResponse {
  const t = find(r);
  if (!t) return problem(404, 'Không tìm thấy hồ sơ khởi tạo');
  const body = (r.body ?? {}) as { idpAlias?: string; idp?: TenantIdp };
  const extra = unknownKeys(body, ['idpAlias', 'idp']);
  if (extra) return strictError(extra);
  if (body.idp && !body.idpAlias) return ok(probe(body.idp));
  const stored = t.onboarding!.idps.find((i) => i.idpAlias === body.idpAlias);
  if (!stored) {
    return fieldError(422, 'Không tìm thấy hệ thống đăng nhập đã lưu.', 'idpAlias');
  }
  const res = probe(stored);
  // `lastTestOk` chỉ đọc với client; máy chủ ghi kết quả lần kiểm tra
  Object.assign(stored, { lastTestOk: res.success, lastTestedAt: res.checkedAt });
  autoReady(t);
  t.rowVersion++;
  return ok(res);
}

/** `DANG_CAU_HINH` → `SAN_SANG` khi đủ IdP, cây tổ chức và không còn lệch mức Cao (tự động kiểm tra). */
function autoReady(t: StoredTenant) {
  if (t.status !== 'DANG_CAU_HINH' || (t.onboardingStep ?? 0) < 6) return;
  if (readiness(t).every((c) => c.passed)) {
    log(t, 'SAN_SANG', 'Đủ điều kiện kích hoạt');
    t.onboardingStep = deriveStep(t);
  }
}

function activate(r: MockRequest): MockResponse {
  const t = find(r);
  if (!t) return problem(404, 'Không tìm thấy hồ sơ khởi tạo');
  if (ifMatch(r) === undefined) return problem(428, 'Thiếu phiên bản bản ghi (If-Match).');
  if (ifMatch(r) !== String(t.rowVersion)) return conflict();
  autoReady(t);
  const failed = readiness(t).filter((c) => !c.passed);
  if (failed.some((c) => c.code === 'HIGH_DISCREPANCY')) {
    return problem(
      422,
      // BR-01-10
      'Chưa kích hoạt được: còn lệch nhân sự mức Cao cần đối soát.',
      { code: 'CONFIG-422-HIGH-DISCREPANCY' },
    );
  }
  if (t.status !== 'SAN_SANG' || failed.length) {
    return wrongState('Chưa kích hoạt được: đơn vị chưa đạt đủ các mục kiểm tra tổng thể.');
  }
  log(t, 'DANG_VAN_HANH', 'Kích hoạt, đã gửi thư cho đầu mối quản trị');
  t.onboardingStep = undefined;
  t.rowVersion++;
  return ok(onboardingView(t), { ETag: `"${t.rowVersion}"` });
}

export const ONBOARDING_ROUTES: MockRoute[] = [
  { method: 'POST', pattern: '/api/v1/config/tenants', handle: create },
  {
    method: 'GET',
    pattern: '/api/v1/registry/cells',
    handle: (r) => {
      const model = r.params.get('tenancyModel');
      const status = r.params.get('status');
      const items = registryCells().filter(
        (c) => (!model || c.tenancyModel === model) && (!status || c.status === status),
      );
      return ok({ items, nextCursor: null });
    },
  },
  { method: 'PATCH', pattern: '/api/v1/config/tenants/:id', handle: patch },
  { method: 'DELETE', pattern: '/api/v1/config/tenants/:id', handle: remove },
  { method: 'POST', pattern: '/api/v1/config/tenants/:id/provision', handle: provision },
  { method: 'POST', pattern: '/api/v1/config/tenants/:id/idp/test', handle: testIdp },
  { method: 'POST', pattern: '/api/v1/config/tenants/:id/activate', handle: activate },
];
