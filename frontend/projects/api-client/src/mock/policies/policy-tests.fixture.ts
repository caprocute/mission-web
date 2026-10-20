import {
  GoldenTestCase,
  PolicyWorkflowTemplateVersion,
  WorkflowLevelParam,
  WorkflowLintResult,
  WorkflowSimulateResult,
  WorkflowStepPreview,
  stepsForLevels,
  workDays,
} from '../../lib/models/policy';

/**
 * Dữ liệu giả lập SCR-1503 (bộ test case vàng) và SCR-1504 (mẫu luồng duyệt) theo mockup TL-02b
 * Hình B3.5, B3.6. `policyPackId` ghi tên gói (`P_DANG`, `P_NN`, `P_VC`), giả lập chính đổi sang id.
 */

type Seed = Omit<GoldenTestCase, 'id' | 'rowVersion' | 'policyPackId'> & { policyPackId: string };

const c = (
  pack: string,
  caseCode: string,
  description: string,
  expected: Record<string, unknown>,
  status: GoldenTestCase['status'] = 'DA_DUYET',
): Seed => ({
  policyPackId: pack,
  caseCode,
  description,
  input: {},
  expected,
  source: 'VAN_BAN',
  status,
  signed: status === 'DA_DUYET',
  approvedBy: status === 'DA_DUYET' ? { id: 'u-btc', name: 'Ban Tổ chức Trung ương' } : null,
  approvedAt: status === 'DA_DUYET' ? '2026-10-02T09:00:00+07:00' : null,
});

// TC-017 đứng đầu: hồ sơ mẫu mặc định của thẻ xem trước SCR-1501
const DANG = [
  c('P_DANG', 'TC-017', 'Đủ điểm xuất sắc nhưng tỷ lệ việc vượt chỉ 22%', {
    muc: 'HTTNV',
    diem: 91.2,
  }),
  c('P_DANG', 'TC-021', 'Chậm 6 ngày làm việc ở nhiệm vụ then chốt', { muc: 'HTNV', diem: 74.5 }),
  c('P_DANG', 'TC-001', 'Hoàn thành xuất sắc chuẩn: 95 điểm, đủ điều kiện', {
    muc: 'HTXSNV',
    diem: 95,
  }),
  c('P_DANG', 'TC-003', 'Hoàn thành đủ mọi nhiệm vụ đúng hạn', { muc: 'HTXSNV', diem: 96.4 }),
  c('P_DANG', 'TC-023', 'Chậm 21 ngày làm việc nên tiến độ bằng 0, không âm', { C: 0 }),
  c('P_DANG', 'TC-041', 'Tiêu chí chung 89%, mức cuối Hoàn thành nhiệm vụ', {
    muc: 'HTNV',
    diem: 86,
  }),
  c('P_DANG', 'TC-058', 'Người đứng đầu: 52% đơn vị không hoàn thành nhiệm vụ', { muc: 'KHTNV' }),
  c('P_DANG', 'TC-077', 'Ngày lễ 2/9 không tính là ngày chậm', { C: 0.9 }),
  c('P_DANG', 'TC-102', 'Minh chứng đang chờ bổ sung không được tính', { A: 0.8 }),
  c('P_DANG', 'TC-131', 'Số liệu ví dụ Phụ lục 1 Bảng 3', { diem: 30 }),
];

export const GOLDEN_SEED: Seed[] = [
  ...DANG,
  c('P_NN', 'NN-001', 'Công chức hoàn thành tốt, đủ minh chứng', { muc: 'HTTNV', diem: 84 }),
  c('P_NN', 'NN-014', 'Trừ 25% chất lượng cho mỗi lần chỉnh sửa', { B: 0.5 }),
  c('P_VC', 'VC-002', 'Viên chức đạt ngưỡng mới của mức xuất sắc', { muc: 'HTXSNV', diem: 92 }),
  c('P_VC', 'VC-009', 'Viên chức chậm tiến độ do nguyên nhân khách quan', { C: 1 }),
];

/** Kết quả mà phiên bản tính được cho ca (giả lập); `NGAY_LICH` làm TC-077 sai như lần #13 mockup. */
export function actualOf(g: Seed, dayKind: unknown): Record<string, unknown> {
  if (g.caseCode === 'TC-077' && dayKind === 'NGAY_LICH') return { C: 0.85 };
  return structuredClone(g.expected);
}

const opt = (v: string, title: string) => ({ const: v, title });

const ROLE_OPTIONS = [
  opt('member', 'Cán bộ được đánh giá'),
  opt('manager', 'Cán bộ quản lý trực tiếp'),
  opt('appraiser', 'Cán bộ thẩm định'),
  opt('leader', 'Lãnh đạo đơn vị'),
  opt('decider', 'Ban Thường vụ (người có thẩm quyền quyết định)'),
];

