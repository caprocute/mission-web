import { TaskGroup } from './task';

/** Dòng kế hoạch quý đã duyệt dùng làm nguồn giao việc (`plan.plan_item`, QT-04, BR-05-04). */
export interface PlanItemRef {
  id: string;
  planCode: string;
  version: number;
  lineNo: number;
  title: string;
  catalogItemId?: string;
  taskGroup: TaskGroup;
  keyResultId?: string;
  productName: string;
  quantity: number;
  unit: string;
  dueDate: string;
  evidenceRequired: string;
  qualityCriteria: string;
}
