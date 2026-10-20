import {
  DecisionTable,
  EXPLANATION_VARIABLES,
  DecisionTableUpdate,
  GoldenTestCase,
  PolicyPack,
  PolicyPackVersion,
  PolicyPackVersionStatus,
  PolicyRule,
  PolicyRuleUpdate,
  GoldenTestCaseCreate,
  PolicyTestCaseResult,
  PolicyTestRun,
  PolicyTestRunCreate,
  WorkflowBinding,
  WorkflowBindingUpdate,
  WorkflowSimulateRequest,
  shadowedDecisionRows,
} from '../../lib/models/policy';
import { MockModule, MockRequest, MockResponse, ok, problem } from '../mock-router';
import { mockSession } from '../mock-session';
import {
  GOLDEN_SEED,
  actualOf,
  WF_TEMPLATES,
  lintBinding,
  simulateBinding,
  templateVersion,
} from './policy-tests.fixture';

/**
 * Giả lập Policy Pack (QT-15; SCR-1501 … SCR-1504) theo `policies.yaml`, dữ liệu theo mockup TL-02b
 * Hình 12.3, 12.4: gói khối Đảng có bản 2.1 đang soạn và bản 2.0 đã phát hành; gói Nhà nước có bản 1.4
 * đạt kiểm thử để thử gửi phê duyệt. Kiểm tra cú pháp ở đây chỉ thô (ngoặc cân bằng); máy chủ thật kiểm
 * kiểu bằng cel-java. `manifest.labels`, `manifest.notes`, `manifest.variables` là cách giả lập đặt nhãn
 * hiển thị, ghi chú nguồn dữ liệu và khai báo biến cho gợi ý (suy luận cấu trúc `manifest`).
 */

const out = (body: unknown, headers?: Record<string, string>) => ok(structuredClone(body), headers);
const uuid = (n: number) => `0192a1b0-3c4d-7e5f-8a9b-${String(n).padStart(12, '0')}`;

const P_DANG = uuid(4001);
const P_NN = uuid(4002);
const P_VC = uuid(4003);

const VARIABLES = [
  { name: 'nv.so_luong_duyet', label: 'Số sản phẩm có minh chứng được xác nhận' },
  { name: 'nv.ke_hoach', label: 'Số lượng theo kế hoạch' },
  { name: 'nv.muc_chat_luong', label: 'Mức chất lượng khi nghiệm thu (0 đến 1)' },
  { name: 'nv.ngay_cham_chu_quan', label: 'Số ngày chậm do chủ quan' },
  { name: 'nv.so_lan_chinh_sua', label: 'Số lần phải chỉnh sửa' },
  { name: 'tham_so.tru_tien_do', label: 'Mức trừ tiến độ mỗi ngày chậm' },
  { name: 'nhom', label: 'Danh sách nhóm nhiệm vụ của cán bộ' },
  { name: 'muc_ket_qua_nhiem_vu', label: 'Mức theo kết quả nhiệm vụ' },
  { name: 'muc_tu_tieu_chi_chung', label: 'Mức theo tiêu chí chung' },
  { name: 'nguoi_dung_dau', label: 'Là người đứng đầu' },
  { name: 'tap_the.khong_dat_05_chi_tieu', label: 'Tập thể không đạt 5 chỉ tiêu' },
  { name: 'tap_the.khong_dat_tat_ca_0', label: 'Tập thể không đạt tất cả chỉ tiêu' },
  { name: 'ty_le_don_vi_KHTNV', label: 'Tỷ lệ đơn vị không hoàn thành nhiệm vụ' },
];

const MANIFEST = {
  labels: {
    A: 'Số lượng',
    B: 'Chất lượng',
    C: 'Tiến độ',
    thuong_xuyen: 'Thường xuyên',
    then_chot: 'Then chốt',
  },
  notes: {
    A: 'Chỉ đếm sản phẩm có minh chứng đã được xác nhận',
    B: 'Theo tham số "Cách tính chất lượng"',
    C: 'Đếm theo tham số "Loại ngày"',
  },
  variables: VARIABLES,
  inheritance: 'QĐ 366 v1.4 → dự thảo Quy định mới v2.x',
};

function rulesFor(slowVar: string, dayKind: string): Omit<PolicyRule, 'id'>[] {
  return [
    {
      ruleKey: 'A',
      // ui-text-ok: biểu thức công thức do người soạn chính sách nhập, hiển thị trong ô soạn công thức
      expression: 'math.least(double(nv.so_luong_duyet) / double(nv.ke_hoach), 1.0)',
      resultType: 'DECIMAL',
      legalRef: 'PL 3 mục 1',
      evalOrder: 1,
      overridable: false,
      rowVersion: 2,
    },
    {
      ruleKey: 'B',
      // ui-text-ok: biểu thức công thức do người soạn chính sách nhập, hiển thị trong ô soạn công thức
      expression: 'nv.muc_chat_luong',
      resultType: 'DECIMAL',
      legalRef: 'PL 3 mục 2',
      evalOrder: 2,
      params: { cach_tinh_chat_luong: 'NAM_MUC_PL3', ly_do_tra_lai: 'CHI_COT_LOI' },
      overridable: true,
      rowVersion: 2,
    },
    {
      ruleKey: 'C',
      // ui-text-ok: biểu thức công thức do người soạn chính sách nhập, hiển thị trong ô soạn công thức
      expression: `math.greatest(0.0, 1.0 - tham_so.tru_tien_do * double(${slowVar}))`,
      resultType: 'DECIMAL',
      legalRef: 'PL 3 mục 3',
      evalOrder: 3,
      params: { tru_tien_do: 0.05, loai_ngay: dayKind },
      overridable: true,
      rowVersion: 3,
    },
    {
      ruleKey: 'kpi_nhiem_vu',
      // ui-text-ok: biểu thức công thức do người soạn chính sách nhập, hiển thị trong ô soạn công thức
      expression: '(A + B + C) / 3.0',
      resultType: 'DECIMAL',
      rounding: { digits: 3, mode: 'NUA_LEN' },
      legalRef: 'PL 3',
      evalOrder: 10,
      overridable: false,
      rowVersion: 4,
    },
    {
      ruleKey: 'nhom',
      // ui-text-ok: biểu thức công thức do người soạn chính sách nhập, hiển thị trong ô soạn công thức
      expression: 'trung_binh(n.nhiem_vu.map(x, x.kpi_nhiem_vu))',
      resultType: 'DECIMAL',
      legalRef: 'PL A3',
      evalOrder: 20,
      params: { diem_toi_da: { thuong_xuyen: 30, then_chot: 70 } },
      overridable: true,
      rowVersion: 2,
    },
    {
      ruleKey: 'diem',
      // ui-text-ok: biểu thức công thức do người soạn chính sách nhập, hiển thị trong ô soạn công thức
      expression: 'sum(nhom.map(n, n.kpi_nhom * n.diem_toi_da))',
      resultType: 'DECIMAL',
      rounding: { digits: 1, mode: 'NUA_LEN' },
      legalRef: 'Bảng chấm điểm',
      evalOrder: 30,
      overridable: false,
      rowVersion: 2,
    },
    {
      ruleKey: 'muc_cuoi',
      // ui-text-ok: biểu thức công thức do người soạn chính sách nhập, hiển thị trong ô soạn công thức
      expression: 'min_muc(muc_ket_qua_nhiem_vu, muc_tu_tieu_chi_chung)',
      resultType: 'STRING',
      legalRef: 'QĐ Điều 11',
      evalOrder: 50,
      overridable: false,
      rowVersion: 1,
    },
    {
      ruleKey: 'nguoi_dung_dau',
      expression:
        'nguoi_dung_dau && (tap_the.khong_dat_05_chi_tieu || tap_the.khong_dat_tat_ca_0 || ty_le_don_vi_KHTNV > 0.5) ? "KHTNV" : muc_cuoi',
      resultType: 'STRING',
      legalRef: 'QĐ khoản 14.3.3',
      evalOrder: 60,
      overridable: false,
      rowVersion: 1,
    },
  ];
}

