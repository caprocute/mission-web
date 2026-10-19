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

/** Trạng thái phiên bản kế hoạch (`task.plan_version.status`, baseline 9.1). */
export type PlanVersionStatus = 'NHAP' | 'CHO_DUYET' | 'DA_DUYET' | 'TRA_LAI';

export const PLAN_VERSION_STATUS_LABEL: Record<PlanVersionStatus, string> = {
  NHAP: 'Nháp',
  CHO_DUYET: 'Chờ duyệt',
  DA_DUYET: 'Đã duyệt',
  TRA_LAI: 'Trả lại',
};

/** Lặp lại của dòng kế hoạch (`task.plan_item.recurrence`), thay cho hạn cố định (SCR-0402 trường 12). */
export type PlanRecurrence = 'HANG_THANG' | 'HANG_QUY';

export const PLAN_RECURRENCE_LABEL: Record<PlanRecurrence, string> = {
  HANG_THANG: 'Hằng tháng',
  HANG_QUY: 'Hằng quý',
};

/**
 * Một dòng của Bảng 1 (`task.plan_item`, TL-03). `productName` theo TL-02 SCR-0402 trường 11
 * (TL-03 chưa có cột này); `standardScore` là điểm của dòng (TL-02 gọi `score_weight`).
 */
export interface PlanItem {
  id: string;
  itemNo: number;
  title: string;
  taskGroup: TaskGroup;
  keyResultId?: string;
  /** Chủ trì duy nhất: đúng một trong hai (BR-04-02). */
  leadUnitId?: string;
  leadPersonId?: string;
  coUnitIds: string[];
  catalogItemId?: string;
  catalogItemCode?: string;
  productName: string;
  plannedQuantity: number | null;
  unit: string;
  dueDate?: string;
  recurrence?: PlanRecurrence;
  difficulty?: number;
  /** Điểm lấy từ danh mục; việc ngoài danh mục để trống, cấp duyệt chấm (BR-04-05). */
  standardScore: number | null;
  /** Dòng tương ứng ở phiên bản trước (so sánh phiên bản, SCR-0403). */
  originItemId?: string;
  changeReason?: string;
}

/** Phiên bản kế hoạch (`task.plan_version`). */
export interface PlanVersion {
  id: string;
  versionNo: number;
  status: PlanVersionStatus;
  rowVersion: number;
  isCurrent: boolean;
  changeReason?: string;
  returnReason?: string;
  reviewComment?: string;
  submittedAt?: string;
  approvedAt?: string;
  approvedByName?: string;
  /** SHA-256 nội dung các dòng, dữ liệu được ký số khi duyệt (hex). */
  contentHash?: string;
  items: PlanItem[];
}

/** Tỷ trọng nhóm của Policy Pack (`policy.policy_rule`, tham số `group_weights`; BR-04-06). */
export interface GroupWeights {
  THUONG_XUYEN: number;
  THEN_CHOT: number;
  total: number;
  /** Luồng đánh giá của đơn vị: `DANG`, `NHA_NUOC` hoặc cả hai. */
  flow: string;
  policyPack: string;
}

/** Kế hoạch tập thể của đơn vị trong một kỳ (`task.plan`), kèm phiên bản đang xem. */
export interface PlanWorkspace {
  id: string;
  code: string;
  periodId: string;
  periodLabel: string;
  periodStart: string;
  periodEnd: string;
  unitId: string;
  unitName: string;
  /** Kỳ trước để sao chép (nhãn ngắn, ví dụ "quý IV"). */
  previousPeriodLabel?: string;
  /** Hạn phê duyệt (BR-04-01) và số ngày làm việc còn lại theo lịch nghỉ (MSG-04-03, MSG-04-08). */
  approvalDeadline: string;
  workingDaysLeft: number;
  groupWeights: GroupWeights;
  /** Số lần điều chỉnh đã dùng và tối đa trong quý (BR-04-10). */
  adjustmentsUsed: number;
  adjustmentsMax: number;
  versions: Pick<PlanVersion, 'id' | 'versionNo' | 'status' | 'isCurrent'>[];
  version: PlanVersion;
}

/** Kế hoạch của đơn vị theo kỳ, để chọn kỳ trên SCR-0402. */
export interface PlanSummary {
  id: string;
  periodId: string;
  periodLabel: string;
  latestVersionNo: number;
  latestStatus: PlanVersionStatus;
}

/** Một dòng "Kiểm tra kế hoạch" (SCR-0402 vùng 4). `itemNo` có khi lỗi thuộc một dòng. */
export interface PlanCheckItem {
  rule: string;
  level: 'CHAN' | 'CANH_BAO';
  passed: boolean;
  message: string;
  itemNo?: number;
}

/** Kết quả kiểm tra tức thì của MOD-02 cho tập dòng đang soạn. */
export interface PlanCheckResult {
  items: PlanCheckItem[];
  /** Tổng điểm theo nhóm (BR-04-06). */
  groupTotals: Record<TaskGroup, number>;
  /** KR đã duyệt cùng kỳ chưa có dòng nào (BR-04-07). */
  keyResultsWithoutItems: string[];
  keyResultCount: number;
}
