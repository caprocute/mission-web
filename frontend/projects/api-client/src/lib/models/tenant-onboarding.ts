import type { components as CommonComponents } from '../generated/common';
import type { components as ConfigComponents } from '../generated/config';
import type { components as PlatformComponents } from '../generated/platform';
import type { components as PoliciesComponents } from '../generated/policies';
import { CELL_LOAD_THRESHOLD, ConfigTenant, TenancyModel, TenantStatus } from './tenant';

type S = ConfigComponents['schemas'];

/**
 * Kiểu của trình khởi tạo tenant SCR-0102 (WEB-017, đối chiếu WEB-072): bí danh lược đồ
 * `config.yaml` (`ConfigTenant`, `ConfigTenantCreate`, `ConfigTenantPatch`, `TenantIdp`,
 * `TenantIdpTestResult`, `ReadinessCheck`), `platform.yaml` (`RegistryCell`) và `policies.yaml`
 * (`PolicyPack`). Hợp đồng là chuẩn (YC-PT-09): mã frontend theo hợp đồng, không ngược lại.
 */

/** Luồng đánh giá tenant áp dụng (`registry.tenant.flows`, SCR-0102 trường 3). */
export type TenantFlow = CommonComponents['schemas']['Flow'];

export const TENANT_FLOW_LABEL: Record<TenantFlow, string> = {
  DANG: 'Đảng',
  NHA_NUOC: 'Nhà nước',
};

/** Nhóm khách hàng (`registry.tenant.sector`). */
export type TenantSector = S['TenantSector'];

/** Khoá mã hoá KEK (SCR-0102 trường 9): riêng theo tenant trong HSM hoặc dùng chung của cell. */
export type KekMode = S['TenantKekMode'];

export const KEK_MODE_LABEL: Record<KekMode, string> = {
  RIENG: 'Khoá riêng của đơn vị, lưu trong thiết bị HSM',
  DUNG_CHUNG_CELL: 'Khoá dùng chung của cụm máy chủ',
};

/**
 * IdP của tenant (`registry.tenant_idp`, SCR-0102 trường 12). Hợp đồng chỉ có `OIDC`, `SAML` và cờ
 * `isVneid`; LDAP liên kết và không gian đăng nhập dùng chung (Keycloak Organization) không phải IdP
 * khai báo được ở bước 4.
 */
export type TenantIdp = S['TenantIdp'];
/** Kết quả `POST /config/tenants/{id}/idp/test` cho một IdP. */
export type TenantIdpTestResult = S['TenantIdpTestResult'];
export type TenantIdpTestRequest = S['TenantIdpTestRequest'];

/** Cách hiển thị một IdP trên giao diện: VNeID là IdP OIDC có `isVneid`. */
export type IdpKind = 'OIDC' | 'SAML' | 'VNEID';

export const IDP_KIND_LABEL: Record<IdpKind, string> = {
  OIDC: 'Liên kết chuẩn OpenID Connect',
  SAML: 'Liên kết chuẩn SAML2',
  VNEID: 'VNeID',
};

export function idpKind(i: Pick<TenantIdp, 'protocol' | 'isVneid'>): IdpKind {
  return i.isVneid ? 'VNEID' : i.protocol;
}

/** Nguồn nhân sự (`integ.connector`, SCR-0102 trường 13). */
export type StaffSourceType = S['TenantStaffSourceType'];
export type TenantStaffSource = S['TenantStaffSource'];

export const STAFF_SOURCE_LABEL: Record<StaffSourceType, string> = {
  CSDL_CBCCVC: 'CSDL CBCCVC qua NDXP',
  CSDL_DANG_VIEN: 'CSDL đảng viên',
  SCIM: 'Đồng bộ tài khoản tự động từ hệ thống đăng nhập',
};

/** Policy Pack gốc gắn theo luồng (SCR-0102 trường 10). */
export type TenantPolicyBinding = S['TenantPolicyBinding'];
/** Đầu mối quản trị tenant (dữ liệu cá nhân C2). */
export type TenantAdminContact = S['TenantAdminContact'];

/**
 * Hồ sơ khởi tạo mà SCR-0102 đọc và lưu nháp: chính là `ConfigTenant` (`GET /config/tenants/{id}`,
 * lưu nháp `PATCH /config/tenants/{id}` merge-patch kèm `If-Match`). Bước wizard `onboardingStep`
 * và kết quả kiểm tra `readinessChecks` do máy chủ tính từ dữ liệu, chỉ đọc.
 */
export type TenantOnboarding = ConfigTenant;