function xepLoai(): Omit<DecisionTable, 'id'> {
  return {
    tableKey: 'xep_loai',
    hitPolicy: 'FIRST',
    legalRef: 'QĐ Điều 11; PL 6 §12',
    inputs: [
      { name: 'diem', label: 'Điểm', type: 'number' },
      { name: 'ty_le_O', label: 'Tỷ lệ mục tiêu đạt', type: 'number' },
      { name: 'ty_le_vuot', label: 'Tỷ lệ việc vượt', type: 'number' },
      { name: 'giai_phap_dot_pha', label: 'Có giải pháp đột phá', type: 'boolean' },
      { name: 'tieu_chi_chung', label: 'Tiêu chí chung', type: 'string' },
      { name: 'loai_tru', label: 'Thuộc diện loại trừ', type: 'boolean' },
    ],
    rules: [
      {
        inputs: { tieu_chi_chung: '"Không đảm bảo"' },
        ratingLevel: 'KHTNV',
        clause: 'QĐ 11.2.4',
        explanation: 'Tiêu chí chung "Không đảm bảo"',
      },
      {
        inputs: { loai_tru: 'true' },
        ratingLevel: 'KHTNV',
        clause: 'QĐ 11.2.4',
        explanation: 'Rơi vào điều kiện loại trừ',
      },
      {
        inputs: { diem: '< 60' },
        ratingLevel: 'KHTNV',
        clause: 'QĐ Điều 11',
        explanation: 'Dưới 60 điểm',
      },
      {
        inputs: {
          diem: '>= 90',
          ty_le_O: '= 1',
          ty_le_vuot: '>= 0.3',
          giai_phap_dot_pha: 'true',
          tieu_chi_chung: '"Thực hiện tốt"',
          loai_tru: 'false',
        },
        ratingLevel: 'HTXSNV',
        clause: 'QĐ Điều 11',
        explanation: 'Đủ mọi điều kiện xuất sắc',
      },
      {
        inputs: { diem: '>= 80', tieu_chi_chung: '"Thực hiện tốt", "Đảm bảo"', loai_tru: 'false' },
        ratingLevel: 'HTTNV',
        clause: 'QĐ Điều 11 · PL 6 §12',
        explanation: 'Thiếu điều kiện mức trên, chuyển mức liền kề',
      },
      {
        inputs: {
          diem: '[60..80)',
          tieu_chi_chung: '"Thực hiện tốt", "Đảm bảo", "Cơ bản đảm bảo"',
          loai_tru: 'false',
        },
        ratingLevel: 'HTNV',
        clause: 'QĐ Điều 11',
        explanation: 'Từ 60 đến dưới 80 điểm',
      },
    ],
    explanationTemplates: {
      // ui-text-ok: mẫu câu giải thích, biến {..} được thay bằng giá trị khi xếp loại
      ha_muc:
        'Đủ điểm mức {muc_tren} ({diem} ≥ {nguong}) nhưng chưa đạt điều kiện {dieu_kien_thieu} (thực tế {gia_tri}, theo {dieu_khoan}), nên chuyển xuống mức liền kề theo PL 6 §12.',
    },
    dmnHash: '5b1e8c0a9d7f6e3c2b1a0f9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0d9c',
    rowVersion: 3,
  };
}

interface VersionState {
  version: PolicyPackVersion;
  rules: PolicyRule[];
  tables: DecisionTable[];
  runs: (PolicyTestRun & { polls: number })[];
  bindings: WorkflowBinding[];
  /** Số lần đọc phiên bản còn lại trước khi saga phát hành giả lập xong. */
  publishing: number | null;
}

let packs: PolicyPack[] = [];
let versions = new Map<string, VersionState>();
let seq = 0;

const vkey = (id: string, v: string) => `${id}@${v}`;

const PACK_CODE: Record<string, string> = {
  [P_DANG]: 'khoi-dang/quy-dinh-moi',
  [P_NN]: 'nn-335',
  [P_VC]: 'vc-su-nghiep',
};

function makeVersion(
  pack: string,
  n: number,
  version: string,
  status: PolicyPackVersionStatus,
  extra: Partial<PolicyPackVersion>,
  slowVar: string,
  dayKind: string,
): VersionState {
  const vid = uuid(n);
  return {
    version: {
      id: vid,
      policyPackId: pack,
      version,
      status,
      packCode: PACK_CODE[pack],
      manifest: structuredClone(MANIFEST),
      rowVersion: 1,
      createdAt: '2026-10-12T09:00:00+07:00',
      ...extra,
    },
    rules: rulesFor(slowVar, dayKind).map((r, i) => ({ ...r, id: uuid(n * 100 + i) })),
    tables: [{ ...xepLoai(), id: uuid(n * 100 + 50) }],
    runs: [],
    bindings: [],
    publishing: null,
  };
}

