import type { components } from '../generated/policies';

/**
 * Policy Pack của MOD-05 `policy` (QT-15; SCR-1501, SCR-1502) theo `contracts/openapi/modules/policies.yaml`:
 * gói, phiên bản, quy tắc CEL và tham số, bảng quyết định DMN 1.4, xem trước với ca vàng, lần chạy
 * kiểm thử, gửi phê duyệt. Mọi kiểu là bí danh của lược đồ sinh.
 */
type S = components['schemas'];

export type PolicyPack = S['PolicyPack'];
export type PolicyPackList = S['PolicyPackList'];
export type PolicyPackOrigin = S['PolicyPackOrigin'];
export type PolicyPackVersion = S['PolicyPackVersion'];
export type PolicyPackVersionSummary = S['PolicyPackVersionSummary'];
export type PolicyPackVersionStatus = S['PolicyPackVersionStatus'];
export type PolicyVersionDiff = S['PolicyVersionDiff'];
export type PolicyDiffChange = S['PolicyVersionDiff']['changes'][number];
export type PolicyRule = S['PolicyRule'];
export type PolicyRuleUpdate = S['PolicyRuleUpdate'];
export type PolicyRuleSaved = S['PolicyRuleSaved'];
export type PolicyRounding = S['PolicyRounding'];
export type DecisionTable = S['DecisionTable'];
export type DecisionTableUpdate = S['DecisionTableUpdate'];
export type DecisionTableSaved = S['DecisionTableSaved'];
export type DecisionInput = S['DecisionInput'];
export type DecisionRule = S['DecisionRule'];
export type DecisionHitPolicy = S['DecisionHitPolicy'];
export type PolicyEvaluateResult = S['PolicyEvaluateResult'];
export type GoldenTestCase = S['GoldenTestCase'];
export type GoldenTestCaseList = S['GoldenTestCaseList'];
export type PolicyTestRunSummary = S['PolicyTestRunSummary'];
export type PolicyTestRunList = S['PolicyTestRunList'];
export type PolicyPackSubmit = S['PolicyPackSubmit'];
export type PolicyPackVersionCreate = S['PolicyPackVersionCreate'];
export type PolicyManifest = S['PolicyManifest'];
export type PolicyEvaluateOutputs = S['PolicyEvaluateOutputs'];
export type PolicyRoundingMode = S['PolicyRoundingMode'];
export type PolicyQualityMethod = S['PolicyQualityMethod'];
export type PolicyDayType = S['PolicyDayType'];
export type PolicyReturnReasonMode = S['PolicyReturnReasonMode'];
export type PolicyTestRun = S['PolicyTestRun'];
export type PolicyTestRunCreate = Partial<S['PolicyTestRunCreate']>;
export type PolicyTestCaseResult = S['PolicyTestCaseResult'];
export type PolicyImpact = S['PolicyImpact'];
export type PolicyPropertyResult = S['PolicyPropertyResult'];
export type GoldenTestSuite = S['GoldenTestSuite'];
export type GoldenTestCaseCreate = S['GoldenTestCaseCreate'];
export type GoldenTestCaseSource = S['GoldenTestCaseSource'];
export type PolicyPackApprove = S['PolicyPackApprove'];
export type PolicyDecisionReason = S['PolicyDecisionReason'];
export type PolicyWorkflowTemplateSummary = S['PolicyWorkflowTemplateSummary'];
export type PolicyWorkflowTemplateList = S['PolicyWorkflowTemplateList'];
export type PolicyWorkflowTemplateVersion = S['PolicyWorkflowTemplateVersion'];
export type WorkflowStepPreview = S['WorkflowStepPreview'];
export type WorkflowLintResult = S['WorkflowLintResult'];
export type WorkflowTestSummary = S['WorkflowTestSummary'];
export type WorkflowBinding = S['WorkflowBinding'];
export type WorkflowBindingUpdate = S['WorkflowBindingUpdate'];
export type WorkflowBindingScope = S['WorkflowBindingScope'];
export type WorkflowSimulateRequest = S['WorkflowSimulateRequest'];
export type WorkflowSimulateResult = S['WorkflowSimulateResult'];

/** Nhãn trạng thái phiên bản (baseline mục 9.1) hiển thị cho người dùng. */
export const POLICY_VERSION_STATUS_LABEL: Record<PolicyPackVersionStatus, string> = {
  NHAP: 'Bản nháp',
  DANG_KIEM_THU: 'Đang kiểm thử',
  DAT_KIEM_THU: 'Đạt kiểm thử',
  CHO_PHE_DUYET: 'Chờ phê duyệt',
  DA_PHAT_HANH: 'Đã phát hành',
  NGUNG: 'Ngừng áp dụng',
};

/** Phiên bản còn sửa được: `NHAP`, hoặc `DAT_KIEM_THU` (sửa xong thì về `NHAP`, MSG-15-03). */
export const isPolicyVersionEditable = (s: PolicyPackVersionStatus) =>
  s === 'NHAP' || s === 'DAT_KIEM_THU';

/** Hiển thị số phiên bản semver `2.1.0` theo cách đọc nghiệp vụ "2.1". */
export const policyVersionLabel = (v: string) => v.replace(/\.0$/, '');

/** Cách tính chất lượng B (BR-15-02). */
export const QUALITY_METHOD_LABEL: Record<PolicyQualityMethod, string> = {
  NAM_MUC_PL3: 'Theo 5 mức của Phụ lục 3',
  TRU_25_MOI_LAN: 'Trừ 25% cho mỗi lần chỉnh sửa (Bảng chấm điểm)',
};

