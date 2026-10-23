import {
  ConfigTenant,
  TENANT_ONBOARDING_STATUSES,
  TenancyModel,
  TenantStatus,
} from '../../lib/models/tenant';
import {
  KekMode,
  ReadinessCheck,
  TenantAdminContact,
  TenantIdp,
  TenantPolicyBinding,
  TenantStaffSource,
} from '../../lib/models/tenant-onboarding';
import { resolveRelativeDates } from '../mock-router';
import fixture from './tenants.json';

/**
 * Kho dữ liệu dùng chung của giả lập danh bạ tenant (SCR-0101) và trình khởi tạo tenant (SCR-0102).
 * Tenant đang khởi tạo giữ thêm phần cấu hình wizard theo `ConfigTenant` của `config.yaml`
 * (`onboarding`), số lệch nhân sự mức Cao (BR-01-10) và trạng thái saga cấp phát giả lập.
 */
export interface OnboardingExtra {
  onboardingCode: string;
  expectedUnits?: number;
  adminContact?: TenantAdminContact;
  createdAt: string;
  kekMode?: KekMode;
  policyBindings: Pick<TenantPolicyBinding, 'flow' | 'packVersionId'>[];
  uiPackVersionId?: string;
  idps: TenantIdp[];
  staffSources: TenantStaffSource[];
  /** Số lệch mức Cao còn lại (SCR-0104), chặn kích hoạt khi > 0. */
  highIssues: number;
  /** Saga cấp phát đang chạy (khi `DANG_CAP_PHAT`). */
  provision?: { jobId: string; startedAt: number; failAt?: number };
  /** Job saga gần nhất (`provisionJobId`). */
  provisionJobId?: string;
  /** Lỗi saga gần nhất đã bù trừ (MSG-01-07), trả qua `Job.problem` của job gần nhất. */
  provisionError?: { step: string; reason: string };
}

/**
 * Bản ghi tenant trong kho giả lập: hợp các cột `registry.tenant`, tên miền, nhật ký trạng thái và
 * phần hiển thị của SCR-0101; các handler dựng `ConfigTenantRow`, `ConfigTenant`, `RegistryTenant`
 * theo hợp đồng từ bản ghi này.
 */
export interface TenantRecord {
  id: string;
  code: string;
  name: string;
  domain: string;
  cellCode: string;
  tenancyModel: TenancyModel;
  accountCount: number;
  policyPacks: string[];
  uiPack: string;
  status: TenantStatus;
  onboardingStep?: number;
  rowVersion: number;
  sector: string;
  flows: ('DANG' | 'NHA_NUOC')[];
  contractRef?: string;
  contractValidTo?: string;
  maxAccounts: number;
  expectedAccounts?: number;
  dataRegion: string;
  domains: { domain: string; kind: 'PRIMARY' | 'ALIAS' | 'EMBED'; verifiedAt?: string }[];
  /** `registry.tenant_status_log`, mới nhất trước. */
  statusLog: { at: string; from?: TenantStatus; to: TenantStatus; reason?: string }[];
}

export type StoredTenant = TenantRecord & { onboarding?: OnboardingExtra };

let tenants: StoredTenant[] | null = null;
let seq = 340;

type FixtureOnboarding = Record<
  string,
  Partial<OnboardingExtra> & {
    contractRefOverride?: string;
    contractValidTo?: string;
    provisionRunning?: boolean;
  }
>;

export function load(): StoredTenant[] {
  if (tenants) return tenants;
  const onboarding = resolveRelativeDates(
    structuredClone(fixture.onboarding as unknown as FixtureOnboarding),
  );
  const named = resolveRelativeDates(structuredClone(fixture.tenants)).map((t) => {
    const ob = onboarding[t.id];
    const stored = {
      ...t,
      rowVersion: 0,
      domains: [{ domain: t.domain, kind: 'PRIMARY', verifiedAt: '2026-01-15T08:00:00+07:00' }],
      statusLog: resolveRelativeDates(
        structuredClone(
          (fixture.statusLog as Record<string, StoredTenant['statusLog']>)[t.id] ?? [],
        ),
      ),
    } as StoredTenant;
    if (ob) {
      const { contractRefOverride, contractValidTo, provisionRunning, ...rest } = ob;
      if (contractRefOverride) stored.contractRef = contractRefOverride;
      if (contractValidTo) stored.contractValidTo = contractValidTo;
      const provision = provisionRunning
        ? { jobId: crypto.randomUUID(), startedAt: Date.now() - 2_000 }
        : undefined;
      stored.onboarding = {
        ...(rest as OnboardingExtra),
        provision,
        provisionJobId: provision?.jobId,
      };
    }
    return stored;
  });
  // tenant pool nhỏ ở CELL-SH (mã `dvNNN`), đủ để bảng có nhiều trang như vùng điều phối thật
  const pool = fixture.poolUnits.map((name, i): StoredTenant => {
    const code = `dv${String(i + 1).padStart(3, '0')}`;
    const party = name.startsWith('Đảng uỷ');
    return {
      id: `t-${code}`,
      code,
      name,
      domain: `${code.replace('_', '-')}.okr.viettel.vn`,
      cellCode: 'CELL-SH',
      tenancyModel: 'pool',
      accountCount: 180 + ((i * 397) % 2600),
      policyPacks: [party ? 'Khối Đảng · Quy định mới v2.0' : 'Nhà nước · NĐ 335 v1.3'],
      uiPack: 'Mặc định v3.0',
      status: 'DANG_VAN_HANH',
      rowVersion: 0,
      sector: party ? 'DANG' : 'SU_NGHIEP',
      flows: [party ? 'DANG' : 'NHA_NUOC'],
      maxAccounts: 5000,
      dataRegion: 'PHIANAM',
      domains: [{ domain: `${code.replace('_', '-')}.okr.viettel.vn`, kind: 'PRIMARY' }],
      statusLog: [],
    };
  });
  tenants = [...named, ...pool];
  return tenants;
}

