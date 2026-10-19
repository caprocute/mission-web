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
export const QUALITY_METHOD_LABEL: Record<string, string> = {
  NAM_MUC_PL3: 'Theo 5 mức của Phụ lục 3',
  TRU_25_MOI_LAN: 'Trừ 25% cho mỗi lần chỉnh sửa (Bảng chấm điểm)',
};

/** Loại ngày tính chậm (BR-15-03). */
export const DAY_KIND_LABEL: Record<string, string> = {
  NGAY_LAM_VIEC: 'ngày làm việc theo lịch của đơn vị',
  NGAY_LICH: 'ngày lịch',
};

/** Lý do trả lại có tính vào chất lượng không (SCR-1501 trường 7; khoá giá trị là suy luận). */
export const RETURN_REASON_LABEL: Record<string, string> = {
  CHI_COT_LOI: 'Chỉ lý do thuộc nội dung cốt lõi',
  TAT_CA: 'Mọi lý do trả lại',
  KHONG_TINH: 'Không tính vào chất lượng',
};

/** Kiểu làm tròn (SCR-1501 trường 8; ngoài `NUA_LEN` là suy luận). */
export const ROUNDING_MODE_LABEL: Record<string, string> = {
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
