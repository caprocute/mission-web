import { CursorPage } from '../paging';

/** Trạng thái chuẩn của `task.task.status` (baseline mục 9.1). */
export type TaskStatus =
  'NHAP' | 'DA_GIAO' | 'DANG_THUC_HIEN' | 'CHO_NGHIEM_THU' | 'HOAN_THANH' | 'TAM_DUNG' | 'HUY';

export const TASK_STATUS_LABEL: Record<TaskStatus, string> = {
  NHAP: 'Nháp',
  DA_GIAO: 'Đã giao',
  DANG_THUC_HIEN: 'Đang thực hiện',
  CHO_NGHIEM_THU: 'Chờ nghiệm thu',
  HOAN_THANH: 'Hoàn thành',
  TAM_DUNG: 'Tạm dừng',
  HUY: 'Huỷ',
};

export type AssigneeRole = 'CHU_TRI' | 'PHOI_HOP';

/** `task.task.task_group` (TL-03): một nhiệm vụ chỉ tính ở một nhóm. */
export type TaskGroup = 'THUONG_XUYEN' | 'THEN_CHOT';

export const TASK_GROUP_LABEL: Record<TaskGroup, string> = {
  THUONG_XUYEN: 'Thường xuyên',
  THEN_CHOT: 'Then chốt',
};

/** `task.task.source` (TL-03): kế hoạch, văn bản, đăng ký, đột xuất. */
export type TaskSource = 'KE_HOACH' | 'VAN_BAN' | 'DANG_KY' | 'DOT_XUAT';

/** Bản tóm tắt nhiệm vụ cho danh sách "Việc của tôi" (SCR-0503) và widget trang chủ (SCR-0002). */
export interface TaskSummary {
  id: string;
  code: string;
  title: string;
  status: TaskStatus;
  /** Cờ `task.task.is_overdue`, máy chủ đặt hằng ngày (QT-06 bước 8). */
  overdue: boolean;
  dueDate: string;
  /** Phần trăm 0–100; máy chủ lưu `progress_pct` dạng 0–1. */
  progress: number;
  role: AssigneeRole;
  assignerName: string;
  unitName: string;
  periodId: string;
  /** ETag = row_version, gửi lại trong `If-Match` khi cập nhật. */
  rowVersion: number;
  taskGroup: TaskGroup;
  source: TaskSource;
  keyResultId?: string;
  keyResultTitle?: string;
  plannedQuantity: number;
  doneQuantity: number;
  unit: string;
  /** Số điểm nghẽn chưa giải quyết của nhiệm vụ. */
  openBlockerCount: number;
  /**
   * Số ngày làm việc còn lại tới hạn (âm khi đã quá hạn), máy chủ tính theo `config.holiday_calendar`
   * để trình duyệt không phải tải lịch nghỉ (SCR-0503 trường 6 "Tình trạng").
   */
  workingDaysLeft: number;
}

/** Kênh cập nhật tiến độ (`task.task_progress.channel`). */
export type ProgressChannel = 'WEB' | 'MOBILE' | 'TICH_HOP';

/** Một dòng nhật ký tiến độ (`task.task_progress`), chỉ ghi thêm. */
export interface ProgressLogEntry {
  at: string;
  by: string;
  /** Vai trò của người ghi; người phối hợp chỉ ghi nhật ký, không đổi phần trăm (BR-06-03). */
  byRole?: AssigneeRole;
  channel?: ProgressChannel;
  /** Phần trăm trước lần cập nhật; bằng `percent` khi chỉ ghi nhật ký. */
  fromPercent?: number;
  percent: number;
  note: string;
}

/** `task.task_blocker.status`. */
export type BlockerStatus = 'MO' | 'DANG_XU_LY' | 'DA_GIAI_QUYET' | 'DONG';
/** `task.task_blocker.cause_category`. */
export type BlockerCauseCategory = 'NGUON_LUC' | 'PHOI_HOP' | 'THU_TUC' | 'KHACH_QUAN' | 'KHAC';
/** `task.task_blocker.impact_level`. */
export type ImpactLevel = 'CAO' | 'TRUNG_BINH' | 'THAP';
/** Nguyên nhân khách quan hoặc chủ quan (QT-06 bước 3, 5; `cause_claimed`, `cause_decided`). */
export type CauseKind = 'KHACH_QUAN' | 'CHU_QUAN';