export function resetTenantStore(): void {
  tenants = null;
  seq = 340;
}

/** Mã yêu cầu khởi tạo tiếp theo, `ONB-yyyy-nnnn`. */
export function nextOnboardingCode(): string {
  seq++;
  return `ONB-${new Date().getFullYear()}-${String(seq).padStart(4, '0')}`;
}

/** Bước wizard theo hợp đồng: chỉ có khi tenant còn khởi tạo (`null` khi đã vận hành). */
export function onboardingStepOf(t: StoredTenant): number | null {
  return TENANT_ONBOARDING_STATUSES.includes(t.status) || t.status === 'SAN_SANG'
    ? (t.onboardingStep ?? (t.status === 'SAN_SANG' ? 6 : 1))
    : null;
}

/** Id phiên bản gói giả lập của tenant đã vận hành, ổn định theo tên gói. */
const packId = (name: string) =>
  `0192e1c4-5d6e-7f80-9a1b-${[...name]
    .reduce((h, c) => (h * 31 + c.charCodeAt(0)) % 1e12, 7)
    .toString()
    .padStart(12, '0')}`;

/** Tách "tên vX.Y" của UI Pack thành `{ name, version }` theo `ConfigTenant.uiPack`. */
function uiPackRef(p: string): ConfigTenant['uiPack'] {
  if (!p) return undefined;
  const m = /^(.*) v(\d+(?:\.\d+)*)$/.exec(p);
  return m ? { name: m[1], version: m[2] } : { name: p, version: '' };
}

/** Phiên bản Policy Pack gốc mà giả lập biết (cùng id với giả lập `/policies/packs`). */
export const PACK_CATALOG = fixture.policyPacks as {
  id: string;
  flow: 'DANG' | 'NHA_NUOC';
  packCode: string;
  name: string;
  version: string;
  goldenPassRate: number;
}[];

/** Nhãn "tên vX.Y" của SCR-0101 cho một phiên bản gói. */
export function packLabel(id: string): string {
  const p = PACK_CATALOG.find((x) => x.id === id);
  return p ? `${p.name} v${p.version.split('.').slice(0, 2).join('.')}` : 'Bộ quy định đánh giá';
}

/**
 * Chi tiết `ConfigTenant`. Tenant đang khởi tạo lấy phần cấu hình wizard từ `onboarding`; kiểm tra
 * tổng thể (`readinessChecks`) do bộ giả lập trình khởi tạo tính và truyền vào.
 */
export function tenantDetail(t: StoredTenant, readiness?: ReadinessCheck[]): ConfigTenant {
  const o = t.onboarding;
  const bindings = o
    ? o.policyBindings.map((b) => {
        const p = PACK_CATALOG.find((x) => x.id === b.packVersionId);
        return { ...b, packCode: p?.packCode ?? '', version: p?.version ?? '' };
      })
    : t.policyPacks.map((p) => {
        const m = /^(.*) v(\d+(?:\.\d+)*)$/.exec(p);
        return {
          flow: p.startsWith('Khối Đảng') ? ('DANG' as const) : ('NHA_NUOC' as const),
          packVersionId: packId(p),
          packCode: m ? m[1] : p,
          version: m ? m[2] : '',
        };
      });
  const created = t.statusLog.at(-1)?.at;
  return {
    id: t.id,
    code: t.code,
    name: t.name,
    sector: t.sector as ConfigTenant['sector'],
    onboardingCode: o?.onboardingCode,
    contractRef: t.contractRef,
    contractValidTo: t.contractValidTo,
    flows: t.flows,
    expectedAccounts: t.expectedAccounts,
    expectedUnits: o?.expectedUnits,
    maxAccounts: t.maxAccounts,
    accountCount: t.accountCount,
    tenancyModel: t.tenancyModel,
    cellCode: t.cellCode || undefined,
    domain: t.domain || undefined,
    dataRegion: t.dataRegion,
    kekMode: o?.kekMode,
    policyBindings: bindings,
    uiPackVersionId: o?.uiPackVersionId,
    uiPack: uiPackRef(t.uiPack),
    statusLog: t.statusLog.map((l, i) => ({
      id: `${t.id}-log-${t.statusLog.length - i}`,
      fromStatus: l.from ?? null,
      toStatus: l.to,
      reason: l.reason ?? '',
      changedAt: l.at,
    })),
    idps: o ? structuredClone(o.idps) : undefined,
    staffSources: o ? structuredClone(o.staffSources) : undefined,
    adminContact: o?.adminContact ? { ...o.adminContact } : undefined,
    status: t.status,
    onboardingStep: onboardingStepOf(t),
    readinessChecks: readiness,
    provisionJobId: o ? (o.provisionJobId ?? null) : undefined,
    rowVersion: t.rowVersion,
    createdAt: o?.createdAt ?? created,
  };
}
