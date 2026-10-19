import type { components as ConfigComponents } from '../generated/config';
import type { components as PlatformComponents } from '../generated/platform';

type ConfigSchemas = ConfigComponents['schemas'];
type PlatformSchemas = PlatformComponents['schemas'];

/**
 * Trạng thái vòng đời tenant (`registry.tenant.status`, TL-02a QT-01 Hình 6.6, TL-03 thêm
 * `DANG_CHUYEN_CELL`). Kiểu tenant của SCR-0101 là bí danh lược đồ `config.yaml` và `platform.yaml`.
 */
export type TenantStatus = ConfigSchemas['TenantStatus'];

export const TENANT_STATUS_LABEL: Record<TenantStatus, string> = {
  KHOI_TAO: 'Khởi tạo',
  DANG_CAP_PHAT: 'Đang cấp phát',
  DANG_CAU_HINH: 'Đang cấu hình',
  SAN_SANG: 'Chờ kích hoạt',
  DANG_VAN_HANH: 'Đang vận hành',
  TAM_NGUNG: 'Tạm ngừng',
  DANG_CHUYEN_CELL: 'Đang chuyển cell',
  DA_CHAM_DUT: 'Đã chấm dứt',
};

/** Các trạng thái của quá trình khởi tạo sáu bước (SCR-0102), dùng cho thẻ "Đang khởi tạo" và MSG-01-02. */
export const TENANT_ONBOARDING_STATUSES: readonly TenantStatus[] = [
  'KHOI_TAO',
  'DANG_CAP_PHAT',
  'DANG_CAU_HINH',
];

/** Mô hình thuê bao (`registry.tenant_cell.tenancy_model`, SCR-0101 trường 4, BR-01-01). */
export type TenancyModel = ConfigSchemas['TenancyModel'];

export const TENANCY_MODELS: readonly TenancyModel[] = ['silo', 'bridge', 'pool'];

/** Một dòng của bảng tenant SCR-0101 (vùng 2), trường 1–8 (`ConfigTenantRow`). */
export type TenantRow = ConfigSchemas['ConfigTenantRow'];

/**
 * Tải một cell (SCR-0101 trường 9, `CellLoad` của `config.yaml`): `loadPercent` và `overThreshold`
 * (vượt 85%, MSG-01-01, BR-01-02) do máy chủ tính.
 */
export type CellLoad = ConfigSchemas['CellLoad'];

/** Bốn chỉ số đầu trang và tải theo cell, trả kèm danh sách (`ConfigTenantSummary`). */
export type TenantStats = ConfigSchemas['ConfigTenantSummary'];

/** Một trang tenant; `summary` là chỉ số toàn vùng điều phối, không phụ thuộc bộ lọc (suy luận). */
export type TenantPage = ConfigSchemas['ConfigTenantPage'];

/** Bộ lọc và định dạng của tác vụ xuất danh sách; `format` có mặc định `XLSX` nên không bắt buộc gửi. */
export type TenantExportRequest = Partial<ConfigSchemas['ConfigTenantExportRequest']>;

/** Chi tiết tenant và cấu hình khởi tạo (`GET /config/tenants/{id}`, `ConfigTenant`). */
export type ConfigTenant = ConfigSchemas['ConfigTenant'];

/** Bản ghi danh bạ tenant (`GET /registry/tenants/{id}`): cell hiện hành và các tên miền. */
export type RegistryTenant = PlatformSchemas['RegistryTenant'];
export type TenantDomainKind = PlatformSchemas['DomainKind'];

/**
 * Cell ứng viên của trình khởi tạo SCR-0102 (WEB-017). Kiểu này chưa đối chiếu hợp đồng: TL-02a cần
 * thêm `name`, `primaryDc`, `status`, `tenantCount` mà `CellLoad` của `config.yaml` không có; khi
 * đối chiếu WEB-017 sẽ chuyển sang `RegistryCell` của `platform.yaml`.
 */
export interface CellInfo {
  code: string;
  name: string;
  primaryDc: string;
  tenancyModel: TenancyModel;
  status: 'HOAT_DONG' | 'BAO_TRI' | 'NGUNG';
  accountCount: number;
  capacityAccounts: number;
  tenantCount: number;
}

/** Ngưỡng tải cell: vượt thì đề nghị mở cell mới (MSG-01-01) và không thêm tenant (BR-01-02). */
export const CELL_LOAD_THRESHOLD = 0.85;

export const TENANT_SECTOR_LABEL: Record<string, string> = {
  DANG: 'Khối Đảng',
  CHINH_QUYEN: 'Chính quyền',
  SU_NGHIEP: 'Sự nghiệp',
  TRUONG_HOC: 'Trường học',
  BENH_VIEN: 'Bệnh viện',
  LLVT: 'Lực lượng vũ trang',
  DOANH_NGHIEP: 'Doanh nghiệp',
};