export const BLOCKER_STATUS_LABEL: Record<BlockerStatus, string> = {
  MO: 'Đang mở',
  DANG_XU_LY: 'Đang xử lý',
  DA_GIAI_QUYET: 'Đã giải quyết',
  DONG: 'Đã đóng',
};
export const BLOCKER_CAUSE_LABEL: Record<BlockerCauseCategory, string> = {
  NGUON_LUC: 'Nguồn lực',
  PHOI_HOP: 'Phối hợp',
  THU_TUC: 'Thủ tục',
  KHACH_QUAN: 'Yếu tố bên ngoài',
  KHAC: 'Khác',
};
export const IMPACT_LABEL: Record<ImpactLevel, string> = {
  CAO: 'Cao',
  TRUNG_BINH: 'Vừa',
  THAP: 'Thấp',
};
export const CAUSE_KIND_LABEL: Record<CauseKind, string> = {
  KHACH_QUAN: 'Khách quan',
  CHU_QUAN: 'Chủ quan',
};

/** Điểm nghẽn (`task.task_blocker`). */
export interface TaskBlocker {
  id: string;
  /** Mã hiển thị, ví dụ `BN-0157-01`. */
  code: string;
  content: string;
  causeCategory: BlockerCauseCategory;
  causeClaimed: CauseKind;
  impactLevel: ImpactLevel;
  handlerName?: string;
  resolveBy?: string;
  status: BlockerStatus;
  createdAt: string;
}

/** Báo điểm nghẽn: `/api/v1/tasks/{id}/blockers` (POST), EVT-21. */
export interface BlockerReport {
  content: string;
  causeCategory: BlockerCauseCategory;
  causeClaimed: CauseKind;
  impactLevel: ImpactLevel;
  /** Hạn mong muốn được xử lý (tuỳ chọn). */
  resolveBy?: string;
}

/**
 * Loại đề nghị theo TL-02 SCR-0602 trường 1. TL-03 ghi bộ giá trị khác
 * (`DIEU_CHINH_KHOI_LUONG`, `DIEU_CHINH_NOI_DUNG`, không có `TAM_DUNG`); xem wiki/14.
 */
export type ChangeRequestType = 'GIA_HAN' | 'DOI_SO_LUONG' | 'DOI_SAN_PHAM' | 'TAM_DUNG' | 'HUY';
export type ChangeRequestStatus = 'NHAP' | 'CHO_DUYET' | 'DA_DUYET' | 'TU_CHOI' | 'DA_RUT';

export const CHANGE_REQUEST_TYPE_LABEL: Record<ChangeRequestType, string> = {
  GIA_HAN: 'Gia hạn',
  DOI_SO_LUONG: 'Điều chỉnh số lượng',
  DOI_SAN_PHAM: 'Điều chỉnh sản phẩm',
  TAM_DUNG: 'Tạm dừng',
  HUY: 'Huỷ nhiệm vụ',
};
export const CHANGE_REQUEST_STATUS_LABEL: Record<ChangeRequestStatus, string> = {
  NHAP: 'Nháp',
  CHO_DUYET: 'Chờ duyệt',
  DA_DUYET: 'Đã duyệt',
  TU_CHOI: 'Từ chối',
  DA_RUT: 'Đã rút',
};

/** Đề nghị gia hạn, điều chỉnh (`task.task_change_request`). */
export interface ChangeRequest {
  id: string;
  type: ChangeRequestType;
  status: ChangeRequestStatus;
  newDueDate?: string;
  newQuantity?: number;
  newProduct?: string;
  causeClaimed: CauseKind;
  blockerId?: string;
  reason: string;
  approverName: string;
  submittedAt: string;
  /** Đề nghị vượt ngưỡng BR-06-06 đã chuyển cấp trên duyệt (MSG-06-07). */
  escalated?: boolean;
  decidedAt?: string;
  causeDecided?: CauseKind;
  decisionNote?: string;
}

/** Nội dung gửi đề nghị: `/api/v1/tasks/{id}/change-requests` (POST, Idempotency-Key). */
export interface ChangeRequestDraft {
  type: ChangeRequestType;
  newDueDate?: string;
  newQuantity?: number;
  newProduct?: string;
  causeClaimed: CauseKind;
  blockerId?: string;
  reason: string;
}

