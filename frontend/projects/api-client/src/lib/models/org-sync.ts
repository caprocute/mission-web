import type { components } from '../generated/org';

/**
 * Đồng bộ và đối soát nhân sự (SCR-0104, QT-01 bước 7–8) của SVC-04 org-service qua SVC-13
 * integration-gateway, theo `contracts/openapi/modules/org.yaml` (thẻ "Đồng bộ nhân sự"):
 * `org.scim_sync_log`, `integ.sync_cursor`, `integ.mapping_xref`, `integ.dead_letter`,
 * `org.sync_discrepancy`. Trường đánh dấu "chờ hợp đồng" là phần màn hình cần mà hợp đồng chưa có;
 * client đọc nếu máy chủ trả, màn hình vẫn chạy khi thiếu (wiki/14, mục Điểm lệch).
 */
type Schemas = components['schemas'];

/** `integ.connector.code`. */
export type SyncSource = Schemas['SyncSource'];

export const SYNC_SOURCE_LABEL: Record<SyncSource, string> = {
  CBCCVC: 'CBCCVC',
  DANG_VIEN: 'Đảng viên',
  SCIM: 'SCIM',
  HRM: 'HRM',
};

export const SYNC_SOURCE_TITLE: Record<SyncSource, string> = {
  CBCCVC: 'CSDL quốc gia CBCCVC (NDXP)',
  DANG_VIEN: 'CSDL đảng viên',
  SCIM: 'SCIM 2.0 từ IdP tỉnh',
  HRM: 'Phần mềm nhân sự HRM',
};

/** Một lần đồng bộ; `reason` (lý do lỗi cho MSG-01-14) chờ hợp đồng. */
export type OrgSyncRun = Schemas['OrgSyncRun'] & { reason?: string };
export type SyncRunStatus = Schemas['OrgSyncRun']['status'];

export const SYNC_RUN_STATUS_LABEL: Record<SyncRunStatus, string> = {
  DANG_CHAY: 'Đang chạy',
  THANH_CONG: 'Thành công',
  THANH_CONG_MOT_PHAN: 'Một phần',
  LOI: 'Lỗi',
};

/** Lần chạy còn thông điệp lỗi trong `integ.dead_letter` để chạy lại. */
export const hasDeadLetters = (r: Schemas['OrgSyncRun']) => (r.deadLetters ?? 0) > 0;

/** Lịch đồng bộ theo nguồn; `cron` dạng `m h * * *` khi chạy hằng ngày. */
export type OrgSyncSchedule = Schemas['OrgSyncSchedule'];

export type DiscrepancySeverity = Schemas['DiscrepancySeverity'];
export const SEVERITY_LABEL: Record<DiscrepancySeverity, string> = {
  CAO: 'Cao',
  VUA: 'Vừa',
  THAP: 'Thấp',
};

/** `org.sync_discrepancy.resolution`. */
export type DiscrepancyResolution = Schemas['DiscrepancyResolution'];
export const RESOLUTION_LABEL: Record<DiscrepancyResolution, string> = {
  NHAN_NGUON: 'Lấy theo nguồn',
  GIU_HIEN_TAI: 'Giữ dữ liệu nội bộ',
  SUA_TAY: 'Ánh xạ thủ công',
  BO_QUA: 'Bỏ qua',
};

/**
 * `org.sync_discrepancy.field` là tên cột (ví dụ hợp đồng `org_unit_id`, `job_title_id`); bốn khoá
 * còn lại suy luận theo trường 4 của SCR-0104. Khoá lạ hiện nguyên văn.
 */
export const DISCREPANCY_FIELD_LABEL: Record<string, string> = {
  job_title_id: 'Chức vụ',
  org_unit_id: 'Đơn vị',
  person_match: 'Không khớp người',
  party_membership: 'Đảng tịch',
  employment_status: 'Trạng thái',
  concurrent_assignment: 'Kiêm nhiệm',
};
/** Dòng "không khớp người" chỉ xử lý bằng ánh xạ thủ công (MSG-01-15). */
export const FIELD_PERSON_MATCH = 'person_match';

/** `suggested_action` (trường 7 SCR-0104); hợp đồng để chuỗi tự do, khoá dưới đây là suy luận. */
export const SUGGESTED_ACTION_LABEL: Record<string, string> = {
  LAY_THEO_NGUON: 'Lấy theo nguồn',
  GIU_NOI_BO: 'Giữ nội bộ',
  DOI_SOAT_THU_CONG: 'Đối soát thủ công',
  TAO_DIEU_DONG_TUONG_LAI: 'Tạo điều động tương lai',
  KET_THUC_PHAN_CONG: 'Kết thúc phân công',
};

/**
 * Một dòng lệch. `matchedBy` (khớp theo tiêu chí nào) và `effectiveFrom` (hiệu lực theo quyết định
 * nguồn, `org.assignment.valid_from`) ở vùng 4 chờ hợp đồng.
 */
export type OrgDiscrepancy = Schemas['OrgDiscrepancy'] & {
  matchedBy?: string;
  effectiveFrom?: string;
};

/** Trang lệch; `countsByField` (số đếm cho tab theo trường) chờ hợp đồng. */
export interface DiscrepancyPage {
  items: OrgDiscrepancy[];
  nextCursor: string | null;
  counts: Record<DiscrepancySeverity, number>;
  countsByField?: Record<string, number>;
}

export type BatchItemResult = components['schemas']['BatchItemResult'];

/** Kết quả xử lý hàng loạt; `assignmentsUpdated` (số {m} của MSG-01-13) chờ hợp đồng. */
export interface ResolveResult {
  results: BatchItemResult[];
  assignmentsUpdated?: number;
}

/** `m h * * *` → `HH:mm`; biểu thức khác trả `null` (màn hình hiện nguyên văn). */
export function cronToTime(cron: string): string | null {
  const m = /^(\d{1,2}) (\d{1,2}) \* \* \*$/.exec(cron.trim());
  if (!m || +m[1] > 59 || +m[2] > 23) return null;
  return `${m[2].padStart(2, '0')}:${m[1].padStart(2, '0')}`;
}

/** `HH:mm` → `m h * * *`. */
export function timeToCron(time: string): string {
  const [h, m] = time.split(':').map(Number);
  return `${m} ${h} * * *`;
}
