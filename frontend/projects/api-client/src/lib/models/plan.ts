import { PeriodRef, TaskGroup } from './task';
import { AssigneeRef } from './task-assign';

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

/** Lặp lại của dòng kế hoạch, thay cho hạn cố định (SCR-0402 trường 12); hợp đồng chỉ có "Hằng tháng". */
export type PlanRecurrence = 'HANG_THANG';

export const PLAN_RECURRENCE_LABEL: Record<PlanRecurrence, string> = {
  HANG_THANG: 'Hằng tháng',
};

/** Kế hoạch công tác quý của một đơn vị (`task.plan`, lược đồ `Plan` của `tasks.yaml`). */
export interface Plan {
  id: string;
  code: string;
  period: PeriodRef;
  unitId: string;
  unitName: string;
  /** Phiên bản mới nhất (đang soạn, chờ duyệt hoặc đã duyệt). */
  currentVersion: PlanVersionSummary;
  /** Phiên bản `DA_DUYET` đang hiệu lực. */
  effectiveVersionNo?: number | null;
  /** Hạn phê duyệt (BR-04-01). */
  approvalDeadline?: string;
  /** Số lần điều chỉnh đã dùng và tối đa trong quý (BR-04-10). */
  adjustmentsUsed?: number;
  adjustmentsMax?: number;
  rowVersion: number;
}

/** Tóm tắt một phiên bản (`GET /plans/{id}/versions`, `GET /plans/approvals`). */
export interface PlanVersionSummary {
  planId: string;
  versionNo: number;
  status: PlanVersionStatus;
  unitName: string;
  periodLabel: string;
  itemCount: number;
  submittedAt?: string;
  submittedByName?: string;
  approvedAt?: string;
}

/**
 * Một dòng của Bảng 1 khi ghi (`task.plan_item`, SCR-0402 trường 5–14). `productName` theo TL-02
 * SCR-0402 trường 11 (TL-03 chưa có cột này); `standardScore` theo cột `standard_score` của TL-03
 * (TL-02 gọi `score_weight`). Dòng "Hằng tháng" vẫn có `dueDate` (hạn cuối kỳ).
 */
export interface PlanItemInput {
  id?: string;
  /** Dòng tương ứng ở phiên bản trước, để tính thêm, sửa, bỏ (SCR-0403). */
  originItemId?: string;
  title: string;
  taskGroup: TaskGroup;
  keyResultId?: string;
  /** Chủ trì duy nhất (BR-04-02). */
  leadAssignee: AssigneeRef;
  coAssignees?: AssigneeRef[];
  /** Bỏ trống khi là việc ngoài danh mục (chờ chấm điểm). */
  catalogItemId?: string;
  productName: string;
  quantity: number;
  unit?: string;
  dueDate: string;
  recurrence?: PlanRecurrence | null;
  /** Điểm của dòng, bắt buộc khi duyệt (BR-04-05). */
  standardScore?: number | null;
  flows?: ('DANG' | 'NHA_NUOC')[];
  /** Bắt buộc với mỗi thay đổi ở phiên bản điều chỉnh (BR-04-10). */
  changeReason?: string;
}

/** Dòng kế hoạch máy chủ trả về: thêm số thứ tự và mã mục danh mục. */
export type PlanItem = PlanItemInput & {
  lineNo: number;
  catalogItemCode?: string;
  /** Việc ngoài danh mục chờ chấm điểm. */
  pendingScore?: boolean;
};

/** Một kết quả kiểm tra kế hoạch còn vi phạm (SCR-0402 vùng 4); `lineNo` có khi lỗi thuộc một dòng. */
export interface PlanCheck {
  rule: string;
  level: 'CHAN' | 'CANH_BAO';
  lineNo?: number;
  message: string;
  msg?: string;
}

/**
 * Luật hiện trong "Kiểm tra kế hoạch" (SCR-0402 vùng 4). Hợp đồng chỉ trả vi phạm (`PlanCheck`), nên
 * luật không có vi phạm hiện "Đạt" với nhãn dưới đây (suy luận, xem wiki/14). BR-04-10 chỉ hiện ở
 * phiên bản điều chỉnh.
 */
export const PLAN_CHECK_RULES: { rule: string; level: PlanCheck['level']; passed: string }[] = [
  { rule: 'BR-04-02', level: 'CHAN', passed: 'Mỗi việc có đúng 1 chủ trì' },
  { rule: 'BR-04-03', level: 'CHAN', passed: 'Không có KPI mơ hồ' },
  { rule: 'BR-04-04', level: 'CHAN', passed: 'Mọi dòng đủ sản phẩm, số lượng, đơn vị tính và hạn' },
  { rule: 'BR-04-05', level: 'CANH_BAO', passed: 'Mọi dòng đã có điểm' },
  { rule: 'BR-04-06', level: 'CHAN', passed: 'Tổng điểm từng nhóm trong tỷ trọng' },
  { rule: 'BR-04-07', level: 'CANH_BAO', passed: 'Mọi KR đã duyệt có nhiệm vụ liên kết' },
  { rule: 'BR-04-10', level: 'CHAN', passed: 'Mọi dòng thêm, sửa có lý do điều chỉnh' },
];