/** Xem trước hệ quả với hệ số C (MSG-06-06): `/api/v1/tasks/{id}/change-requests/preview`. */
export interface ChangeRequestPreview {
  /** Số ngày làm việc tăng thêm theo lịch nghỉ (SCR-0602 trường 3). */
  extraWorkingDays: number;
  objective: { scoreDueDate: string; c: number };
  subjective: { scoreDueDate: string; delayDays: number; c: number };
  /** Vượt ngưỡng BR-06-06: sẽ chuyển cấp trên duyệt (MSG-06-07). */
  overThreshold: boolean;
  escalateToName?: string;
  /** Gửi sau hạn thực hiện (BR-06-07, MSG-06-08). */
  lateSubmission: boolean;
  /** Huỷ không được khi đã có sản phẩm được nghiệm thu (BR-06-09, MSG-06-09). */
  cancelBlocked: boolean;
}

/** Một lần chuyển trạng thái (`task.task_state_log`). */
export interface StateLogEntry {
  at: string;
  from: TaskStatus | null;
  to: TaskStatus;
  transition: string;
  actorName: string;
  reason?: string;
}

/** Dự báo trễ hạn do SVC-15 tính (`task.task.delay_risk`, SCR-0601 trường 6). */
export interface DelayRisk {
  level: 'THAP' | 'VUA' | 'CAO';
  /** Số ngày làm việc ước tính còn cần theo nhịp hiện tại (MSG-06-02). */
  neededWorkingDays: number;
  explanation: string;
}

export interface TaskDetail extends TaskSummary {
  description: string;
  sourceDoc?: { number: string; title: string };
  /** Mô tả nguồn nhiệm vụ, ví dụ "Kế hoạch Q4 phiên bản 2 · dòng 1". */
  sourceRef?: string;
  catalogItemCode?: string;
  difficulty?: number;
  standardScore?: number;
  assignedAt?: string;
  acceptorName: string;
  /** Hạn tính hệ số tiến độ C, khác `dueDate` là hạn thực hiện (BR-06-04, BR-06-05). */
  scoreDueDate: string;
  /** Sản phẩm đã xác nhận / chờ nghiệm thu / phải nộp (SCR-0601 trường 5). */
  products: { confirmed: number; pendingReview: number; required: number };
  delayRisk?: DelayRisk;
  extensionsUsed: number;
  extensionsMax: number;
  coAssignees: { personId: string; name: string; role: AssigneeRole }[];
  progressLog: ProgressLogEntry[];
  blockers: TaskBlocker[];
  changeRequests: ChangeRequest[];
  stateLog: StateLogEntry[];
}

export interface ProgressUpdate {
  percent: number;
  note: string;
}

/** Bộ lọc nhanh của SCR-0503 trường 1. */
export type MyTasksQuickFilter = 'TAT_CA' | 'CHAM' | 'SAP_HAN' | 'CHO_NGHIEM_THU' | 'HOAN_THANH';

export const MY_TASKS_QUICK_FILTER_LABEL: Record<MyTasksQuickFilter, string> = {
  TAT_CA: 'Tất cả',
  CHAM: 'Chậm',
  SAP_HAN: 'Sắp hạn',
  CHO_NGHIEM_THU: 'Chờ nghiệm thu',
  HOAN_THANH: 'Hoàn thành',
};

/** Kỳ đánh giá đang xem (`task.period_state`). */
export interface PeriodRef {
  id: string;
  label: string;
  startDate: string;
  endDate: string;
}

/**
 * Trang "Việc của tôi": ngoài phân trang con trỏ còn có số đếm cho từng bộ lọc nhanh
 * (tính trên cùng điều kiện vai trò, nhóm, KR, từ khoá) và danh sách KR để lọc.
 */
export interface MyTasksPage extends CursorPage<TaskSummary> {
  period: PeriodRef;
  counts: Record<MyTasksQuickFilter, number>;
  keyResults: { id: string; title: string }[];
}

/** Một dòng của "Cập nhật tiến độ hàng loạt" (`/api/v1/tasks/progress-batch`). */
export interface ProgressBatchItem {
  taskId: string;
  rowVersion: number;
  percent: number;
  note: string;
}

/** Kết quả từng dòng: máy chủ xử lý độc lập, dòng lỗi không làm hỏng dòng khác. */
export interface ProgressBatchResult {
  taskId: string;
  status: number;
  task?: TaskSummary;
  problem?: { title: string; code?: string };
}

/** Đăng ký nhiệm vụ từ danh mục (QT-05 bước 1c, SCR-0503 trường 8, 9). */
export interface TaskRegistration {
  catalogItemId: string;
  quantity: number;
  proposedDueDate: string;
  note?: string;
}
