import type {
  AiSourceList,
  AiSuggestion,
  AnalyticsDashboard,
  SemanticModel,
  TraceLink,
} from '../../lib/models/analytics';
import type { TaskSummary } from '../../lib/models/task';
import { MOCK_ME } from '../mock-session';

/**
 * Dữ liệu mẫu QT-16 (WEB-047, WEB-048) theo Hình 12.8 – 12.10 của TL-02, dời sang các đơn vị của fixture
 * `org.json`: Đảng uỷ khối, kỳ Quý IV/2026 (tuần 6/13), sáu đơn vị cùng khối, 14 việc chậm, 9 hồ sơ chờ
 * thẩm định; chuỗi truy vết từ điểm 91,2 của đồng chí Trần Minh Hoàng tới minh chứng phiên bản 3. Kỳ Quý
 * III/2026 đang khoá nên kho phân tích cập nhật chậm (để thử MSG-16-01).
 */

export const PERIODS = [
  { id: 'p-2026q4', label: 'Quý IV/2026', previous: 'p-2026q3' },
  { id: 'p-2026q3', label: 'Quý III/2026', previous: 'p-2026q2' },
  { id: 'p-2026q2', label: 'Quý II/2026', previous: null },
] as const;
export const CURRENT_PERIOD = 'p-2026q4';
export const STALE_PERIOD = 'p-2026q3';

export interface UnitDef {
  id: string;
  name: string;
  flow: string;
  group: string;
}

export const UNITS: UnitDef[] = [
  { id: 'u-btg', name: 'Ban Tuyên giáo', flow: 'Đảng', group: 'Cơ quan tham mưu' },
  { id: 'u-ubkt', name: 'Uỷ ban Kiểm tra', flow: 'Đảng', group: 'Cơ quan tham mưu' },
  { id: 'u-cs-01', name: 'Đảng bộ cơ sở số 1', flow: 'Đảng', group: 'Đảng bộ cơ sở' },
  { id: 'u-cs-02', name: 'Đảng bộ cơ sở số 2', flow: 'Nhà nước', group: 'Đảng bộ cơ sở' },
  { id: 'u-vp', name: 'Văn phòng Đảng uỷ', flow: 'Đảng', group: 'Cơ quan tham mưu' },
  { id: 'u-btc', name: 'Ban Tổ chức', flow: 'Đảng', group: 'Cơ quan tham mưu' },
];

/** Chỉ số cấp đơn vị; `avg` lấy trung bình khi gộp, `sum` cộng dồn; `progressive` tăng dần theo tuần. */
export const UNIT_MEASURES = {
  'okr.tien_do_o': { agg: 'avg', progressive: true },
  'task.ty_le_dung_han': { agg: 'avg', progressive: false },
  'task.so_viec_cham': { agg: 'sum', progressive: false },
  'appraisal.cho_tham_dinh': { agg: 'sum', progressive: false },
  'appraisal.diem_kpi_tb': { agg: 'avg', progressive: false },
  'evidence.cho_nghiem_thu': { agg: 'sum', progressive: false },
  'task.so_ngay_cham_bq': { agg: 'avg', progressive: false },
  'okr.muc_tu_tin_kr': { agg: 'avg', progressive: false },
  'oversight.so_canh_bao': { agg: 'sum', progressive: false },
} as const;
export type UnitMeasure = keyof typeof UNIT_MEASURES;
const ORDER = Object.keys(UNIT_MEASURES) as UnitMeasure[];

