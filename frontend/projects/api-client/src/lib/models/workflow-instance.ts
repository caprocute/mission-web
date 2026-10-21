import type { components } from '../generated/platform';

type S = components['schemas'];

/** Một tiến trình đang chạy theo mẫu, đọc từ `flowable.wf_task_projection` (BS-01 SCR-1506). */
export type WorkflowInstanceSummary = S['WorkflowInstanceSummary'];
export type WorkflowInstancePage = S['WorkflowInstancePage'];
/** BPMN của phiên bản mẫu cùng các phần tử đã qua và đang chờ. */
export type WorkflowDiagram = S['WorkflowDiagram'];
export type WorkflowHistory = S['WorkflowHistory'];
export type WorkflowHistoryAction = S['WorkflowHistoryAction'];
export type WorkflowTaskReassign = S['WorkflowTaskReassign'];
export type WorkflowTaskExtend = S['WorkflowTaskExtend'];
export type WorkflowInboxItem = S['WorkflowInboxItem'];

export interface WorkflowInstanceQuery {
  template?: string;
  stuck?: boolean;
  /** Chỉ quản trị nền tảng dùng (xem tổng hợp theo cell). */
  cell?: string;
  cursor?: string;
  limit?: number;
}
