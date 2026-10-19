import { PeriodRef, TaskGroup, TaskSource, TaskStatus } from './task';

/** `task.task.priority` (TL-02 SCR-0501 trường 8). */
export type TaskPriority = 'CAO' | 'TRUNG_BINH' | 'THAP';

export const TASK_PRIORITY_LABEL: Record<TaskPriority, string> = {
  CAO: 'Cao',
  TRUNG_BINH: 'Trung bình',
  THAP: 'Thấp',
};

/**
 * Nhãn nguồn nhiệm vụ trên SCR-0501 trường 2. TL-02 gọi nguồn ngoài kế hoạch là `PHAT_SINH`,
 * ràng buộc `ck_task__source` của TL-03 dùng `DOT_XUAT`; mã giữ `DOT_XUAT` (xem wiki/14).
 */
export const TASK_SOURCE_LABEL: Record<TaskSource, string> = {
  KE_HOACH: 'Từ kế hoạch quý',
  VAN_BAN: 'Từ văn bản chỉ đạo',
  DOT_XUAT: 'Phát sinh',
  DANG_KY: 'Cán bộ đăng ký',
};

/** Mốc kiểm soát (`task.task.milestones`, JSON). */
export interface TaskMilestone {
  date: string;
  content: string;
}

/** Người hoặc đơn vị phối hợp (`task.task_assignee`, `role` = `PHOI_HOP`). */
export interface AssigneeRef {
  kind: 'PERSON' | 'UNIT';
  id: string;
  name: string;
}

/**
 * Nhiệm vụ đang soạn để giao (SCR-0501), tên trường theo `task.task` của TL-03.
 * `id`, `code`, `rowVersion` chỉ có sau khi lưu nháp (`NHAP`).
 */
export interface TaskDraft {
  id?: string;
  code?: string;
  rowVersion?: number;
  status: TaskStatus;
  periodId: string;
  unitName: string;
  assignerId: string;
  assignerName: string;
  source: TaskSource;
  planItemId?: string;
  catalogItemId?: string;
  title: string;
  taskGroup: TaskGroup;
  keyResultId?: string;
  priority: TaskPriority;
  leadPersonId?: string;
  coAssignees: AssigneeRef[];
  dueDate?: string;
  acceptorId?: string;
  milestones: TaskMilestone[];
  productName: string;
  quantity: number | null;
  unit: string;
  evidenceRequired: string;
  qualityCriteria: string;
  /** Văn bản căn cứ khi nguồn là văn bản (`task.task_source_doc`). */
  sourceDoc?: { number: string; title: string };
  /** Căn cứ của nhiệm vụ phát sinh ngoài kế hoạch (BR-05-09). */
  basis?: string;
}

/** Khung soạn nhiệm vụ mới: kỳ, đơn vị, người giao do máy chủ điền theo ngữ cảnh đăng nhập. */
export interface TaskDraftTemplate {
  period: PeriodRef;
  draft: TaskDraft;
}

/** Một dòng của "Kiểm tra trước khi giao" (QT-05 bước 5). */
export interface AssignCheckItem {
  /** Mã quy tắc, ví dụ `BR-05-01`. */
  rule: string;
  /** `CHAN`: không giao được; `CANH_BAO`: giao được sau khi xử lý (ghi lý do). */
  level: 'CHAN' | 'CANH_BAO';
  passed: boolean;
  message: string;
}

/** Nhiệm vụ gần giống (BR-05-06, MSG-05-03). */
export interface SimilarTask {
  id: string;
  code: string;
  title: string;
  status: TaskStatus;
  similarity: number;
  unitName: string;
  leadName: string;
  dueDate: string;
  progress: number;
}

/** Khối lượng việc của người chủ trì theo tuần (BR-05-11, MSG-05-04). */
export interface PersonWorkload {
  personId: string;
  personName: string;
  leadCount: number;
  coCount: number;
  weeklyThreshold: number;
  weeks: { label: string; count: number }[];
}

/** Kết quả kiểm tra tức thì của MOD-02 cho bản đang soạn. */
export interface AssignCheckResult {
  items: AssignCheckItem[];
  similar: SimilarTask[];
  /** Số ngày làm việc từ hôm nay tới hạn, theo lịch nghỉ của tenant. */
  workingDays?: number;
  workload?: PersonWorkload;
}

/** Thân `POST /api/v1/tasks/{id}/assign`. */
export interface AssignRequest {
  /** Lý do vẫn tạo mới khi có nhiệm vụ gần giống (BR-05-06). */
  similarReason?: string;
}

export interface AssignResult {
  id: string;
  code: string;
  status: TaskStatus;
  leadName: string;
}

/** Thân `POST /api/v1/tasks/bulk-assign`: một nhiệm vụ cho mỗi đơn vị nhận. */
export interface BulkAssignRequest {
  draft: TaskDraft;
  unitIds: string[];
}