function seed() {
  seq = 0;
  versions = new Map();
  const all: VersionState[] = [
    makeVersion(
      P_DANG,
      4121,
      '2.1.0',
      'NHAP',
      { changeNote: 'Đổi cách đếm ngày chậm theo ngày làm việc của đơn vị' },
      'nv.ngay_cham_chu_quan',
      'NGAY_LAM_VIEC',
    ),
    makeVersion(
      P_DANG,
      4120,
      '2.0.0',
      'DA_PHAT_HANH',
      {
        effectiveFrom: '2026-10-01',
        goldenPassRate: 1,
        publishedAt: '2026-09-20T15:10:00+07:00',
        changeNote: 'Phiên bản đầu theo dự thảo Quy định mới',
      },
      'nv.ngay_cham',
      'NGAY_LICH',
    ),
    makeVersion(
      P_NN,
      4214,
      '1.4.0',
      'DAT_KIEM_THU',
      { goldenPassRate: 1, changeNote: 'Bổ sung cách tính chất lượng trừ 25%' },
      'nv.ngay_cham_chu_quan',
      'NGAY_LAM_VIEC',
    ),
    makeVersion(
      P_NN,
      4213,
      '1.3.0',
      'DA_PHAT_HANH',
      { effectiveFrom: '2026-07-01', goldenPassRate: 1, publishedAt: '2026-06-10T10:00:00+07:00' },
      'nv.ngay_cham_chu_quan',
      'NGAY_LAM_VIEC',
    ),
    makeVersion(
      P_VC,
      4311,
      '1.1.0',
      'CHO_PHE_DUYET',
      {
        goldenPassRate: 1,
        changeNote: 'Điều chỉnh ngưỡng xếp loại theo hướng dẫn năm 2027',
        createdBy: { id: 'u-policy-author', name: 'Trần Thị Hạnh' },
      },
      'nv.ngay_cham_chu_quan',
      'NGAY_LAM_VIEC',
    ),
    makeVersion(
      P_VC,
      4310,
      '1.0.0',
      'DA_PHAT_HANH',
      { effectiveFrom: '2026-01-01', goldenPassRate: 1, publishedAt: '2025-12-15T09:00:00+07:00' },
      'nv.ngay_cham_chu_quan',
      'NGAY_LAM_VIEC',
    ),
  ];
  golden = structuredClone(GOLDEN_SEED).map((g, i) => ({
    ...g,
    id: uuid(5001 + i),
    rowVersion: 1,
    policyPackId: g.policyPackId === 'P_DANG' ? P_DANG : g.policyPackId === 'P_NN' ? P_NN : P_VC,
  }));
  for (const s of all) versions.set(vkey(s.version.policyPackId, s.version.version), s);
  seedRuns(all);
  const draft = all[0];
  draft.bindings.push({
    id: uuid(7001),
    processKind: 'DANH_GIA',
    templateKey: 'duyet-ho-so-danh-gia',
    templateVersionNo: 3,
    versionNo: 1,
    scope: { flow: 'DANG', fromPeriodId: 'p-2027q1', periodType: 'QUY', subjectGroupId: null },
    params: {
      so_cap: 4,
      cap: [
        { buoc: 'TU_DANH_GIA', vai_tro: 'member', ky_so: true },
        { buoc: 'THAM_DINH', vai_tro: 'appraiser', don_vi: 'BAN_TO_CHUC', ky_so: true },
        {
          buoc: 'DE_XUAT',
          vai_tro: 'manager',
          bo_qua_khi:
            "nguoi_duoc_danh_gia.chuc_danh in ['CHUYEN_VIEN'] && muc_tham_dinh == muc_tu_de_xuat",
        },
        { buoc: 'QUYET_DINH', vai_tro: 'decider', ky_lo: true, ky_so: true },
      ],
      thoi_han_moi_buoc: 'P3D_LV',
      nhac_truoc: 'P1D_LV',
      khi_qua_han: 'CHUYEN_CAP_PHO_UY_QUYEN',
    },
    status: 'NHAP',
    lint: { passed: true, issues: [] },
    templateTests: { passed: 42, total: 42 },
    rowVersion: 2,
  });
  const summaries = (id: string) =>
    all
      .filter((s) => s.version.policyPackId === id)
      .map(({ version: { manifest: _m, packCode: _c, ...rest } }) => rest);
  packs = [
    {
      id: P_DANG,
      code: 'khoi-dang/quy-dinh-moi',
      name: 'Quy định đánh giá khối Đảng (dự thảo Quy định mới)',
      sector: 'KHOI_DANG',
      flow: 'DANG',
      origin: 'GOC_TOAN_CUC',
      legalBasis: 'QĐ 366-QĐ/TW; dự thảo Quy định mới về đánh giá cán bộ',
      versions: summaries(P_DANG),
    },
    {
      id: P_NN,
      code: 'nn-335',
      name: 'Đánh giá công chức, viên chức theo Nghị định 335/2025',
      sector: 'CHINH_QUYEN',
      flow: 'NHA_NUOC',
      origin: 'GOC_TOAN_CUC',
      legalBasis: 'NĐ 335/2025/NĐ-CP',
      versions: summaries(P_NN),
    },
    {
      id: P_VC,
      code: 'vc-su-nghiep',
      name: 'Đánh giá viên chức đơn vị sự nghiệp công lập',
      sector: 'CHINH_QUYEN',
      flow: 'NHA_NUOC',
      origin: 'GOC_TOAN_CUC',
      legalBasis: 'NĐ 335/2025/NĐ-CP; hướng dẫn của Bộ Nội vụ',
      versions: summaries(P_VC),
    },
  ];
}

let golden: GoldenTestCase[] = [];

/** Kết quả tính thử theo ca (giả lập đầu ra của cel-java). */
const EVALUATIONS: Record<string, Record<string, unknown>> = {
  'TC-017': {
    tasks: [
      { ma: 'NV-TC-02', nhom: 'then_chot', A: 1, B: 0.5, C: 0.85, kpi_nhiem_vu: 0.783 },
      { ma: 'NV-TX-05', nhom: 'thuong_xuyen', A: 1, B: 0.75, C: 1, kpi_nhiem_vu: 0.917 },
    ],
    groups: [
      { key: 'then_chot', kpi: 0.909, score: 63.6 },
      { key: 'thuong_xuyen', kpi: 0.92, score: 27.6 },
    ],
    diem: 91.2,
    level: 'HTTNV',
    explanation:
      'Đủ điểm mức Hoàn thành xuất sắc nhiệm vụ (91,2 ≥ 90) nhưng chưa đạt điều kiện tỷ lệ việc vượt (thực tế 22%, theo QĐ Điều 11), nên chuyển xuống mức liền kề theo PL 6 §12.',
  },
  'TC-003': {
    tasks: [{ ma: 'NV-TC-01', nhom: 'then_chot', A: 1, B: 1, C: 1, kpi_nhiem_vu: 1 }],
    groups: [
      { key: 'then_chot', kpi: 0.96, score: 67.2 },
      { key: 'thuong_xuyen', kpi: 0.973, score: 29.2 },
    ],
    diem: 96.4,
    level: 'HTXSNV',
    explanation: 'Đủ mọi điều kiện xuất sắc theo QĐ Điều 11.',
  },
  'TC-021': {
    tasks: [{ ma: 'NV-TC-03', nhom: 'then_chot', A: 1, B: 0.75, C: 0.7, kpi_nhiem_vu: 0.817 }],
    groups: [
      { key: 'then_chot', kpi: 0.75, score: 52.5 },
      { key: 'thuong_xuyen', kpi: 0.733, score: 22 },
    ],
    diem: 74.5,
    level: 'HTNV',
    explanation: 'Từ 60 đến dưới 80 điểm theo QĐ Điều 11.',
  },
};

