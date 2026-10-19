import { CellLoad, TenancyModel, TenantStatus } from './tenant';

/** Luồng đánh giá tenant áp dụng (`registry.tenant.flows`, SCR-0102 trường 3). */
export type TenantFlow = 'DANG' | 'NHA_NUOC';

export const TENANT_FLOW_LABEL: Record<TenantFlow, string> = {
  DANG: 'Đảng',
  NHA_NUOC: 'Nhà nước',
};

/** Khoá mã hoá KEK (SCR-0102 trường 9): riêng theo tenant trong HSM hoặc dùng chung của cell. */
export type KekMode = 'RIENG' | 'DUNG_CHUNG';

export const KEK_MODE_LABEL: Record<KekMode, string> = {
  RIENG: 'Khoá riêng theo tenant trong HSM',
  DUNG_CHUNG: 'Khoá dùng chung của cell',
};

/** Loại IdP (`registry.tenant_idp`, SCR-0102 trường 12). */
export type IdpKind = 'KEYCLOAK_ORG' | 'OIDC' | 'SAML' | 'LDAP' | 'VNEID';

export const IDP_KIND_LABEL: Record<IdpKind, string> = {
  KEYCLOAK_ORG: 'Organization trong Keycloak của cell',
  OIDC: 'OIDC',
  SAML: 'SAML',
  LDAP: 'LDAP liên kết',
  VNEID: 'VNeID',
};

export interface TenantIdp {
  kind: IdpKind;
  name: string;
  /** Địa chỉ issuer, metadata hoặc máy chủ LDAP; Organization và VNeID không cần. */
  url?: string;
  status: 'CHUA_KIEM_TRA' | 'DAT' | 'LOI';
  message?: string;
}

/** Nguồn nhân sự (`integ.connector`, SCR-0102 trường 13). */
export type HrSource = 'CBCCVC' | 'DANG_VIEN' | 'SCIM';

export const HR_SOURCE_LABEL: Record<HrSource, string> = {
  CBCCVC: 'CSDL CBCCVC qua NDXP',
  DANG_VIEN: 'CSDL đảng viên',
  SCIM: 'SCIM 2.0 từ IdP',
};

/**
 * Hồ sơ khởi tạo tenant mà trình hướng dẫn SCR-0102 đọc và lưu nháp
 * (`GET /api/v1/config/tenants/{id}/onboarding`, lưu nháp `PATCH /api/v1/config/tenants/{id}` kèm `If-Match`).
 */
export interface TenantOnboarding {
  id: string;
  /** Trường 1, `ONB-yyyy-nnnn`, máy chủ sinh. */
  onboardingCode: string;
  name: string;
  /** `registry.tenant.code`, `^[a-z0-9_]+$`. */
  code: string;
  sector: string;
  /** Trường 2. */
  contractRef: string;
  contractSignedOn?: string;
  /** Trường 3, ít nhất một. */
  flows: TenantFlow[];
  /** Trường 4: số tài khoản và số đơn vị dự kiến. */
  expectedAccounts: number;
  expectedUnits: number;
  /** Yêu cầu cách ly (khối Đảng TW, LLVT): bắt buộc silo (BR-01-01). */
  isolationRequired: boolean;
  adminName: string;
  adminEmail: string;
  status: TenantStatus;
  /** Bước của trình hướng dẫn đã tới (1–6). */
  step: number;
  startedAt: string;
  /** Trường 5–9 (bước 2). */
  tenancyModel?: TenancyModel;
  cellCode?: string;
  domain?: string;
  dataRegion?: string;
  kekMode?: KekMode;
  /** Trường 10: mã phiên bản Policy Pack theo luồng (BR-01-03, BR-01-04). */
  policyPacks: Partial<Record<TenantFlow, string>>;
  /** Trường 11. */
  uiPackId?: string;
  /** Trường 12 (bước 4). */
  idps: TenantIdp[];
  /** Trường 13 (bước 5). */
  hrSources: HrSource[];
  /** `Location` của saga cấp phát đang chạy (khi `DANG_CAP_PHAT`). */
  provisionLocation?: string;
  /** Số phút saga đã chạy khi hoàn tất (MSG-01-06). */
  provisionedMinutes?: number;
  /** Lỗi saga gần nhất đã bù trừ (MSG-01-07). */
  provisionError?: { step: string; reason: string };
  rowVersion: number;
}