const UNIT_OPTIONS = [
  opt('DON_VI_CONG_TAC', 'Đơn vị công tác của người được đánh giá'),
  opt('BAN_TO_CHUC', 'Ban Tổ chức'),
  opt('VAN_PHONG', 'Văn phòng'),
  opt('UY_BAN_KIEM_TRA', 'Uỷ ban Kiểm tra'),
];

interface TemplateDef {
  key: string;
  name: string;
  versionNo: number;
  processKind: string;
  testCount: number;
  minLevels: number;
  maxLevels: number;
  steps: WorkflowStepPreview[];
}

const step = (
  stepKey: string,
  name: string,
  role: string,
  signRequired: boolean,
  extra: Partial<WorkflowStepPreview> = {},
): WorkflowStepPreview => ({
  stepKey,
  name,
  role,
  signRequired,
  batchSign: false,
  skippable: false,
  ...extra,
});

/** Năm mẫu luồng của mockup; 27 mẫu nhân phiên bản trên toàn hệ thống. */
export const WF_TEMPLATES: TemplateDef[] = [
  {
    key: 'duyet-ho-so-danh-gia',
    name: 'Duyệt hồ sơ đánh giá',
    versionNo: 3,
    processKind: 'DANH_GIA',
    testCount: 42,
    minLevels: 2,
    maxLevels: 4,
    steps: [
      step('TU_DANH_GIA', 'Tự đánh giá', 'member', true),
      step('THAM_DINH', 'Thẩm định', 'appraiser', true),
      step('DE_XUAT', 'Đề xuất', 'manager', false, { skippable: true }),
      step('QUYET_DINH', 'Quyết định', 'decider', true, { batchSign: true }),
    ],
  },
  {
    key: 'duyet-ke-hoach-quy',
    name: 'Duyệt kế hoạch quý',
    versionNo: 2,
    processKind: 'KE_HOACH',
    testCount: 28,
    minLevels: 1,
    maxLevels: 3,
    steps: [
      step('LAP_KE_HOACH', 'Lập kế hoạch', 'member', false),
      step('QUAN_LY_DUYET', 'Quản lý trực tiếp duyệt', 'manager', false, { skippable: true }),
      step('LANH_DAO_DUYET', 'Lãnh đạo đơn vị duyệt', 'leader', true),
    ],
  },
  {
    key: 'duyet-kep-chi-huy-cap-uy',
    name: 'Duyệt kép chỉ huy và cấp uỷ',
    versionNo: 1,
    processKind: 'DANH_GIA_LLVT',
    testCount: 36,
    minLevels: 3,
    maxLevels: 3,
    steps: [
      step('TU_DANH_GIA', 'Tự đánh giá', 'member', true),
      step('CHI_HUY', 'Chỉ huy duyệt', 'leader', true),
      step('CHINH_UY', 'Chính uỷ duyệt song song', 'decider', true),
    ],
  },
  {
    key: 'xu-ly-khieu-nai',
    name: 'Xử lý khiếu nại kết quả đánh giá',
    versionNo: 2,
    processKind: 'KHIEU_NAI',
    testCount: 24,
    minLevels: 2,
    maxLevels: 3,
    steps: [
      step('TIEP_NHAN', 'Tiếp nhận', 'appraiser', false),
      step('XAC_MINH', 'Xác minh', 'appraiser', false, { skippable: true }),
      step('KET_LUAN', 'Kết luận', 'decider', true),
    ],
  },
  {
    key: 'mo-lai-ky-hai-nguoi',
    name: 'Mở lại kỳ đã khoá (hai người duyệt)',
    versionNo: 1,
    processKind: 'MO_LAI_KY',
    testCount: 18,
    minLevels: 2,
    maxLevels: 2,
    steps: [
      step('DE_NGHI', 'Đề nghị mở lại', 'leader', true),
      step('DUYET_THU_HAI', 'Người thứ hai duyệt', 'decider', true),
    ],
  },
];

export function templateVersion(key: string, v: number): PolicyWorkflowTemplateVersion | null {
  const t = WF_TEMPLATES.find((x) => x.key === key && x.versionNo === v);
  if (!t) return null;
  return {
    key: t.key,
    name: t.name,
    versionNo: t.versionNo,
    status: 'HIEU_LUC',
    processKind: t.processKind,
    testCount: t.testCount,
    previewSteps: structuredClone(t.steps),
    paramSchema: {
      type: 'object',
      required: ['so_cap', 'cap', 'thoi_han_moi_buoc', 'khi_qua_han'],
      properties: {
        so_cap: {
          type: 'integer',
          minimum: t.minLevels,
          maximum: t.maxLevels,
          default: t.maxLevels,
        },
        cap: {
          type: 'array',
          items: {
            type: 'object',
            properties: {
              buoc: { enum: t.steps.map((s) => s.stepKey) },
              vai_tro: { oneOf: ROLE_OPTIONS },
              don_vi: { oneOf: UNIT_OPTIONS },
              ky_so: { type: 'boolean' },
              ky_lo: { type: 'boolean' },
              bo_qua_khi: { type: 'string' },
            },
          },
        },
        thoi_han_moi_buoc: { type: 'string', default: 'P3D_LV' },
        nhac_truoc: { type: 'string', default: 'P1D_LV' },
        khi_qua_han: {
          type: 'string',
          enum: ['NHAC_LAI', 'CHUYEN_CAP_PHO_UY_QUYEN', 'CHUYEN_CAP_TREN'],
          default: 'CHUYEN_CAP_PHO_UY_QUYEN',
        },
      },
    },
  };
}