function state(r: MockRequest): VersionState | null {
  return versions.get(vkey(r.pathParams['id'], r.pathParams['v'])) ?? null;
}

const notFound = () => problem(404, 'Không tìm thấy phiên bản bộ quy định đánh giá');
const etag = (n: number) => ({ ETag: `"${n}"` });

function ifMatch(r: MockRequest, rowVersion: number): MockResponse | null {
  const h = r.headers.get('If-Match');
  if (!h) return problem(428, 'Thiếu điều kiện phiên bản khi ghi');
  if (h.replace(/"/g, '') !== String(rowVersion)) {
    return problem(409, 'Nội dung đã được người khác cập nhật. Vui lòng tải lại.', {
      code: 'POLICY-409-VERSION-CONFLICT',
    } as never);
  }
  return null;
}

function balanced(expr: string): boolean {
  let depth = 0;
  for (const ch of expr.replace(/"(?:[^"\\]|\\.)*"/g, '')) {
    if (ch === '(') depth++;
    if (ch === ')' && --depth < 0) return false;
  }
  return depth === 0;
}

/** Đưa phiên bản đạt kiểm thử về nháp khi nội dung đổi (MSG-15-03). */
function touch(s: VersionState): boolean {
  s.version.rowVersion++;
  if (s.version.status === 'DAT_KIEM_THU') {
    s.version.status = 'NHAP';
    s.version.goldenPassRate = null;
    return true;
  }
  return false;
}

function frozen(s: VersionState, onlyDraft = false): MockResponse | null {
  const st = s.version.status;
  const allowed = onlyDraft ? st === 'NHAP' : st === 'NHAP' || st === 'DAT_KIEM_THU';
  return allowed
    ? null
    : problem(409, 'Phiên bản này không còn ở trạng thái được sửa.', {
        code: 'POLICY-409-VERSION-FROZEN',
      } as never);
}

function saveRule(r: MockRequest): MockResponse {
  const s = state(r);
  if (!s) return notFound();
  const rule = s.rules.find((x) => x.ruleKey === r.pathParams['key']);
  if (!rule) return problem(404, 'Không tìm thấy quy tắc');
  const blocked = frozen(s) ?? ifMatch(r, rule.rowVersion);
  if (blocked) return blocked;
  const b = r.body as PolicyRuleUpdate;
  if (!b?.expression?.trim() || !balanced(b.expression)) {
    const msg = `Biểu thức không hợp lệ: thiếu hoặc thừa dấu ngoặc tại khối ${rule.ruleKey}.`;
    return problem(422, 'Dữ liệu chưa hợp lệ', {
      code: 'POLICY-422-CEL-TYPE',
      msg: 'MSG-15-01',
      detail: msg,
      errors: [{ field: 'expression', message: msg }],
    } as never);
  }
  const params = (b.params ?? {}) as Record<string, unknown>;
  const slow = params['tru_tien_do'];
  if (typeof slow === 'number' && (slow < 0 || slow > 0.2)) {
    const msg = 'Mức trừ tiến độ phải từ 0,00 đến 0,20.';
    return problem(422, 'Dữ liệu chưa hợp lệ', {
      code: 'COMMON-422-VALIDATION',
      detail: msg,
      errors: [{ field: 'params.tru_tien_do', message: msg }],
    } as never);
  }
  const groups = params['diem_toi_da'] as Record<string, number> | undefined;
  if (groups) {
    const total = Object.values(groups).reduce((a, n) => a + Number(n || 0), 0);
    const want = 100;
    if (total !== want) {
      const msg = `Tổng điểm tối đa các nhóm là ${total}, phải bằng ${want}.`;
      return problem(422, 'Dữ liệu chưa hợp lệ', {
        code: 'POLICY-422-GROUP-SUM',
        msg: 'MSG-15-02',
        detail: msg,
        errors: [{ field: 'params.diem_toi_da', message: msg }],
      } as never);
    }
  }
  Object.assign(rule, {
    expression: b.expression,
    resultType: b.resultType,
    rounding: b.rounding ?? rule.rounding,
    legalRef: b.legalRef ?? rule.legalRef,
    params: b.params ?? rule.params,
    rowVersion: rule.rowVersion + 1,
  });
  const reverted = touch(s);
  return out(
    { ...rule, versionStatus: s.version.status, statusReverted: reverted },
    etag(rule.rowVersion),
  );
}

function saveTable(r: MockRequest): MockResponse {
  const s = state(r);
  if (!s) return notFound();
  const t = s.tables.find((x) => x.tableKey === r.pathParams['key']);
  if (!t) return problem(404, 'Không tìm thấy bảng quyết định');
  const blocked = frozen(s, true) ?? ifMatch(r, t.rowVersion);
  if (blocked) return blocked;
  const b = r.body as DecisionTableUpdate;
  const missing = b.rules.findIndex((x) => !x.clause?.trim() || !x.explanation?.trim());
  if (missing >= 0) {
    const msg = `Dòng ${missing + 1} chưa có điều khoản căn cứ hoặc lời giải thích.`;
    return problem(422, 'Dữ liệu chưa hợp lệ', {
      code: 'COMMON-422-VALIDATION',
      detail: msg,
      errors: [{ field: `rules[${missing}]`, message: msg }],
    } as never);
  }
  const allowed = new Set([...EXPLANATION_VARIABLES, ...b.inputs.map((i) => i.name)]);
  for (const tpl of Object.values(b.explanationTemplates ?? {})) {
    const bad = [...tpl.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).find((v) => !allowed.has(v));
    if (bad) {
      const msg = `Mẫu câu giải thích dùng biến ${bad} không có trong ngữ cảnh xếp loại.`;
      return problem(422, 'Dữ liệu chưa hợp lệ', {
        code: 'POLICY-422-TEMPLATE-VAR',
        msg: 'MSG-15-05',
        detail: msg,
        errors: [{ field: 'explanationTemplates', message: msg }],
      } as never);
    }
  }
  Object.assign(t, b, { rowVersion: t.rowVersion + 1 });
  touch(s);
  const warnings = shadowedDecisionRows(t.rules).map((w) => ({
    msg: 'MSG-15-04',
    message: `Dòng ${w.row} không bao giờ được chọn vì dòng ${w.by} phía trên luôn thoả mãn trước (bảng áp dụng dòng đầu tiên thoả mãn).`,
    row: w.row,
    shadowedBy: w.by,
  }));
  return out({ ...t, warnings }, etag(t.rowVersion));
}

