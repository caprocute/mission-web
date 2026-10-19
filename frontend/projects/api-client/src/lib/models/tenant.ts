import { CursorPage } from '../paging';

/**
 * Trạng thái vòng đời tenant (`registry.tenant.status`, TL-02a QT-01 Hình 6.6, TL-03 thêm
 * `DANG_CHUYEN_CELL`).
 */
export type TenantStatus =
  | 'KHOI_TAO'
  | 'DANG_CAP_PHAT'
  | 'DANG_CAU_HINH'
  | 'SAN_SANG'
  | 'DANG_VAN_HANH'
  | 'TAM_NGUNG'
  | 'DANG_CHUYEN_CELL'
  | 'DA_CHAM_DUT';

export const TENANT_STATUS_LABEL: Record<TenantStatus, string> = {
  KHOI_TAO: 'Khởi tạo',
  DANG_CAP_PHAT: 'Đang cấp phát',
  DANG_CAU_HINH: 'Đang cấu hình',
  SAN_SANG: 'Chờ kích hoạt',
  DANG_VAN_HANH: 'Đang vận hành',
  TAM_NGUNG: 'Tạm ngừng',
  DANG_CHUYEN_CELL: 'Đang chuyển cụm máy chủ',
  DA_CHAM_DUT: 'Đã chấm dứt',
};

/** Các trạng thái của quá trình khởi tạo sáu bước (SCR-0102), dùng cho thẻ "Đang khởi tạo" và MSG-01-02. */
export const TENANT_ONBOARDING_STATUSES: readonly TenantStatus[] = [
  'KHOI_TAO',
  'DANG_CAP_PHAT',
  'DANG_CAU_HINH',
];

/** Mô hình thuê bao (`registry.tenant_cell.tenancy_model`, SCR-0101 trường 4, BR-01-01). */
export type TenancyModel = 'silo' | 'bridge' | 'pool';

export const TENANCY_MODELS: readonly TenancyModel[] = ['silo', 'bridge', 'pool'];

/** Tên hiển thị của mô hình thuê bao (wiki/16, bảng từ thay thế): mức cô lập dữ liệu. */
export const TENANCY_MODEL_LABEL: Record<TenancyModel, string> = {
  silo: 'Cụm riêng',
  bridge: 'CSDL riêng',
  pool: 'Dùng chung',
};

/** Trung tâm dữ liệu, vùng lưu dữ liệu (`registry.cell.primary_dc`, `dataRegion`). */
export const DATA_CENTER_LABEL: Record<string, string> = {
  HOALAC: 'Hoà Lạc',
  PHIANAM: 'Phía Nam',
  TAICHO: 'Tại chỗ',
};

export const CELL_STATUS_LABEL: Record<CellLoad['status'], string> = {
  HOAT_DONG: 'Hoạt động',
  BAO_TRI: 'Bảo trì',
  NGUNG: 'Ngừng',
};

/** Một dòng của bảng tenant SCR-0101 (vùng 2), trường 1–8. */
export interface TenantSummary {
  id: string;
  /** `registry.tenant.code`, mã ngắn dùng trong URN. */
  code: string;
  /** Trường 1, `registry.tenant.name`. */
  name: string;
  /** Trường 2, tên miền chính (`registry.tenant_domain` loại `PRIMARY`). */
  domain: string;
  /** Trường 3, mã cell hiện tại (`registry.cell.code` qua `registry.tenant_cell`). */
  cellCode: string;
  /** Trường 4. */
  tenancyModel: TenancyModel;
  /** Trường 5, `registry.tenant.account_count`, đồng bộ mỗi giờ từ cell. */
  accountCount: number;
  /** Trường 6, tên và phiên bản các Policy Pack đang gắn (một gói mỗi luồng, BR-01-04). */
  policyPacks: string[];
  /** Trường 7, tên và phiên bản UI Pack. */
  uiPack: string;
  /** Trường 8. */
  status: TenantStatus;
  /** Bước hiện tại của quá trình khởi tạo sáu bước khi tenant chưa vận hành (MSG-01-02). */
  onboardingStep?: number;
  rowVersion: number;
}

/** Trang tenant có tổng số để hiển thị "Hiển thị a–b trong n". */
export interface TenantPage extends CursorPage<TenantSummary> {
  total: number;
}

/** Tải theo cell (SCR-0101 vùng 3, trường 9): số tài khoản chia dung lượng thiết kế. */
export interface CellLoad {
  /** `registry.cell.code`, ví dụ `CELL-HCM`. */
  code: string;
  name: string;
  /** `registry.cell.primary_dc`. */
  primaryDc: string;
  tenancyModel: TenancyModel;
  status: 'HOAT_DONG' | 'BAO_TRI' | 'NGUNG';
  accountCount: number;
  /** `registry.cell.capacity_accounts`. */
  capacityAccounts: number;
  tenantCount: number;
}

/** Ngưỡng tải cell: vượt thì đề nghị mở cell mới (MSG-01-01) và không thêm tenant (BR-01-02). */
export const CELL_LOAD_THRESHOLD = 0.85;

/** Một việc cần xử lý của vùng điều phối toàn cục (SCR-0101 vùng 3). */
export interface PlatformTodo {
  kind: 'CHO' | 'LICH' | 'LOI';
  tenantId?: string;
  text: string;
}

export const PLATFORM_TODO_LABEL: Record<PlatformTodo['kind'], string> = {
  CHO: 'Chờ',
  LICH: 'Lịch',
  LOI: 'Lỗi',
};

/** Bốn thẻ tổng quan đầu SCR-0101 và danh sách việc cần xử lý. */
export interface TenantOverview {
  operatingCount: number;
  /** Giới hạn thiết kế số tenant (NFR-01). */
  maxTenants: number;
  accountTotal: number;
  /** Tổng tài khoản thiết kế của nền tảng. */
  designAccounts: number;
  onboardingCount: number;
  cellCount: number;
  dataCenterCount: number;
  /** Tenant đang khởi tạo kèm bước hiện tại (MSG-01-02). */
  onboarding: { id: string; name: string; step: number }[];
  todos: PlatformTodo[];
  updatedAt: string;
}

/** Chi tiết tenant (`GET /api/v1/config/tenants/{id}`). */
export interface TenantDetail extends TenantSummary {
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

export const TENANT_SECTOR_LABEL: Record<string, string> = {
  DANG: 'Khối Đảng',
  CHINH_QUYEN: 'Chính quyền',
  SU_NGHIEP: 'Sự nghiệp',
  TRUONG_HOC: 'Trường học',
  BENH_VIEN: 'Bệnh viện',
  LLVT: 'Lực lượng vũ trang',
  DOANH_NGHIEP: 'Doanh nghiệp',
};