function balanced(expr: string): boolean {
  let depth = 0;
  for (const ch of expr.replace(/'(?:[^'\\]|\\.)*'|"(?:[^"\\]|\\.)*"/g, '')) {
    if (ch === '(' || ch === '[') depth++;
    if ((ch === ')' || ch === ']') && --depth < 0) return false;
  }
  return depth === 0;
}

/** Linter mẫu luồng giả lập (BR-15-12): kiểm tham số theo khai báo của mẫu. */
export function lintBinding(t: TemplateDef, params: Record<string, unknown>): WorkflowLintResult {
  const issues: WorkflowLintResult['issues'] = [];
  const err = (rule: string, message: string, elementId?: string) =>
    issues.push({ rule, message, severity: 'error', ...(elementId ? { elementId } : {}) });
  const n = Number(params['so_cap']);
  if (!Number.isInteger(n) || n < t.minLevels || n > t.maxLevels) {
    err(
      'so-cap',
      t.minLevels === t.maxLevels
        ? `số cấp duyệt của mẫu này cố định là ${t.minLevels}`
        : `số cấp duyệt phải từ ${t.minLevels} đến ${t.maxLevels}`,
    );
  }
  const due = workDays(params['thoi_han_moi_buoc']);
  const remind = workDays(params['nhac_truoc']);
  if (due === null || due < 1 || due > 15)
    err('thoi-han', 'thời hạn mỗi bước phải từ 1 đến 15 ngày làm việc');
  if (remind === null || remind < 1 || remind > 15)
    err('nhac-truoc', 'thời điểm nhắc phải từ 1 đến 15 ngày làm việc');
  else if (due !== null && remind >= due)
    err('nhac-truoc', 'thời điểm nhắc phải sớm hơn thời hạn của bước');
  const levels = (params['cap'] ?? []) as WorkflowLevelParam[];
  const active = stepsForLevels(t.steps, Number.isInteger(n) ? n : t.maxLevels);
  for (const s of active) {
    const p = levels.find((l) => l.buoc === s.stepKey);
    if (!p?.vai_tro) err('vai-tro', `chưa chọn người xử lý cho bước "${s.name}"`, s.stepKey);
    if (s.signRequired && !p?.ky_so) {
      err('ky-so-bat-buoc', `bước "${s.name}" bắt buộc ký số theo mẫu`, s.stepKey);
    }
    const cond = p?.bo_qua_khi?.trim();
    if (cond && !s.skippable) {
      err('bo-qua', `bước "${s.name}" không được phép bỏ qua theo mẫu`, s.stepKey);
    } else if (cond && !balanced(cond)) {
      err('cel-type', `điều kiện bỏ qua bước "${s.name}" sai cú pháp`, s.stepKey);
    }
  }
  return { passed: issues.length === 0, issues };
}

/** Mô phỏng luồng với tham số trên ba hồ sơ mẫu. */
export function simulateBinding(
  t: TemplateDef,
  params: Record<string, unknown>,
): WorkflowSimulateResult {
  const lint = lintBinding(t, params);
  const n = Number(params['so_cap']) || t.maxLevels;
  const levels = (params['cap'] ?? []) as WorkflowLevelParam[];
  const steps = stepsForLevels(t.steps, n).map((s) => {
    const p = levels.find((l) => l.buoc === s.stepKey);
    return {
      ...s,
      role: p?.vai_tro ?? s.role,
      signRequired: !!p?.ky_so,
      batchSign: !!p?.ky_lo,
      skippable: !!p?.bo_qua_khi?.trim(),
    };
  });
  const skippable = steps.filter((s) => s.skippable).map((s) => s.stepKey);
  return {
    steps,
    lint,
    templateTests: {
      total: t.testCount,
      passed: lint.passed ? t.testCount : t.testCount - lint.issues.length,
    },
    sampleRuns: [
      {
        sample: 'Hồ sơ mẫu 1: chuyên viên, mức thẩm định trùng mức tự đề xuất',
        endState: 'DA_KHOA',
        skippedSteps: skippable,
      },
      {
        sample: 'Hồ sơ mẫu 2: trưởng phòng, mức thẩm định khác mức tự đề xuất',
        endState: 'DA_KHOA',
        skippedSteps: [],
      },
      { sample: 'Hồ sơ mẫu 3: quá hạn ở bước thẩm định', endState: 'DA_KHOA', skippedSteps: [] },
    ],
  };
}