/** Giá trị theo kỳ, đơn vị, cùng thứ tự `UNIT_MEASURES`. */
const RAW: Record<string, Record<string, number[]>> = {
  'p-2026q4': {
    'u-btg': [92, 94, 1, 1, 93.4, 2, 0.5, 86, 0],
    'u-ubkt': [88, 90, 2, 1, 91.8, 3, 1, 82, 1],
    'u-cs-01': [71, 80, 3, 2, 86.2, 6, 2, 70, 1],
    'u-cs-02': [68, 78, 3, 2, 85.1, 5, 2.5, 66, 0],
    'u-vp': [41, 70, 4, 2, 80.6, 7, 4, 52, 2],
    'u-btc': [85, 91, 1, 1, 90.4, 2, 1, 80, 1],
  },
  'p-2026q3': {
    'u-btg': [88, 92, 1, 0, 92.1, 1, 0.5, 84, 0],
    'u-ubkt': [85, 89, 1, 0, 90.6, 2, 1, 80, 0],
    'u-cs-01': [70, 82, 2, 0, 86.8, 3, 1.5, 72, 1],
    'u-cs-02': [66, 80, 2, 0, 84.3, 4, 2, 64, 1],
    'u-vp': [55, 75, 3, 0, 83.2, 4, 3, 60, 1],
    'u-btc': [84, 90, 1, 0, 89.9, 2, 1, 79, 0],
  },
  'p-2026q2': {
    'u-btg': [86, 90, 1, 0, 91.5, 1, 0.5, 82, 0],
    'u-ubkt': [83, 88, 2, 0, 90.1, 2, 1, 78, 0],
    'u-cs-01': [68, 79, 3, 0, 85.9, 4, 2, 70, 0],
    'u-cs-02': [65, 78, 2, 0, 84.0, 3, 2, 63, 1],
    'u-vp': [60, 77, 2, 0, 84.5, 3, 2.5, 62, 0],
    'u-btc': [82, 89, 1, 0, 89.2, 2, 1, 77, 0],
  },
};

export function unitValues(periodId: string, unitId: string): Record<UnitMeasure, number> | null {
  const v = RAW[periodId]?.[unitId];
  return v
    ? (Object.fromEntries(ORDER.map((m, i) => [m, v[i]])) as Record<UnitMeasure, number>)
    : null;
}

export interface PersonFact {
  unitId: string;
  name: string;
  scoreId: string;
  score: number;
  onTime: number;
  rating: string;
}

const R = {
  xs: 'Hoàn thành xuất sắc nhiệm vụ',
  tot: 'Hoàn thành tốt nhiệm vụ',
  ht: 'Hoàn thành nhiệm vụ',
  kht: 'Không hoàn thành nhiệm vụ',
};

const PEOPLE: [string, string, number, number, string][] = [
  ['u-btc', 'Trần Minh Hoàng', 91.2, 89, R.tot],
  ['u-btc', 'Nguyễn Thị Mai', 93.5, 95, R.xs],
  ['u-btc', 'Phạm Văn Long', 86.4, 88, R.tot],
  ['u-btg', 'Lê Hồng Nhung', 95.1, 97, R.xs],
  ['u-btg', 'Đỗ Quang Vinh', 91.7, 92, R.tot],
  ['u-ubkt', 'Bùi Thị Hạnh', 92.3, 91, R.tot],
  ['u-ubkt', 'Vũ Đức Thắng', 91.3, 89, R.tot],
  ['u-cs-01', 'Hoàng Văn Sơn', 88.0, 82, R.tot],
  ['u-cs-01', 'Đinh Thị Thu', 84.4, 78, R.ht],
  ['u-cs-02', 'Ngô Minh Đức', 86.0, 80, R.tot],
  ['u-cs-02', 'Trịnh Văn Bảo', 84.2, 76, R.ht],
  ['u-vp', 'Lê Thu Trang', 82.5, 72, R.ht],
  ['u-vp', 'Mai Xuân Phúc', 78.7, 68, R.ht],
  ['u-vp', 'Cao Thị Yến', 48.0, 40, R.kht],
];

const slug = (s: string) =>
  s
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/đ/gi, 'd')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-');

/** Cán bộ của đơn vị theo kỳ (kỳ trước điểm thấp hơn đôi chút). */
export function people(periodId: string): PersonFact[] {
  const shift = periodId === CURRENT_PERIOD ? 0 : periodId === 'p-2026q3' ? 0.8 : 1.4;
  const q = periodId.replace('p-', '');
  return PEOPLE.map(([unitId, name, score, onTime, rating]) => ({
    unitId,
    name,
    scoreId: `ss-${q}-${slug(name)}`,
    score: Math.round((score - shift) * 10) / 10,
    onTime,
    rating,
  }));
}