/** Nội dung khai báo ở bước 1 (`POST /api/v1/config/tenants`). */
export type TenantOnboardingInput = Pick<
  TenantOnboarding,
  | 'name'
  | 'code'
  | 'sector'
  | 'contractRef'
  | 'contractSignedOn'
  | 'flows'
  | 'expectedAccounts'
  | 'expectedUnits'
  | 'isolationRequired'
  | 'adminName'
  | 'adminEmail'
>;

/** Nội dung lưu nháp (`PATCH`): chỉ gửi phần đã đổi. */
export type TenantOnboardingPatch = Partial<
  Omit<
    TenantOnboarding,
    'id' | 'onboardingCode' | 'status' | 'startedAt' | 'rowVersion' | 'provisionLocation'
  >
>;

/** Một cell ứng viên ở bước 2 (vùng 3): tải sau khi thêm tenant (BR-01-02). */
export interface CellOption extends CellLoad {
  standbyDc?: string;
  softwareVersion: string;
  /** Tỉ lệ sử dụng sau khi thêm số tài khoản dự kiến. */
  loadAfter: number;
  /** Chọn được: cell hoạt động và `loadAfter` < 85%. */
  eligible: boolean;
}

/** Gói chính sách đã phát hành cho một luồng (`policy.pack_version`). */
export interface PolicyPackOption {
  id: string;
  name: string;
  version: string;
  flow: TenantFlow;
  status: 'DA_PHAT_HANH';
  /** Tỉ lệ đạt test case vàng (0–100); dưới 100 thì không gắn được (BR-01-03, MSG-01-05). */
  goldenPassRate: number;
}

export interface UiPackOption {
  id: string;
  name: string;
  version: string;
}

/** Một mục kiểm tra trước khi tiếp tục hoặc kích hoạt (SCR-0102 vùng 4, bước 6). */
export interface OnboardingCheck {
  code: string;
  label: string;
  result: 'DAT' | 'CANH_BAO' | 'LOI';
  detail?: string;
}

export const CHECK_RESULT_LABEL: Record<OnboardingCheck['result'], string> = {
  DAT: 'Đạt',
  CANH_BAO: 'Cảnh báo',
  LOI: 'Chưa đạt',
};

/** Các bước con của saga cấp phát (QT-01 bước 4), máy chủ ghi tên bước vào `Job.message`. */
export const PROVISION_STEPS = [
  'Tạo CSDL hoặc đăng ký tenant_id',
  'Tạo khoá KEK trong HSM',
  'Tạo Organization trong Keycloak',
  'Đăng ký tên miền',
  'Cấp chứng chỉ TLS',
  'Sao chép Policy Pack và UI Pack xuống cell',
  'Tạo lịch nghỉ mặc định',
] as const;

/** Sáu bước của trình hướng dẫn (SCR-0102 vùng 1). */
export const ONBOARDING_STEPS = [
  'Thông tin tenant',
  'Cell và mô hình',
  'Policy Pack, UI Pack',
  'Định danh',
  'Tổ chức, nhân sự',
  'Kiểm tra, kích hoạt',
] as const;

/** Ngưỡng tài khoản chọn mô hình (QT-01 bước 2). */
export const POOL_MAX_ACCOUNTS = 5_000;
export const BRIDGE_MAX_ACCOUNTS = 1_000_000;

/**
 * Gợi ý mô hình thuê bao theo quy mô (QT-01 bước 2) và BR-01-01: trên 1.000.000 tài khoản hoặc cần
 * cách ly thì silo; từ 5.000 thì bridge; còn lại pool. Chỉ để gợi ý, máy chủ kiểm lại.
 */
export function suggestTenancyModel(accounts: number, isolationRequired: boolean): TenancyModel {
  if (isolationRequired || accounts > BRIDGE_MAX_ACCOUNTS) return 'silo';
  if (accounts >= POOL_MAX_ACCOUNTS) return 'bridge';
  return 'pool';
}

/** BR-01-01: mô hình bị chặn với tenant thuộc diện bắt buộc silo. */
export function modelAllowed(
  model: TenancyModel,
  accounts: number,
  isolationRequired: boolean,
): boolean {
  return suggestTenancyModel(accounts, isolationRequired) !== 'silo' || model === 'silo';
}
