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
export type TenancyModel = ConfigSchemas['TenancyModel'];

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

/** Trạng thái cell (`registry.cell.status`). */
export const CELL_STATUS_LABEL: Record<PlatformSchemas['CellStatus'], string> = {
  HOAT_DONG: 'Hoạt động',
  BAO_TRI: 'Bảo trì',
  NGUNG: 'Ngừng',
};

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