export const HOANG_SCORE_ID = 'ss-2026q4-tran-minh-hoang';
/** Cán bộ có minh chứng liên kết ngang của đơn vị khác (mắt xích bị che, MSG-16-06). */
export const MASKED_SCORE_ID = 'ss-2026q4-le-thu-trang';

const ALL_UNIT = ORDER;
const PERSON = ['appraisal.diem_kpi_tb', 'task.ty_le_dung_han'];

export const SEMANTIC_MODEL: SemanticModel = {
  version: '2026.4',
  measures: [
    {
      name: 'okr.tien_do_o',
      title: 'Tiến độ mục tiêu O',
      type: 'number',
      unit: '%',
      drillable: true,
    },
    {
      name: 'task.ty_le_dung_han',
      title: 'Tỷ lệ nhiệm vụ đúng hạn',
      type: 'number',
      unit: '%',
      drillable: true,
    },
    { name: 'task.so_viec_cham', title: 'Việc chậm cần chỉ đạo', type: 'count', drillable: true },
    {
      name: 'appraisal.cho_tham_dinh',
      title: 'Hồ sơ chờ thẩm định',
      type: 'count',
      drillable: false,
    },
    {
      name: 'appraisal.diem_kpi_tb',
      title: 'Điểm KPI trung bình',
      type: 'number',
      unit: 'điểm',
      drillable: true,
    },
    {
      name: 'appraisal.phan_bo_muc',
      title: 'Phân bố mức xếp loại',
      type: 'count',
      drillable: false,
    },
    {
      name: 'evidence.cho_nghiem_thu',
      title: 'Minh chứng chờ nghiệm thu',
      type: 'count',
      drillable: true,
    },
    {
      name: 'task.so_ngay_cham_bq',
      title: 'Số ngày chậm bình quân',
      type: 'number',
      unit: 'ngày',
      drillable: true,
    },
    {
      name: 'okr.muc_tu_tin_kr',
      title: 'Mức tự tin kết quả then chốt',
      type: 'number',
      unit: '%',
      drillable: false,
    },
    {
      name: 'oversight.so_canh_bao',
      title: 'Cảnh báo bất thường',
      type: 'count',
      drillable: false,
    },
  ],
  dimensions: [
    {
      name: 'org_unit.name',
      title: 'Đơn vị',
      type: 'string',
      supportedMeasures: [...ALL_UNIT, 'appraisal.phan_bo_muc'],
    },
    {
      name: 'period.label',
      title: 'Kỳ',
      type: 'string',
      supportedMeasures: [...ALL_UNIT, 'appraisal.phan_bo_muc'],
    },
    {
      name: 'subject_group.name',
      title: 'Khối đối tượng',
      type: 'string',
      supportedMeasures: [...ALL_UNIT, 'appraisal.phan_bo_muc'],
    },
    {
      name: 'flow.name',
      title: 'Luồng',
      type: 'string',
      supportedMeasures: [...ALL_UNIT, 'appraisal.phan_bo_muc'],
    },
    {
      name: 'task_type.name',
      title: 'Loại nhiệm vụ',
      type: 'string',
      supportedMeasures: ['task.ty_le_dung_han', 'task.so_viec_cham', 'task.so_ngay_cham_bq'],
    },
    {
      name: 'time.week',
      title: 'Tuần',
      type: 'time',
      supportedMeasures: ['okr.tien_do_o', 'task.ty_le_dung_han', 'task.so_viec_cham'],
    },
    {
      name: 'rating.level',
      title: 'Mức xếp loại',
      type: 'string',
      supportedMeasures: ['appraisal.phan_bo_muc'],
    },
    { name: 'person.name', title: 'Cán bộ', type: 'string', supportedMeasures: PERSON },
    // chiều kỹ thuật để đi xuống (mã đơn vị, mã hồ sơ điểm); trình dựng không liệt kê
    {
      name: 'org_unit.id',
      title: 'Mã đơn vị',
      type: 'string',
      supportedMeasures: [...ALL_UNIT, ...PERSON],
    },
    {
      name: 'score_snapshot.id',
      title: 'Mã hồ sơ điểm',
      type: 'string',
      supportedMeasures: PERSON,
    },
  ],
};

