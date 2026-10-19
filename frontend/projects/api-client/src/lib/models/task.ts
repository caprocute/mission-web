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

export interface TaskDetail extends TaskSummary {
  description: string;
  sourceDoc?: { number: string; title: string };
  coAssignees: { personId: string; name: string; role: AssigneeRole }[];
  progressLog: { at: string; percent: number; note: string; by: string }[];
  blockers: { id: string; content: string; resolved: boolean }[];
}

export interface ProgressUpdate {
  percent: number;
  note: string;
  blocker?: string;
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