/** Nội dung khai báo ở bước 1 (`POST /config/tenants`, `ConfigTenantCreate`). */
export type TenantOnboardingInput = S['ConfigTenantCreate'];

/** Nội dung lưu nháp (`PATCH`, `ConfigTenantPatch`): chỉ gửi phần đã đổi, không gửi trường chỉ đọc. */
export type TenantOnboardingPatch = S['ConfigTenantPatch'];

/** Một mục kiểm tra tổng thể ở bước 6 (`ReadinessCheck`). */
export type ReadinessCheck = S['ReadinessCheck'];
export type ReadinessCheckCode = ReadinessCheck['code'];

/** Tên mục kiểm tra trên giao diện (mã mục chỉ dùng nội bộ). */
export const READINESS_CHECK_LABEL: Record<ReadinessCheckCode, string> = {
  CAPACITY: 'Dung lượng cụm máy chủ dưới 85%',
  DOMAIN: 'Tên miền đã xác minh',
  TLS: 'Chứng chỉ TLS đã cấp',
  POLICY_PACK_GOLDEN: 'Bộ quy định đánh giá đã phát hành, đạt 100% bộ ca kiểm thử chuẩn',
  IDP: 'Hệ thống đăng nhập hoạt động',
  ORG_TREE: 'Đã có cây tổ chức',
  HIGH_DISCREPANCY: 'Không còn lệch nhân sự mức Cao',
};

/** Kết quả một mục kiểm tra hiển thị ở vùng 4 và bước 6. */
export type CheckResult = 'DAT' | 'CANH_BAO' | 'LOI';

export const CHECK_RESULT_LABEL: Record<CheckResult, string> = {
  DAT: 'Đạt',
  CANH_BAO: 'Cảnh báo',
  LOI: 'Chưa đạt',
};

/** Cell (`registry.cell`) kèm tải hiện tại, `GET /registry/cells` của `platform.yaml`. */
export type RegistryCell = PlatformComponents['schemas']['RegistryCell'];
export type RegistryTenancyModel = PlatformComponents['schemas']['RegistryTenancyModel'];

/**
 * Một cell ứng viên ở bước 2 (vùng 3): `RegistryCell` cộng tải sau khi thêm tenant do trình duyệt tính
 * từ số tài khoản dự kiến (BR-01-02; máy chủ kiểm lại khi lưu, `CONFIG-422-CELL-OVER-CAPACITY`).
 */
export interface CellOption extends RegistryCell {
  accountCount: number;
  tenantCount: number;
  /** Tỉ lệ sử dụng sau khi thêm số tài khoản dự kiến. */
  loadAfter: number;
  /** Chọn được: cell hoạt động, `loadAfter` dưới 85%, cell riêng (silo) chưa có tenant. */
  eligible: boolean;
}

/** Mô hình thuê bao chữ thường của `config.yaml` sang chữ hoa của `platform.yaml`. */
export function registryModel(model: TenancyModel): RegistryTenancyModel {
  return model.toUpperCase() as RegistryTenancyModel;
}

/** Tính tải sau khi thêm và khả năng chọn của từng cell; sắp tải thấp trước. */
export function cellOptions(
  cells: RegistryCell[],
  model: TenancyModel,
  expectedAccounts: number,
  loads: Record<string, number> = {},
): CellOption[] {
  return cells
    .map((c) => {
      const accountCount = c.accountCount ?? loads[c.code] ?? 0;
      const tenantCount = c.tenantCount ?? 0;
      const loadAfter = c.capacityAccounts
        ? (accountCount + Math.max(0, expectedAccounts)) / c.capacityAccounts
        : 1;
      // cell silo dành riêng cho một tenant: đã có tenant thì không thêm được
      const dedicatedTaken = model === 'silo' && tenantCount > 0;
      return {
        ...c,
        accountCount,
        tenantCount,
        loadAfter,
        eligible: c.status === 'HOAT_DONG' && !dedicatedTaken && loadAfter < CELL_LOAD_THRESHOLD,
      };
    })
    .sort((a, b) => a.loadAfter - b.loadAfter);
}

/** Gói Policy Pack kèm tóm tắt phiên bản (`GET /policies/packs`). */
export type PolicyPackWithVersions = PoliciesComponents['schemas']['PolicyPack'];

/** Một phiên bản Policy Pack đã phát hành chọn được ở bước 3 (`packVersionId` của `policyBindings`). */
export interface PolicyPackOption {
  /** Id phiên bản (`policy.policy_pack_version.id`), gửi trong `policyBindings[].packVersionId`. */
  id: string;
  name: string;
  version: string;
  flow: TenantFlow;
  /** Tỉ lệ đạt ca vàng 0–1; dưới 1 thì không gắn được (BR-01-03, MSG-01-05). */
  goldenPassRate: number | null;
}