/** Việc cần chỉ đạo (SCR-1601 trường 9): ba việc quá hạn trong phạm vi và hai việc sắp tới hạn. */
export function directiveTasks(): TaskSummary[] {
  const base = {
    status: 'DANG_THUC_HIEN',
    role: 'CHU_TRI',
    periodId: CURRENT_PERIOD,
    rowVersion: 3,
    source: 'KE_HOACH',
    plannedQuantity: 1,
    doneQuantity: 0,
    unit: 'báo cáo',
    openBlockerCount: 0,
  } as const;
  return [
    {
      ...base,
      id: 't-cd-01',
      code: 'NV-2026-0412',
      title: 'Báo cáo tổng kết công tác văn phòng năm 2026',
      overdue: true,
      dueDate: '2026-11-06',
      progress: 35,
      assignerName: MOCK_ME.name,
      unitName: 'Văn phòng Đảng uỷ',
      taskGroup: 'THEN_CHOT',
      workingDaysLeft: -6,
    },
    {
      ...base,
      id: 't-cd-02',
      code: 'NV-2026-0398',
      title: 'Rà soát hồ sơ đảng viên chuyển sinh hoạt',
      overdue: true,
      dueDate: '2026-11-10',
      progress: 60,
      assignerName: 'Phạm Quang Huy',
      unitName: 'Đảng bộ cơ sở số 2',
      taskGroup: 'THUONG_XUYEN',
      workingDaysLeft: -4,
    },
    {
      ...base,
      id: 't-cd-03',
      code: 'NV-2026-0405',
      title: 'Kế hoạch kiểm tra chuyên đề quý IV',
      overdue: true,
      dueDate: '2026-11-13',
      progress: 50,
      assignerName: MOCK_ME.name,
      unitName: 'Đảng bộ cơ sở số 1',
      taskGroup: 'THEN_CHOT',
      workingDaysLeft: -1,
    },
    {
      ...base,
      id: 't-cd-04',
      code: 'NV-2026-0420',
      title: 'Tổng hợp ý kiến góp ý dự thảo báo cáo chính trị',
      overdue: false,
      dueDate: '2026-11-18',
      progress: 70,
      assignerName: MOCK_ME.name,
      unitName: 'Ban Tuyên giáo',
      taskGroup: 'THEN_CHOT',
      workingDaysLeft: 2,
    },
    {
      ...base,
      id: 't-cd-05',
      code: 'NV-2026-0431',
      title: 'Cập nhật dữ liệu đảng viên trên hệ thống',
      overdue: false,
      dueDate: '2026-11-20',
      progress: 80,
      assignerName: MOCK_ME.name,
      unitName: 'Ban Tổ chức',
      taskGroup: 'THUONG_XUYEN',
      workingDaysLeft: 4,
    },
  ];
}

/** Việc do người khác giao: lãnh đạo không có quyền giao lại (403 của OPA, BR-16-07). */
export const NOT_MY_ASSIGNMENT = 't-cd-02';

