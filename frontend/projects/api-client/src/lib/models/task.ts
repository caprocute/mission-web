/** Trạng thái chuẩn của `task.task.status` (baseline mục 9.1). */
export type TaskStatus = 'NHAP' | 'DA_GIAO' | 'DANG_THUC_HIEN' | 'CHO_NGHIEM_THU' | 'HOAN_THANH' | 'TAM_DUNG' | 'HUY';

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

/** Bản tóm tắt nhiệm vụ cho danh sách "Việc của tôi" (SCR-0503) và widget trang chủ (SCR-0002). */
export interface TaskSummary {
  id: string;
  code: string;
  title: string;
  status: TaskStatus;
  overdue: boolean;
  dueDate: string;
  progress: number;
  role: AssigneeRole;
  assignerName: string;
  unitName: string;
  periodId: string;
  /** ETag = row_version, gửi lại trong `If-Match` khi cập nhật. */
  rowVersion: number;
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
