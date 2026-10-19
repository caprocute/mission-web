import { TenantDetail } from '../../lib/models/tenant';
import { TenantOnboarding } from '../../lib/models/tenant-onboarding';
import { resolveRelativeDates } from '../mock-router';
import fixture from './tenants.json';

/**
 * Kho dữ liệu dùng chung của giả lập danh bạ tenant (SCR-0101) và trình khởi tạo tenant (SCR-0102).
 * Mỗi tenant giữ thêm phần hồ sơ khởi tạo (`onboarding`) và số lệch nhân sự mức Cao (BR-01-10).
 */
export interface OnboardingExtra extends Omit<
  TenantOnboarding,
  | 'id'
  | 'name'
  | 'code'
  | 'sector'
  | 'contractRef'
  | 'flows'
  | 'status'
  | 'step'
  | 'tenancyModel'
  | 'cellCode'
  | 'domain'
  | 'dataRegion'
  | 'rowVersion'
  | 'provisionLocation'
> {
  /** Số lệch mức Cao còn lại (SCR-0104), chặn kích hoạt khi > 0. */
  highIssues: number;
  /** Saga cấp phát đang chạy (khi `DANG_CAP_PHAT`). */
  provision?: { jobId: string; startedAt: number; failAt?: number };
}

export type StoredTenant = TenantDetail & { onboarding?: OnboardingExtra };

let tenants: StoredTenant[] | null = null;
let seq = 340;

type FixtureOnboarding = Record<
  string,
  Partial<OnboardingExtra> & { contractRefOverride?: string; provisionRunning?: boolean }
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
      const { contractRefOverride, provisionRunning, ...rest } = ob;
      if (contractRefOverride) stored.contractRef = contractRefOverride;
      stored.onboarding = {
        ...(rest as OnboardingExtra),
        expectedAccounts: t.expectedAccounts ?? t.maxAccounts,
        provision: provisionRunning
          ? { jobId: crypto.randomUUID(), startedAt: Date.now() - 2_000 }
          : undefined,
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