export function weeklySummary(): AiSuggestion {
  return {
    id: 'ai-tt-2026w46',
    useCase: 'TOM_TAT_VAN_BAN',
    kind: 'TOM_TAT_TUAN',
    model: 'viettel-llm-vi-2026.3',
    createdAt: '2026-11-16T06:00:00+07:00',
    aiGenerated: true,
    label: 'Nội dung do AI tạo',
    outputText: null,
    items: [
      'Tiến độ mục tiêu O của khối tăng 3% so với tuần trước; Ban Tuyên giáo dẫn đầu với 92%.',
      'Văn phòng Đảng uỷ đạt 41%, thấp nhất khối, có 2 nhiệm vụ then chốt quá hạn.',
      '9 hồ sơ chờ thẩm định, hạn hoàn thành ngày 10/01/2027.',
    ],
    sources: [
      { kind: 'TRUY_VAN', title: 'Số liệu tiến độ ngày 14/11/2026', ref: 'okr.tien_do_o' },
      { kind: 'DU_LIEU', title: 'Danh sách nhiệm vụ quá hạn', ref: 'task.so_viec_cham' },
    ],
    confidence: null,
    decision: null,
    blocked: null,
  } as AiSuggestion;
}

export const SUMMARY_SOURCES: AiSourceList = {
  suggestionId: 'ai-tt-2026w46',
  items: [
    {
      kind: 'TRUY_VAN',
      title: 'Tiến độ mục tiêu O theo đơn vị',
      ref: 'okr.tien_do_o',
      excerpt: 'Ban Tuyên giáo 92%; Văn phòng Đảng uỷ 41%',
      asOf: '2026-11-14T23:55:00+07:00',
    },
    {
      kind: 'DU_LIEU',
      title: 'Nhiệm vụ quá hạn của Văn phòng Đảng uỷ',
      ref: 'task.so_viec_cham',
      excerpt: '2 nhiệm vụ then chốt quá hạn',
      asOf: '2026-11-14T23:55:00+07:00',
    },
    {
      kind: 'DU_LIEU',
      title: 'Hồ sơ chờ thẩm định',
      ref: 'appraisal.cho_tham_dinh',
      excerpt: '9 hồ sơ, hạn 10/01/2027',
      asOf: '2026-11-14T23:55:00+07:00',
    },
  ],
} as AiSourceList;

export function dashboards(): AnalyticsDashboard[] {
  return [
    {
      id: 'db-bang-bi-thu',
      title: 'Bảng của Bí thư',
      ownerUserId: MOCK_ME.id,
      measures: [
        'okr.tien_do_o',
        'task.ty_le_dung_han',
        'appraisal.diem_kpi_tb',
        'appraisal.phan_bo_muc',
      ],
      dimensions: ['period.label', 'rating.level'],
      layout: [
        {
          id: 'w1',
          x: 0,
          y: 0,
          w: 3,
          h: 2,
          measure: 'okr.tien_do_o',
          dimensions: [],
          chartType: 'THE_SO',
          filters: [],
          drillDown: true,
        },
        {
          id: 'w2',
          x: 3,
          y: 0,
          w: 3,
          h: 2,
          measure: 'task.ty_le_dung_han',
          dimensions: [],
          chartType: 'THE_SO',
          filters: [],
          drillDown: true,
        },
        {
          id: 'w3',
          x: 6,
          y: 0,
          w: 6,
          h: 4,
          measure: 'appraisal.diem_kpi_tb',
          dimensions: ['period.label'],
          chartType: 'COT',
          filters: [{ member: 'flow.name', operator: 'equals', values: ['Đảng'] }],
          drillDown: true,
        },
        {
          id: 'w4',
          x: 0,
          y: 2,
          w: 6,
          h: 4,
          measure: 'appraisal.phan_bo_muc',
          dimensions: ['rating.level'],
          chartType: 'THANH',
          filters: [],
          drillDown: false,
        },
      ],
      shareScope: 'RIENG',
      sharedUnitIds: [],
      semanticModelVersion: '2026.4',
      rowVersion: 3,
      updatedAt: '2026-11-16T09:30:00+07:00',
    },
    {
      id: 'db-khoi-tham-muu',
      title: 'Theo dõi khối cơ quan tham mưu',
      ownerUserId: 'u-pham-quang-huy',
      measures: ['okr.tien_do_o'],
      dimensions: ['org_unit.name'],
      layout: [
        {
          id: 'w1',
          x: 0,
          y: 0,
          w: 12,
          h: 4,
          measure: 'okr.tien_do_o',
          dimensions: ['org_unit.name'],
          chartType: 'THANH',
          filters: [
            { member: 'subject_group.name', operator: 'equals', values: ['Cơ quan tham mưu'] },
          ],
          drillDown: true,
        },
      ],
      shareScope: 'DON_VI',
      sharedUnitIds: ['u-btc'],
      semanticModelVersion: '2026.4',
      rowVersion: 1,
      updatedAt: '2026-11-12T15:10:00+07:00',
    },
  ];
}