/** Phiên bản kế hoạch kèm các dòng (`task.plan_version`, `task.plan_item`). */
export interface PlanVersion {
  planId: string;
  versionNo: number;
  status: PlanVersionStatus;
  period: PeriodRef;
  unitName: string;
  approvalDeadline?: string;
  /** Số ngày làm việc còn tới hạn phê duyệt theo lịch nghỉ (âm khi quá hạn; MSG-04-03, MSG-04-08). */
  workingDaysToDeadline?: number;
  /** SHA-256 nội dung các dòng, tính khi gửi duyệt; là dữ liệu được ký số khi duyệt (hex). */
  contentHash?: string | null;
  /** Ý kiến của người duyệt (khi trả lại hoặc khi duyệt). */
  reviewComment?: string | null;
  items: PlanItem[];
  /** Điểm tối đa của từng nhóm theo Policy Pack (BR-04-06). */
  groupWeights: Record<TaskGroup, number>;
  checks: PlanCheck[];
  /** Yêu cầu ký của esign-service sau "Duyệt và ký số" (SCR-0403 trường 8). */
  signRequestId?: string;
  rowVersion: number;
}

export interface PlanVersionInput {
  items: PlanItemInput[];
}

/** Loại thay đổi của một dòng khi so sánh hai phiên bản (SCR-0403 trường 2). */
export type PlanChangeType = 'THEM' | 'SUA' | 'BO' | 'KHONG_DOI';

export const PLAN_CHANGE_LABEL: Record<PlanChangeType, string> = {
  THEM: 'Thêm',
  SUA: 'Sửa',
  BO: 'Bỏ',
  KHONG_DOI: 'Không đổi',
};

/** Một dòng của bảng khác biệt, ghép theo `originItemId` (SCR-0403 vùng 2). */
export interface PlanDiffLine {
  change: PlanChangeType;
  before?: PlanItem;
  after?: PlanItem;
  /** Trường đã đổi giữa `before` và `after` (chỉ với `SUA`). */
  changedFields?: string[];
  /** Lý do thay đổi (`task.plan_item.change_reason`, BR-04-10); với dòng bỏ là lý do bỏ. */
  reason?: string;
}

/**
 * Kết quả `GET /plans/{id}/versions/{v}/diff?base=`. Phiên bản đầu chưa có phiên bản gốc thì client
 * so với kế hoạch rỗng: `baseVersionNo = 0`, mọi dòng là `THEM` (giả định WEB-065, xem wiki/14).
 */
export interface PlanDiff {
  baseVersionNo: number;
  versionNo: number;
  added: number;
  modified: number;
  removed: number;
  groupWeightChanged?: boolean;
  /** Lần điều chỉnh hiện tại. */
  adjustmentNo?: number;
  adjustmentsMax?: number;
  lines: PlanDiffLine[];
}

/** Phương thức ký số (SCR-0403 trường 7, `esign.certificate_ref`). */
export type SignMethod = 'MYSIGN' | 'USB_TOKEN' | 'CHUNG_THU_TO_CHUC';

export const SIGN_METHOD_LABEL: Record<SignMethod, string> = {
  MYSIGN: 'Ký số bằng MySign',
  USB_TOKEN: 'USB token',
  CHUNG_THU_TO_CHUC: 'Chứng thư số của tổ chức',
};

/** Trạng thái yêu cầu ký của esign-service (SVC-10, `esign.sign_request.status`, `dossiers.yaml`). */
export type EsignRequestStatus = 'TAO' | 'CHO_XAC_NHAN' | 'DA_KY' | 'TU_CHOI' | 'HET_HAN' | 'LOI';

/** Phần của `EsignRequest` (`GET /esign/requests/{id}`) mà SCR-0403 dùng. */
export interface PlanSignRequest {
  id: string;
  status: EsignRequestStatus;
  expiresAt: string;
  completedAt?: string | null;
}

/**
 * Dữ liệu SCR-0402 ghép phía client từ ba lời gọi của hợp đồng: kế hoạch (`GET /plans`), danh sách
 * phiên bản (`GET /plans/{id}/versions`, xếp tăng dần ở đây) và phiên bản đang xem
 * (`GET /plans/{id}/versions/{v}`). Không phải lược đồ của hợp đồng.
 */
export interface PlanWorkspace {
  plan: Plan;
  versions: PlanVersionSummary[];
  version: PlanVersion;
}

/**
 * Dữ liệu SCR-0403 ghép phía client: phiên bản trình, phiên bản gốc (nếu có), tóm tắt hai phiên bản
 * và khác biệt. Không phải lược đồ của hợp đồng.
 */
export interface PlanReview {
  planId: string;
  version: PlanVersion;
  summary?: PlanVersionSummary;
  base?: PlanVersionSummary;
  /** Điểm tối đa nhóm then chốt của phiên bản gốc, để hiện "trước → sau". */
  baseGroupWeights?: Record<TaskGroup, number>;
  diff: PlanDiff;
}
