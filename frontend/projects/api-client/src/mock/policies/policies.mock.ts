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
  PolicyTestRunSummary,
  shadowedDecisionRows,
} from '../../lib/models/policy';
import { MockModule, MockRequest, MockResponse, ok, problem } from '../mock-router';

/**
 * Giả lập Policy Pack (QT-15; SCR-1501, SCR-1502) theo `policies.yaml`, dữ liệu theo mockup TL-02b
 * Hình 12.3, 12.4: gói khối Đảng có bản 2.1 đang soạn và bản 2.0 đã phát hành; gói Nhà nước có bản 1.4
 * đạt kiểm thử để thử gửi phê duyệt. Kiểm tra cú pháp ở đây chỉ thô (ngoặc cân bằng); máy chủ thật kiểm
 * kiểu bằng cel-java. `manifest.labels`, `manifest.notes`, `manifest.variables` là cách giả lập đặt nhãn
 * hiển thị, ghi chú nguồn dữ liệu và khai báo biến cho gợi ý (suy luận cấu trúc `manifest`).
 */

const out = (body: unknown, headers?: Record<string, string>) => ok(structuredClone(body), headers);
const uuid = (n: number) => `0192a1b0-3c4d-7e5f-8a9b-${String(n).padStart(12, '0')}`;

const P_DANG = uuid(4001);
const P_NN = uuid(4002);

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
      expression: 'math.least(double(nv.so_luong_duyet) / double(nv.ke_hoach), 1.0)',
      resultType: 'DECIMAL',
      legalRef: 'PL 3 mục 1',
      evalOrder: 1,
      overridable: false,
      rowVersion: 2,
    },
    {
      ruleKey: 'B',
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
  runs: (PolicyTestRunSummary & { polls: number })[];
}

let packs: PolicyPack[] = [];
let versions = new Map<string, VersionState>();
let seq = 0;

const vkey = (id: string, v: string) => `${id}@${v}`;

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
      packCode: pack === P_DANG ? 'khoi-dang/quy-dinh-moi' : 'nn-335',
      manifest: structuredClone(MANIFEST),
      rowVersion: 1,
      createdAt: '2026-10-12T09:00:00+07:00',
      ...extra,
    },
    rules: rulesFor(slowVar, dayKind).map((r, i) => ({ ...r, id: uuid(n * 100 + i) })),
    tables: [{ ...xepLoai(), id: uuid(n * 100 + 50) }],
    runs: [],
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
  ];
  for (const s of all) versions.set(vkey(s.version.policyPackId, s.version.version), s);
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
  ];
}

const GOLDEN: GoldenTestCase[] = [
  {
    id: uuid(5017),
    policyPackId: P_DANG,
    caseCode: 'TC-017',
    description: 'Đủ điểm xuất sắc nhưng tỷ lệ việc vượt chỉ 22%',
    input: {},
    expected: { diem: 91.2, muc: 'HTTNV' },
    source: 'VAN_BAN',
    status: 'DA_DUYET',
    rowVersion: 1,
  },
  {
    id: uuid(5003),
    policyPackId: P_DANG,
    caseCode: 'TC-003',
    description: 'Hoàn thành đủ mọi nhiệm vụ đúng hạn',
    input: {},
    expected: { diem: 96.4, muc: 'HTXSNV' },
    source: 'VAN_BAN',
    status: 'DA_DUYET',
    rowVersion: 1,
  },
  {
    id: uuid(5021),
    policyPackId: P_DANG,
    caseCode: 'TC-021',
    description: 'Chậm 6 ngày làm việc ở nhiệm vụ then chốt',
    input: {},
    expected: { diem: 74.5, muc: 'HTNV' },
    source: 'VAN_BAN',
    status: 'DA_DUYET',
    rowVersion: 1,
  },
];