export const OWNER_NAMES: Record<string, string> = {
  [MOCK_ME.id]: MOCK_ME.name,
  'u-pham-quang-huy': 'Phạm Quang Huy',
};

/** Mắt xích của chuỗi truy vết đồng chí Trần Minh Hoàng (Hình 12.10). */
export function hoangChain(): TraceLink[] {
  return [
    {
      objectType: 'DIEM',
      id: HOANG_SCORE_ID,
      label: 'Điểm quý IV/2026 – Trần Minh Hoàng',
      value: '91,2',
      unitName: 'Ban Tổ chức',
      masked: false,
      details: {
        person: 'Trần Minh Hoàng',
        period: 'Quý IV/2026',
        rating: R.tot,
        note: 'Tỷ lệ nhiệm vụ vượt mục tiêu 2/9 (22%) thấp hơn ngưỡng 30% nên mức xếp loại là Hoàn thành tốt nhiệm vụ.',
      },
    },
    {
      objectType: 'CAU_PHAN',
      id: 'cp-2026q4-hoang-then-chot',
      label: 'Nhiệm vụ then chốt × 70',
      value: '63,6',
      unitName: 'Ban Tổ chức',
      masked: false,
      details: { groupKpi: 0.908, weight: 70, taskCount: 4 },
    },
    {
      objectType: 'KPI_NHIEM_VU',
      id: 'kpi-2026q4-hoang-nv2',
      label: 'Nhiệm vụ then chốt 2',
      value: '0,783',
      unitName: 'Ban Tổ chức',
      masked: false,
      details: {
        task: 'Báo cáo sơ kết công tác tổ chức cơ sở đảng',
        a: 1,
        b: 0.5,
        c: 0.85,
        kpi: 0.783,
        policyPack: 'Bộ tiêu chí khối Đảng, phiên bản 2.0',
        bNote: 'Nghiệm thu ngày 18/12: phải sửa nội dung cơ bản',
        cNote: 'Hạn 04/12, hoàn thành 09/12, chậm 3 ngày làm việc do chủ quan',
      },
    },
    {
      objectType: 'SAN_PHAM',
      id: 'sp-2026q4-bc-so-ket',
      label: 'Báo cáo sơ kết',
      value: '1/1',
      unitName: 'Ban Tổ chức',
      masked: false,
    },
    {
      objectType: 'MINH_CHUNG',
      id: 'mc-2026q4-bc-so-ket',
      label: 'BC-so-ket-TCCS-v3.pdf',
      status: 'DA_XAC_NHAN',
      versionNo: 3,
      unitName: 'Ban Tổ chức',
      masked: false,
      msg: 'MSG-16-07',
      details: {
        fileName: 'BC-so-ket-TCCS-v3.pdf',
        sizeBytes: 1_887_437,
        objectId: '0192a1c3-0000-7000-8000-000000000001',
        retainUntil: '2037-12-31',
        scan: 'SACH',
        documentNumber: '112-BC/BTC',
        submittedBy: 'Trần Minh Hoàng',
        confirmedBy: 'Nguyễn Thị Mai',
        versions: [
          {
            versionNo: 1,
            status: 'YEU_CAU_BO_SUNG',
            at: '2026-12-08T10:12:00+07:00',
            note: 'Thiếu phụ lục số liệu',
          },
          {
            versionNo: 2,
            status: 'YEU_CAU_BO_SUNG',
            at: '2026-12-12T16:40:00+07:00',
            note: 'Phải sửa nội dung cơ bản',
          },
          {
            versionNo: 3,
            status: 'DA_XAC_NHAN',
            at: '2026-12-18T09:05:00+07:00',
            note: 'Được tính điểm',
          },
        ],
      },
    },
    {
      objectType: 'KE_HOACH',
      id: 'kh-2026q4-phong-tong-hop',
      label: 'Kế hoạch quý IV, phiên bản 2',
      status: 'DA_DUYET',
      unitName: 'Ban Tổ chức',
      masked: false,
    },
    {
      objectType: 'MUC_TIEU',
      id: 'o1-2026-phong-tong-hop',
      label: 'Mục tiêu O1, kết quả then chốt 1.2 của Phòng Tổng hợp',
      unitName: 'Ban Tổ chức',
      masked: false,
    },
    {
      objectType: 'CAN_CU',
      id: 'cc-nq-dai-hoi-khoi',
      label: 'Nghị quyết Đại hội Đảng bộ khối',
      masked: false,
    },
  ];
}