/** Loại ngày tính chậm (BR-15-03). */
export const DAY_KIND_LABEL: Record<PolicyDayType, string> = {
  NGAY_LAM_VIEC: 'ngày làm việc theo lịch của đơn vị',
  NGAY_LICH: 'ngày lịch',
};

/** Lý do trả lại có tính vào chất lượng không (SCR-1501 trường 7). */
export const RETURN_REASON_LABEL: Record<PolicyReturnReasonMode, string> = {
  CHI_COT_LOI: 'Chỉ lý do thuộc nội dung cốt lõi',
  TAT_CA: 'Mọi lý do trả lại',
  KHONG_TINH: 'Không tính vào chất lượng',
};

/** Kiểu làm tròn (SCR-1501 trường 8; ngoài `NUA_LEN` là suy luận). */
export const ROUNDING_MODE_LABEL: Record<PolicyRoundingMode, string> = {
  NUA_LEN: 'nửa lên',
  CAT_BO: 'cắt bỏ phần lẻ',
};

export const HIT_POLICY_LABEL: Record<DecisionHitPolicy, string> = {
  FIRST: 'Dòng đầu tiên thoả mãn',
  UNIQUE: 'Chỉ một dòng thoả mãn',
  PRIORITY: 'Theo thứ tự ưu tiên kết quả',
};

/** Biến được phép trong mẫu câu giải thích, ngoài tên cột đầu vào (SCR-1502 vùng 5, MSG-15-05). */
export const EXPLANATION_VARIABLES = [
  'muc_tren',
  'diem',
  'nguong',
  'dieu_kien_thieu',
  'gia_tri',
  'dieu_khoan',
  'phien_ban',
];

/** Biến `{ten}` dùng trong mẫu câu mà ngữ cảnh xếp loại không có (MSG-15-05). */
export function unknownTemplateVariables(template: string, inputs: readonly DecisionInput[]) {
  const allowed = new Set([...EXPLANATION_VARIABLES, ...inputs.map((i) => i.name)]);
  return [...new Set([...template.matchAll(/\{(\w+)\}/g)].map((m) => m[1]))].filter(
    (v) => !allowed.has(v),
  );
}

/**
 * Dòng bị che với hit policy FIRST (MSG-15-04): dòng `by` phía trên có mọi ô điều kiện trùng ô
 * tương ứng của dòng `row` nên luôn thoả mãn trước. Chỉ so khớp nguyên văn ô; máy chủ phân tích
 * đầy đủ khoảng giá trị khi lưu và trả `warnings`.
 */
export function shadowedDecisionRows(
  rules: readonly DecisionRule[],
): { row: number; by: number }[] {
  const res: { row: number; by: number }[] = [];
  rules.forEach((ri, i) => {
    for (let j = 0; j < i; j++) {
      const cond = Object.entries(rules[j].inputs ?? {}).filter(([, v]) => v && v.trim() !== '-');
      if (cond.every(([k, v]) => ((ri.inputs ?? {})[k] ?? '').trim() === v.trim())) {
        res.push({ row: i + 1, by: j + 1 });
        break;
      }
    }
  });
  return res;
}

/** Nguồn ca kiểm thử vàng. */
export const GOLDEN_CASE_SOURCE_LABEL: Record<GoldenTestCaseSource, string> = {
  KHACH_HANG: 'Đơn vị sử dụng cung cấp',
  BA: 'Bộ phận nghiệp vụ soạn',
  VAN_BAN: 'Ví dụ trong văn bản quy định',
};

/** Một bước duyệt trong tham số luồng (`params.cap[]` của mẫu, SCR-1504 trường 4, 7, 8). */
export interface WorkflowLevelParam {
  buoc: string;
  vai_tro?: string;
  don_vi?: string;
  ky_so?: boolean;
  ky_lo?: boolean;
  bo_qua_khi?: string;
}

/** Hành vi khi quá hạn (SCR-1504 trường 6). */
export const OVERDUE_ACTION_LABEL: Record<string, string> = {
  NHAC_LAI: 'Nhắc lại người xử lý',
  CHUYEN_CAP_PHO_UY_QUYEN: 'Tự chuyển cấp phó được uỷ quyền',
  CHUYEN_CAP_TREN: 'Chuyển lên cấp trên',
};

/** Thời lượng ISO rút gọn theo ngày làm việc của mẫu luồng: `P3D_LV` ↔ 3 (SCR-1504 trường 5). */
export const workDays = (d: unknown): number | null => {
  const m = /^P(\d+)D_LV$/.exec(String(d ?? ''));
  return m ? Number(m[1]) : null;
};
// ui-text-ok: giá trị tham số gửi máy chủ, không hiện trên màn hình
export const workDaysParam = (n: number) => `P${n}D_LV`;

/**
 * Bước còn lại khi giảm số cấp duyệt của mẫu: giữ bước đầu và bước cuối, bỏ dần các bước giữa từ
 * cuối lên (suy luận; máy chủ quyết định qua mô phỏng).
 */
export function stepsForLevels<T>(steps: readonly T[], n: number): T[] {
  if (n >= steps.length) return [...steps];
  if (n <= 1) return steps.slice(-1);
  return [...steps.slice(0, n - 1), steps[steps.length - 1]];
}