function dmn(r: MockRequest): MockResponse {
  const s = state(r);
  const t = s?.tables.find((x) => x.tableKey === r.pathParams['key']);
  if (!s || !t) return notFound();
  const esc = (v: string) =>
    v.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  const rows = t.rules
    .map(
      (rule, i) =>
        `    <rule id="r${i + 1}">\n${t.inputs
          .map(
            (c) =>
              `      <inputEntry><text>${esc(rule.inputs?.[c.name] || '-')}</text></inputEntry>`,
          )
          .join(
            '\n',
          )}\n      <outputEntry><text>"${rule.ratingLevel}"</text></outputEntry>\n    </rule>`,
    )
    .join('\n');
  // ui-text-ok: nội dung tệp xuất ra, không hiển thị trên màn hình
  const xml = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="${t.tableKey}" name="${t.tableKey}" namespace="https://okrkpi.vn/dmn">
  <decision id="${t.tableKey}_${s.version.version}">
  <decisionTable hitPolicy="${t.hitPolicy}">
${t.inputs.map((c) => `    <input label="${esc(c.label ?? c.name)}"><inputExpression typeRef="${c.type}"><text>${c.name}</text></inputExpression></input>`).join('\n')}
    <output name="muc" typeRef="string"/>
${rows}
  </decisionTable>
  </decision>
</definitions>
`;
  return {
    status: 200,
    body: new Blob([xml], { type: 'application/xml' }),
    headers: {
      'Content-Type': 'application/xml',
      'Content-Disposition': `attachment; filename="${t.tableKey}-${s.version.version}.dmn"`,
    },
  };
}

function diff(r: MockRequest): MockResponse {
  const s = state(r);
  const base = versions.get(vkey(r.pathParams['id'], r.params.get('base') ?? ''));
  if (!s || !base) return notFound();
  const changes: unknown[] = [];
  for (const rule of s.rules) {
    const old = base.rules.find((x) => x.ruleKey === rule.ruleKey);
    if (!old) {
      changes.push({ kind: 'RULE', key: rule.ruleKey, change: 'THEM', after: rule.expression });
      continue;
    }
    if (old.expression !== rule.expression) {
      changes.push({
        kind: 'RULE',
        key: rule.ruleKey,
        path: 'expression',
        change: 'SUA',
        before: old.expression,
        after: rule.expression,
      });
    }
    const keys = new Set([...Object.keys(old.params ?? {}), ...Object.keys(rule.params ?? {})]);
    for (const k of keys) {
      const a = JSON.stringify((old.params ?? {})[k]);
      const b = JSON.stringify((rule.params ?? {})[k]);
      if (a !== b) {
        changes.push({
          kind: 'PARAM',
          key: rule.ruleKey,
          path: `params.${k}`,
          change: 'SUA',
          before: (old.params ?? {})[k],
          after: (rule.params ?? {})[k],
        });
      }
    }
  }
  for (const t of s.tables) {
    const old = base.tables.find((x) => x.tableKey === t.tableKey);
    if (old && JSON.stringify(old.rules) !== JSON.stringify(t.rules)) {
      changes.push({ kind: 'DECISION_TABLE', key: t.tableKey, path: 'rules', change: 'SUA' });
    }
  }
  return out({ base: base.version.version, target: s.version.version, changes });
}

function evaluate(r: MockRequest): MockResponse {
  const s = state(r);
  if (!s) return notFound();
  const code = (r.body as { caseCode?: string })?.caseCode ?? '';
  const e = EVALUATIONS[code];
  if (!e) {
    return problem(422, 'Hồ sơ mẫu không thuộc bộ test case vàng.', {
      code: 'POLICY-422-CASE-NOT-GOLDEN',
    } as never);
  }
  // điểm nhóm tính lại theo "Điểm tối đa" đang lưu để xem trước phản ánh tham số vừa sửa
  const max = (s.rules.find((x) => x.ruleKey === 'nhom')?.params?.['diem_toi_da'] ?? {}) as Record<
    string,
    number
  >;
  const tasks = e['tasks'] as { kpi_nhiem_vu: number }[];
  const groups = (e['groups'] as { key: string; kpi: number }[]).map((g) => ({
    key: g.key,
    kpi_nhom: g.kpi,
    diem_toi_da: max[g.key] ?? 0,
    diem: Math.round(g.kpi * (max[g.key] ?? 0) * 10) / 10,
  }));
  const diem = Math.round(groups.reduce((a, g) => a + g.diem, 0) * 10) / 10;
  return out({
    caseCode: code,
    engine: 'CEL_JAVA',
    outputs: {
      nhiem_vu: tasks,
      nhom: groups,
      diem,
    },
    ratingLevel: e['level'],
    explanation: e['explanation'],
    trace: [
      { ruleKey: 'kpi_nhiem_vu', value: tasks.map((t) => t.kpi_nhiem_vu) },
      { ruleKey: 'diem', value: diem },
    ],
  });
}

const dayKindOf = (s: VersionState) =>
  s.rules.find((x) => x.ruleKey === 'C')?.params?.['loai_ngay'];
const goldenOf = (packId: string) =>
  golden.filter((g) => g.policyPackId === packId && g.status === 'DA_DUYET');

/** Kết quả từng ca của một lần chạy trên phiên bản (giả lập cel-java và cel-js). */
function caseResults(s: VersionState, jsMismatch?: string): PolicyTestCaseResult[] {
  const day = dayKindOf(s);
  return goldenOf(s.version.policyPackId).map((g) => {
    const actual = actualOf(g, day);
    const passed = JSON.stringify(actual) === JSON.stringify(g.expected);
    const js = g.caseCode === jsMismatch ? { C: -0.05 } : null;
    return {
      goldenTestCaseId: g.id,
      caseCode: g.caseCode,
      description: g.description,
      expected: g.expected,
      actual,
      actualJs: js,
      passed,
      engineMatch: !js,
      diff: passed ? null : { expected: g.expected, actual },
    };
  });
}

type Run = VersionState['runs'][number];

function makeRun(
  s: VersionState,
  runNo: number,
  at: string,
  cases: PolicyTestCaseResult[],
  extra: Partial<Run> = {},
): Run {
  const passed = cases.filter((x) => x.passed).length;
  const matched = cases.filter((x) => x.engineMatch).length;
  return {
    id: uuid(6000 + runNo * 10 + (s.version.policyPackId === P_DANG ? 1 : 2)),
    runNo,
    policyPackVersionId: s.version.id,
    version: s.version.version,
    result: passed === cases.length && matched === cases.length ? 'DAT' : 'KHONG_DAT',
    engines: ['CEL_JAVA', 'CEL_JS'],
    total: cases.length,
    passed,
    failed: cases.length - passed,
    engineMatch: matched === cases.length,
    engineMatchedCount: matched,
    durationMs: 6800,
    startedAt: at,
    finishedAt: at,
    createdBy: { id: 'u-policy-author', name: 'Trần Thị Hạnh' },
    cases,
    propertyResult: {
      datasets: 10000,
      invariants: ['0 <= diem && diem <= 100', 'C >= 0'],
      violations: 0,
    },
    configDiff: null,
    impact: null,
    polls: 0,
    ...extra,
  };
}

/** Lịch sử chạy theo mockup: bản 2.1 có lần #13 lỗi TC-077 (loại ngày bị đổi sang ngày lịch). */
function seedRuns(all: VersionState[]) {
  const [d21, d20, nn14, nn13, vc11] = all;
  d20.runs.push(makeRun(d20, 9, '2026-10-01T10:00:00+07:00', caseResults(d20).map(fixed)));
  const bad = caseResults(d21).map((x) =>
    x.caseCode === 'TC-077'
      ? {
          ...x,
          actual: { C: 0.85 },
          passed: false,
          diff: { expected: x.expected, actual: { C: 0.85 } },
        }
      : x,
  );
  d21.runs.push(
    makeRun(d21, 13, '2026-10-18T14:02:00+07:00', bad, {
      configDiff: {
        base: 'nháp 5',
        target: 'nháp 6',
        changes: [
          {
            kind: 'PARAM',
            key: 'C',
            path: 'params.loai_ngay',
            change: 'SUA',
            before: 'NGAY_LAM_VIEC',
            after: 'NGAY_LICH',
          },
        ],
      },
    }),
    makeRun(d21, 12, '2026-10-17T09:30:00+07:00', caseResults(d21)),
  );
  nn14.runs.push(makeRun(nn14, 5, '2026-10-15T08:20:00+07:00', caseResults(nn14)));
  nn13.runs.push(makeRun(nn13, 3, '2026-06-01T08:00:00+07:00', caseResults(nn13)));
  vc11.runs.push(
    makeRun(vc11, 7, '2026-10-16T16:45:00+07:00', caseResults(vc11), {
      impact: {
        baseVersion: '1.0.0',
        changedScoreCount: 0,
        changedRatingCount: 1,
        lockedPeriodNote: 'Không áp dụng, bản 1.1 chỉ có hiệu lực từ Quý I/2027.',
      },
    }),
  );
}

/** Bản đã phát hành dùng ngày lịch từ trước nên ca TC-077 được ghi nhận đúng ở thời điểm đó. */
const fixed = (x: PolicyTestCaseResult): PolicyTestCaseResult =>
  x.passed ? x : { ...x, actual: x.expected, passed: true, diff: null };

const runsOfPack = (packId: string) =>
  [...versions.values()].filter((v) => v.version.policyPackId === packId).flatMap((v) => v.runs);

function startRun(r: MockRequest): MockResponse {
  const s = state(r);
  if (!s) return notFound();
  if (s.runs.some((x) => x.result === 'DANG_CHAY')) {
    return problem(409, 'Đang có một lần chạy kiểm thử chưa xong.', {
      code: 'POLICY-409-TEST-RUNNING',
    } as never);
  }
  if (s.version.status !== 'NHAP' && s.version.status !== 'DAT_KIEM_THU') {
    return problem(409, 'Phiên bản này không chạy kiểm thử được ở trạng thái hiện tại.', {
      code: 'POLICY-409-VERSION-FROZEN',
    } as never);
  }
  const blocked = ifMatch(r, s.version.rowVersion);
  if (blocked) return blocked;
  if (s.version.status === 'NHAP') s.version.status = 'DANG_KIEM_THU';
  const runNo = Math.max(0, ...runsOfPack(s.version.policyPackId).map((x) => x.runNo ?? 0)) + 1;
  const body = (r.body ?? {}) as PolicyTestRunCreate;
  const base = body.compareWithVersion;
  const run = makeRun(s, runNo, new Date().toISOString(), caseResults(s), {
    result: 'DANG_CHAY',
    passed: 0,
    failed: 0,
    finishedAt: null,
    durationMs: null,
    propertyResult: { datasets: body.propertyDatasets ?? 10000, invariants: [], violations: 0 },
    impact: base
      ? {
          baseVersion: base,
          changedScoreCount: 12,
          changedRatingCount: 1,
          lockedPeriodNote: 'Không áp dụng, phiên bản mới chỉ có hiệu lực từ kỳ chưa bắt đầu.',
        }
      : null,
  });
  run.id = uuid(6900 + ++seq);
  s.runs.unshift(run);
  return {
    status: 202,
    body: { id: run.id, type: 'TEST_RUN', status: 'CHO', progress: 0 },
    headers: { Location: `${r.path}/${run.id}` },
  };
}

/** Lần chạy đang chờ: sau hai lần đọc thì có kết quả (BR-15-07). */
function settle(s: VersionState) {
  for (const run of s.runs.filter((x) => x.result === 'DANG_CHAY')) {
    if (++run.polls < 2) continue;
    const done = makeRun(s, run.runNo ?? 0, run.startedAt, run.cases ?? [], {
      id: run.id,
      impact: run.impact,
      finishedAt: new Date().toISOString(),
      durationMs: 6800,
    });
    Object.assign(run, done, { polls: run.polls });
    if (s.version.status === 'DANG_KIEM_THU') {
      const ok = run.result === 'DAT';
      s.version.status = ok ? 'DAT_KIEM_THU' : 'NHAP';
      s.version.goldenPassRate = run.total ? run.passed / run.total : null;
      s.version.lastTestRunId = run.id;
    }
  }
}

const summary = ({
  polls: _p,
  cases: _c,
  configDiff: _d,
  impact: _i,
  propertyResult: _r,
  ...x
}: Run) => x;

function listRuns(r: MockRequest): MockResponse {
  const s = state(r);
  if (!s) return notFound();
  settle(s);
  const limit = Number(r.params.get('limit') ?? 10) || 10;
  return out({ items: s.runs.slice(0, limit).map(summary), nextCursor: null });
}

function getRun(r: MockRequest): MockResponse {
  const s = state(r);
  if (!s) return notFound();
  settle(s);
  const run = s.runs.find((x) => x.id === r.pathParams['runId']);
  if (!run) return problem(404, 'Không tìm thấy lần chạy');
  const { polls: _p, ...full } = run;
  return out(run.result === 'DANG_CHAY' ? { ...full, cases: [] } : full);
}

function listTestCases(r: MockRequest): MockResponse {
  const pack = r.params.get('pack');
  const status = r.params.get('status');
  const mine = golden.filter((g) => g.policyPackId === pack);
  const approved = mine.filter((g) => g.status === 'DA_DUYET');
  return out({
    suite: {
      code:
        pack === P_DANG
          ? 'bo-ca-mau-ban-to-chuc-v3'
          : `bo-ca-mau-${PACK_CODE[pack ?? ''] ?? 'goi'}`,
      total: approved.length,
      pending: mine.filter((g) => g.status === 'NHAP').length,
      approvedBy: 'Ban Tổ chức Trung ương',
      approvedAt: '2026-10-02T09:00:00+07:00',
    },
    items: status ? mine.filter((g) => g.status === status) : mine,
    nextCursor: null,
  });
}

function createTestCase(r: MockRequest): MockResponse {
  const b = (r.body ?? {}) as Partial<GoldenTestCaseCreate>;
  const errors: { field: string; message: string }[] = [];
  if (!b.caseCode?.trim()) errors.push({ field: 'caseCode', message: 'Nhập mã ca kiểm thử.' });
  if (!b.description?.trim()) errors.push({ field: 'description', message: 'Nhập mô tả ca.' });
  if (!b.expected || !Object.keys(b.expected).length) {
    errors.push({ field: 'expected', message: 'Nhập ít nhất một kết quả mong đợi.' });
  }
  if (golden.some((g) => g.policyPackId === b.policyPackId && g.caseCode === b.caseCode?.trim())) {
    errors.push({ field: 'caseCode', message: 'Mã ca đã có trong bộ kiểm thử của gói.' });
  }
  if (errors.length) return problem(422, 'Thông tin ca kiểm thử chưa hợp lệ.', { errors } as never);
  const g: GoldenTestCase = {
    id: uuid(5500 + ++seq),
    policyPackId: b.policyPackId!,
    caseCode: b.caseCode!.trim(),
    description: b.description!.trim(),
    input: b.input ?? {},
    expected: b.expected!,
    source: b.source ?? 'BA',
    legalRef: b.legalRef ?? null,
    note: b.note,
    status: 'NHAP',
    signed: false,
    createdBy: { id: 'dev-user', name: 'Nguyễn Văn Minh' },
    rowVersion: 1,
  };
  golden.push(g);
  return {
    status: 201,
    body: structuredClone(g),
    headers: { ...etag(1), Location: `${r.path}/${g.id}` },
  };
}

function approve(r: MockRequest): MockResponse {
  const s = state(r);
  if (!s) return notFound();
  const blocked = ifMatch(r, s.version.rowVersion);
  if (blocked) return blocked;
  if (s.version.status !== 'CHO_PHE_DUYET') {
    return problem(409, 'Phiên bản không ở trạng thái chờ phê duyệt.', {
      code: 'POLICY-409-NOT-PENDING',
    } as never);
  }
  if (!mockSession.recentMfa()) {
    const res = problem(401, 'Cần xác thực nhiều lớp trước khi ký phê duyệt phát hành.', {
      type: 'https://datatracker.ietf.org/doc/html/rfc9470',
      code: 'insufficient_user_authentication',
    });
    res.headers = {
      ...res.headers,
      // ui-text-ok: tiêu đề HTTP theo RFC 9470, không hiện lên màn hình
      'WWW-Authenticate': 'Bearer error="insufficient_user_authentication", acr_values="aal2"',
    };
    return res;
  }
  s.publishing = 2;
  const id = uuid(6800 + ++seq);
  return {
    status: 202,
    body: { id, type: 'POLICY_PUBLISH', status: 'DANG_CHAY', progress: 10 },
    headers: { Location: `/api/v1/jobs/${id}` },
  };
}

/** Saga phát hành giả lập: xong sau hai lần đọc phiên bản (EVT-13, MSG-15-08). */
function readVersion(r: MockRequest): MockResponse {
  const s = state(r);
  if (!s) return notFound();
  if (s.publishing !== null && --s.publishing <= 0) {
    s.publishing = null;
    s.version.status = 'DA_PHAT_HANH';
    s.version.effectiveFrom = '2027-01-01';
    s.version.publishedAt = new Date().toISOString();
    s.version.approvedBy = { id: 'dev-user', name: 'Nguyễn Văn Minh' };
    s.version.approvedAt = s.version.publishedAt;
    s.version.rowVersion++;
  }
  return out(s.version, etag(s.version.rowVersion));
}

function reject(r: MockRequest): MockResponse {
  const s = state(r);
  if (!s) return notFound();
  const blocked = ifMatch(r, s.version.rowVersion);
  if (blocked) return blocked;
  if (s.version.status !== 'CHO_PHE_DUYET') {
    return problem(409, 'Phiên bản không ở trạng thái chờ phê duyệt.', {
      code: 'POLICY-409-NOT-PENDING',
    } as never);
  }
  const reason = ((r.body as { reason?: string })?.reason ?? '').trim();
  if (reason.length < 10) {
    return problem(422, 'Ý kiến từ chối cần ít nhất 10 ký tự.', {
      errors: [{ field: 'reason', message: 'Ý kiến từ chối cần ít nhất 10 ký tự.' }],
    } as never);
  }
  s.version.status = 'NHAP';
  s.version.goldenPassRate = null;
  s.version.rowVersion++;
  return out(s.version, etag(s.version.rowVersion));
}

const findTemplate = (key: string, v: number) =>
  WF_TEMPLATES.find((t) => t.key === key && t.versionNo === v);

function saveBinding(r: MockRequest): MockResponse {
  const s = state(r);
  if (!s) return notFound();
  const kind = r.pathParams['key'];
  const cur = s.bindings.find((b) => b.processKind === kind);
  const blocked = frozen(s) ?? (cur ? ifMatch(r, cur.rowVersion) : null);
  if (blocked) return blocked;
  const b = (r.body ?? {}) as WorkflowBindingUpdate;
  const t = findTemplate(b.templateKey, b.templateVersionNo);
  if (!t) {
    return problem(422, 'Mẫu luồng chưa được phát hành.', {
      code: 'POLICY-422-TEMPLATE-NOT-ACTIVE',
    } as never);
  }
  if (!b.scope?.fromPeriodId) {
    return problem(422, 'Chưa chọn kỳ bắt đầu áp dụng.', {
      errors: [{ field: 'scope.fromPeriodId', message: 'Chọn kỳ bắt đầu áp dụng.' }],
    } as never);
  }
  const lint = lintBinding(t, b.params ?? {});
  if (!lint.passed) {
    const cel = lint.issues.find((i) => i.rule === 'cel-type');
    return problem(422, 'Tham số luồng duyệt chưa hợp lệ.', {
      code: cel ? 'POLICY-422-CEL-TYPE' : 'POLICY-422-WF-PARAMS',
      msg: 'MSG-15-09',
      detail: `Tham số chưa hợp lệ với mẫu ${t.name}: ${lint.issues.map((i) => i.message).join('; ')}.`,
      lint,
    } as never);
  }
  const next: WorkflowBinding = {
    id: cur?.id ?? uuid(7100 + ++seq),
    processKind: kind,
    templateKey: t.key,
    templateVersionNo: t.versionNo,
    versionNo: (cur?.versionNo ?? 0) + (cur ? 0 : 1),
    scope: b.scope,
    params: b.params,
    status: 'NHAP',
    lint,
    templateTests: { passed: t.testCount, total: t.testCount },
    rowVersion: (cur?.rowVersion ?? 0) + 1,
  };
  s.bindings = [...s.bindings.filter((x) => x.processKind !== kind), next];
  touch(s);
  return out(next, etag(next.rowVersion));
}

function simulate(r: MockRequest): MockResponse {
  const b = (r.body ?? {}) as WorkflowSimulateRequest;
  const t = findTemplate(b.templateKey, b.templateVersionNo);
  if (!t) {
    return problem(422, 'Mẫu luồng chưa được phát hành.', {
      code: 'POLICY-422-TEMPLATE-NOT-ACTIVE',
    } as never);
  }
  return out(simulateBinding(t, b.params ?? {}));
}

function submit(r: MockRequest): MockResponse {
  const s = state(r);
  if (!s) return notFound();
  const h = r.headers.get('If-Match');
  if (!h) return problem(428, 'Thiếu điều kiện phiên bản khi ghi');
  if (h.replace(/"/g, '') !== String(s.version.rowVersion)) {
    return problem(409, 'Phiên bản đã được cập nhật. Vui lòng tải lại.', {
      code: 'POLICY-409-VERSION-CONFLICT',
    } as never);
  }
  if (s.version.status !== 'DAT_KIEM_THU') {
    return problem(409, 'Phiên bản chưa đạt toàn bộ test case vàng nên chưa gửi phê duyệt được.', {
      code: 'POLICY-409-NOT-TESTED',
      msg: 'MSG-15-06',
    } as never);
  }
  const b = r.body as { effectivePeriodId?: string; scopes?: unknown[] };
  if (!b?.effectivePeriodId || !b.scopes?.length) {
    return problem(422, 'Chưa chọn kỳ hiệu lực hoặc phạm vi áp dụng.', {
      errors: [{ field: 'effectivePeriodId', message: 'Chọn kỳ hiệu lực' }],
    } as never);
  }
  s.version.status = 'CHO_PHE_DUYET';
  s.version.rowVersion++;
  return out(s.version, etag(s.version.rowVersion));
}

seed();

export const POLICIES_MOCK: MockModule = {
  name: 'policies',
  reset: seed,
  routes: [
    {
      method: 'GET',
      pattern: '/api/v1/policies/packs',
      handle: () => out({ items: packs, nextCursor: null }),
    },
    { method: 'GET', pattern: '/api/v1/policies/test-cases', handle: listTestCases },
    { method: 'POST', pattern: '/api/v1/policies/test-cases', handle: createTestCase },
    {
      method: 'GET',
      pattern: '/api/v1/policies/workflow-templates',
      handle: (r) => {
        const kind = r.params.get('processKind');
        const items = WF_TEMPLATES.filter((t) => !kind || t.processKind === kind).map(
          ({ key, name, versionNo, processKind, testCount }) => ({
            key,
            name,
            versionNo,
            processKind,
            testCount,
            status: 'HIEU_LUC',
          }),
        );
        return out({ items, nextCursor: null });
      },
    },
    {
      method: 'GET',
      pattern: '/api/v1/policies/workflow-templates/:key/versions/:v',
      handle: (r) => {
        const t = templateVersion(r.pathParams['key'], Number(r.pathParams['v']));
        return t ? out(t, etag(t.versionNo)) : problem(404, 'Không tìm thấy mẫu luồng');
      },
    },
    { method: 'POST', pattern: '/api/v1/policies/workflow-bindings/simulate', handle: simulate },
    {
      method: 'GET',
      pattern: '/api/v1/policies/packs/:id',
      handle: (r) => {
        const p = packs.find((x) => x.id === r.pathParams['id']);
        if (!p) return problem(404, 'Không tìm thấy bộ quy định đánh giá');
        const vs = [...versions.values()]
          .filter((s) => s.version.policyPackId === p.id)
          .map(({ version: { manifest: _m, packCode: _c, ...rest } }) => rest);
        return out({ ...p, versions: vs });
      },
    },
    {
      method: 'GET',
      pattern: '/api/v1/policies/packs/:id/versions/:v',
      handle: readVersion,
    },
    { method: 'GET', pattern: '/api/v1/policies/packs/:id/versions/:v/diff', handle: diff },
    {
      method: 'GET',
      pattern: '/api/v1/policies/packs/:id/versions/:v/rules',
      handle: (r) => {
        const s = state(r);
        return s
          ? out({ items: [...s.rules].sort((a, b) => a.evalOrder - b.evalOrder) })
          : notFound();
      },
    },
    {
      method: 'PUT',
      pattern: '/api/v1/policies/packs/:id/versions/:v/rules/:key',
      handle: saveRule,
    },
    {
      method: 'GET',
      pattern: '/api/v1/policies/packs/:id/versions/:v/decision-tables/:key',
      handle: (r) => {
        const t = state(r)?.tables.find((x) => x.tableKey === r.pathParams['key']);
        return t ? out(t, etag(t.rowVersion)) : notFound();
      },
    },
    {
      method: 'PUT',
      pattern: '/api/v1/policies/packs/:id/versions/:v/decision-tables/:key',
      handle: saveTable,
    },
    {
      method: 'GET',
      pattern: '/api/v1/policies/packs/:id/versions/:v/decision-tables/:key/dmn',
      handle: dmn,
    },
    {
      method: 'POST',
      pattern: '/api/v1/policies/packs/:id/versions/:v/evaluate',
      handle: evaluate,
    },
    {
      method: 'POST',
      pattern: '/api/v1/policies/packs/:id/versions/:v/test-runs',
      handle: startRun,
    },
    {
      method: 'GET',
      pattern: '/api/v1/policies/packs/:id/versions/:v/test-runs',
      handle: listRuns,
    },
    {
      method: 'GET',
      pattern: '/api/v1/policies/packs/:id/versions/:v/test-runs/:runId',
      handle: getRun,
    },
    { method: 'POST', pattern: '/api/v1/policies/packs/:id/versions/:v/submit', handle: submit },
    { method: 'POST', pattern: '/api/v1/policies/packs/:id/versions/:v/approve', handle: approve },
    { method: 'POST', pattern: '/api/v1/policies/packs/:id/versions/:v/reject', handle: reject },
    {
      method: 'GET',
      pattern: '/api/v1/policies/packs/:id/versions/:v/workflow-bindings',
      handle: (r) => {
        const s = state(r);
        return s ? out({ items: s.bindings }) : notFound();
      },
    },
    {
      method: 'GET',
      pattern: '/api/v1/policies/packs/:id/versions/:v/workflow-bindings/:key',
      handle: (r) => {
        const b = state(r)?.bindings.find((x) => x.processKind === r.pathParams['key']);
        return b ? out(b, etag(b.rowVersion)) : problem(404, 'Chưa có tham số luồng duyệt');
      },
    },
    {
      method: 'PUT',
      pattern: '/api/v1/policies/packs/:id/versions/:v/workflow-bindings/:key',
      handle: saveBinding,
    },
  ],
};