/** Chuỗi rút gọn cho cán bộ khác; Lê Thu Trang có minh chứng của Ban Tuyên giáo liên kết ngang (bị che). */
export function genericChain(p: PersonFact, unitName: string, periodLabel: string): TraceLink[] {
  const k = p.scoreId.replace(/^ss-/, '');
  const masked = p.scoreId === MASKED_SCORE_ID;
  const fmt = (n: number, d = 1) =>
    n.toLocaleString('vi-VN', { minimumFractionDigits: d, maximumFractionDigits: d });
  return [
    {
      objectType: 'DIEM',
      id: p.scoreId,
      label: `Điểm ${periodLabel.toLowerCase()} – ${p.name}`,
      value: fmt(p.score),
      unitName,
      masked: false,
      details: { person: p.name, period: periodLabel, rating: p.rating },
    },
    {
      objectType: 'CAU_PHAN',
      id: `cp-${k}`,
      label: 'Nhiệm vụ then chốt × 70',
      value: fmt(p.score * 0.7),
      unitName,
      masked: false,
    },
    {
      objectType: 'KPI_NHIEM_VU',
      id: `kpi-${k}`,
      label: 'Nhiệm vụ then chốt 1',
      value: fmt(p.score / 100, 3),
      unitName,
      masked: false,
    },
    {
      objectType: 'SAN_PHAM',
      id: `sp-${k}`,
      label: 'Báo cáo chuyên đề',
      value: '1/1',
      unitName,
      masked: false,
    },
    masked
      ? {
          objectType: 'MINH_CHUNG',
          id: `mc-${k}`,
          label: 'Minh chứng',
          unitName: 'Ban Tuyên giáo',
          masked: true,
          msg: 'MSG-16-06',
        }
      : {
          objectType: 'MINH_CHUNG',
          id: `mc-${k}`,
          label: 'Báo cáo chuyên đề.pdf',
          status: 'DA_XAC_NHAN',
          versionNo: 1,
          unitName,
          masked: false,
          msg: 'MSG-16-07',
          details: {
            fileName: 'Báo cáo chuyên đề.pdf',
            sizeBytes: 640_000,
            objectId: '0192a1c3-0000-7000-8000-000000000001',
            scan: 'SACH',
            versions: [
              {
                versionNo: 1,
                status: 'DA_XAC_NHAN',
                at: '2026-11-10T09:00:00+07:00',
                note: 'Được tính điểm',
              },
            ],
          },
        },
    {
      objectType: 'KE_HOACH',
      id: `kh-${k}`,
      label: 'Kế hoạch quý của đơn vị',
      status: 'DA_DUYET',
      unitName,
      masked: false,
    },
    {
      objectType: 'MUC_TIEU',
      id: `o-${k}`,
      label: `Mục tiêu năm 2026 của ${unitName}`,
      unitName,
      masked: false,
    },
    {
      objectType: 'CAN_CU',
      id: 'cc-nq-dai-hoi-khoi',
      label: 'Nghị quyết Đại hội Đảng bộ khối',
      masked: false,
    },
  ] as TraceLink[];
}