/** Phiên bản `DA_PHAT_HANH` của các gói gốc toàn cục thành lựa chọn của bước 3. */
export function policyPackOptions(packs: PolicyPackWithVersions[]): PolicyPackOption[] {
  return packs.flatMap((p) =>
    (p.versions ?? [])
      .filter((v) => v.status === 'DA_PHAT_HANH')
      .map((v) => ({
        id: v.id,
        name: p.name,
        version: v.version,
        flow: p.flow,
        goldenPassRate: v.goldenPassRate ?? null,
      })),
  );
}

/** Gói chưa đạt 100% ca kiểm thử chuẩn (BR-01-03). */
export function packNotReady(p: Pick<PolicyPackOption, 'goldenPassRate'>): boolean {
  return p.goldenPassRate === null || p.goldenPassRate < 1;
}

/** Một phiên bản UI Pack chọn được ở bước 3 (`uiPackVersionId`). */
export interface UiPackOption {
  /** Id phiên bản đang phát hành (`UiPackSummary.currentVersionId`). */
  id: string;
  name: string;
  version: string;
}

/** Các bước con của saga cấp phát (QT-01 bước 4), máy chủ ghi tên bước vào `Job.message`. */
export const PROVISION_STEPS = [
  'Tạo vùng lưu trữ dữ liệu của đơn vị',
  'Tạo khoá mã hoá dữ liệu',
  'Tạo không gian đăng nhập của đơn vị',
  'Đăng ký tên miền',
  'Cấp chứng chỉ TLS',
  'Cài đặt bộ quy định đánh giá và bộ giao diện của đơn vị',
  'Tạo lịch nghỉ mặc định',
] as const;

/** Sáu bước của trình hướng dẫn (SCR-0102 vùng 1). */
export const ONBOARDING_STEPS = [
  'Thông tin đơn vị',
  'Cụm máy chủ và mức cô lập',
  'Bộ quy định đánh giá, bộ giao diện',
  'Đăng nhập',
  'Tổ chức, nhân sự',
  'Kiểm tra, kích hoạt',
] as const;

/** Ngưỡng tài khoản chọn mô hình (QT-01 bước 2). */
export const POOL_MAX_ACCOUNTS = 5_000;
export const BRIDGE_MAX_ACCOUNTS = 1_000_000;

/**
 * Nhóm khách hàng thuộc diện cách ly, bắt buộc silo (BR-01-01; suy luận: hợp đồng không có cờ cách ly
 * riêng, máy chủ trả gợi ý trong `suggestedTenancyModel`).
 */
export const ISOLATED_SECTORS: readonly TenantSector[] = ['LLVT'];

/**
 * Gợi ý mô hình thuê bao theo quy mô (QT-01 bước 2) và BR-01-01: trên 1.000.000 tài khoản hoặc cần
 * cách ly thì silo; từ 5.000 thì bridge; còn lại pool. Chỉ để gợi ý khi máy chủ chưa trả
 * `suggestedTenancyModel`; máy chủ kiểm lại.
 */
export function suggestTenancyModel(accounts: number, isolationRequired: boolean): TenancyModel {
  if (isolationRequired || accounts > BRIDGE_MAX_ACCOUNTS) return 'silo';
  if (accounts >= POOL_MAX_ACCOUNTS) return 'bridge';
  return 'pool';
}

/** BR-01-01: khi gợi ý là silo thì chỉ chọn được silo. */
export function modelAllowed(model: TenancyModel, suggestion: TenancyModel): boolean {
  return suggestion !== 'silo' || model === 'silo';
}

/** Gợi ý của máy chủ nếu có, không thì tính tại chỗ. */
export function tenantSuggestion(
  t: Pick<ConfigTenant, 'suggestedTenancyModel' | 'expectedAccounts' | 'sector'>,
): TenancyModel {
  return (
    t.suggestedTenancyModel ??
    suggestTenancyModel(t.expectedAccounts ?? 0, !!t.sector && ISOLATED_SECTORS.includes(t.sector))
  );
}

/** Trạng thái còn trong quá trình khởi tạo sáu bước (dùng cho bước wizard tới được). */
export const ONBOARDING_OPEN_STATUSES: readonly TenantStatus[] = [
  'KHOI_TAO',
  'DANG_CAP_PHAT',
  'DANG_CAU_HINH',
  'SAN_SANG',
];