/** Kết quả tính thử theo ca (giả lập đầu ra của cel-java). */
const EVALUATIONS: Record<string, Record<string, unknown>> = {
  'TC-017': {
    tasks: [{ name: 'Nhiệm vụ then chốt 2', A: 1, B: 0.5, C: 0.85, kpi: 0.783 }],
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
    tasks: [{ name: 'Nhiệm vụ then chốt 1', A: 1, B: 1, C: 1, kpi: 1 }],
    groups: [
      { key: 'then_chot', kpi: 0.96, score: 67.2 },
      { key: 'thuong_xuyen', kpi: 0.973, score: 29.2 },
    ],
    diem: 96.4,
    level: 'HTXSNV',
    explanation: 'Đủ mọi điều kiện xuất sắc theo QĐ Điều 11.',
  },
  'TC-021': {
    tasks: [{ name: 'Nhiệm vụ then chốt 3', A: 1, B: 0.75, C: 0.7, kpi: 0.817 }],
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

const notFound = () => problem(404, 'Không tìm thấy phiên bản Policy Pack');
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
    message: `Dòng ${w.row} không bao giờ được chọn vì dòng ${w.by} phía trên luôn thoả mãn trước (hit policy FIRST).`,
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
  const task = (e['tasks'] as Record<string, unknown>[])[0];
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
      nhiem_vu: task['name'],
      A: task['A'],
      B: task['B'],
      C: task['C'],
      kpi_nhiem_vu: task['kpi'],
      nhom: groups,
      diem,
    },
    ratingLevel: e['level'],
    explanation: e['explanation'],
    trace: [
      { ruleKey: 'kpi_nhiem_vu', value: task['kpi'] },
      { ruleKey: 'diem', value: diem },
    ],
  });
}

function startRun(r: MockRequest): MockResponse {
  const s = state(r);
  if (!s) return notFound();
  if (s.version.status !== 'NHAP' && s.version.status !== 'DAT_KIEM_THU') {
    return problem(409, 'Phiên bản này không chạy kiểm thử được ở trạng thái hiện tại.', {
      code: 'POLICY-409-VERSION-FROZEN',
    } as never);
  }
  if (s.version.status === 'NHAP') s.version.status = 'DANG_KIEM_THU';
  const id = uuid(6000 + ++seq);
  s.runs.unshift({
    id,
    runNo: 13 + seq,
    policyPackVersionId: s.version.id,
    version: s.version.version,
    result: 'DANG_CHAY',
    engines: ['CEL_JAVA', 'CEL_JS'],
    total: 48,
    passed: 0,
    failed: 0,
    engineMatch: true,
    startedAt: new Date().toISOString(),
    finishedAt: null,
    polls: 0,
  });
  return {
    status: 202,
    body: { id, type: 'TEST_RUN', status: 'CHO', progress: 0 },
    headers: { Location: `${r.path}/${id}` },
  };
}

function listRuns(r: MockRequest): MockResponse {
  const s = state(r);
  if (!s) return notFound();
  for (const run of s.runs.filter((x) => x.result === 'DANG_CHAY')) {
    if (++run.polls >= 2) {
      Object.assign(run, { result: 'DAT', passed: 48, finishedAt: new Date().toISOString() });
      if (s.version.status === 'DANG_KIEM_THU') {
        s.version.status = 'DAT_KIEM_THU';
        s.version.goldenPassRate = 1;
        s.version.lastTestRunId = run.id;
      }
    }
  }
  const limit = Number(r.params.get('limit') ?? 10) || 10;
  return out({
    items: s.runs.slice(0, limit).map(({ polls: _p, ...x }) => x),
    nextCursor: null,
  });
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
    {
      method: 'GET',
      pattern: '/api/v1/policies/test-cases',
      handle: (r) =>
        out({
          suite: { code: 'bo-ca-mau-ban-to-chuc-v3', total: 48, pending: 0 },
          items: GOLDEN.filter((g) => g.policyPackId === r.params.get('pack')),
          nextCursor: null,
        }),
    },
    {
      method: 'GET',
      pattern: '/api/v1/policies/packs/:id',
      handle: (r) => {
        const p = packs.find((x) => x.id === r.pathParams['id']);
        if (!p) return problem(404, 'Không tìm thấy Policy Pack');
        const vs = [...versions.values()]
          .filter((s) => s.version.policyPackId === p.id)
          .map(({ version: { manifest: _m, packCode: _c, ...rest } }) => rest);
        return out({ ...p, versions: vs });
      },
    },
    {
      method: 'GET',
      pattern: '/api/v1/policies/packs/:id/versions/:v',
      handle: (r) => {
        const s = state(r);
        return s ? out(s.version, etag(s.version.rowVersion)) : notFound();
      },
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
    { method: 'POST', pattern: '/api/v1/policies/packs/:id/versions/:v/submit', handle: submit },
  ],
};
