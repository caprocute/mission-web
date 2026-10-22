import {
  AnnualRatingExplanation,
  AnnualRatingRow,
  AppraisalRuleCheck,
  ProspectGroupBoard,
  ProspectGroupBoardInput,
  ProspectGroupCode,
  ProspectGroupEntry,
  RATING_LEVEL_LABEL,
  RatingLevel,
} from '../../lib/models/appraisal';
import { MockRequest, MockResponse, MockRoute, ok, problem } from '../mock-router';

/**
 * Xếp loại năm SCR-1001 và phân nhóm triển vọng SCR-1002 (QT-10, WEB-039) theo mockup TL-02b: 48 hồ sơ
 * năm 2026 luồng Đảng của Ban Tổ chức Đảng uỷ khối; 3 hồ sơ bị chặn mức xuất sắc vì có quý không hoàn
 * thành nhiệm vụ (BR-10-03), 1 hồ sơ có hai quý liên tiếp không hoàn thành nhiệm vụ (BR-10-04).
 *
 * Quy tắc máy chủ giả lập: `flag=lien_thong` chỉ trả hồ sơ có `blockReason` hoặc `reassignFlag`; lập đề
 * xuất bố trí lại chỉ với hồ sơ có cờ (409), một lần (409), `If-Match` (428, 409). Bảng phân nhóm chỉ
 * mở ở kỳ năm (409 MSG-10-05, BR-10-07); lưu và trình kiểm thứ tự không trùng trong nhóm (422), người
 * không hoàn thành nhiệm vụ năm hoặc có cờ bố trí lại không vào Nhóm 1, Nhóm 2 (422 MSG-10-06,
 * BR-10-09), lý do 20 đến 1.000 ký tự khi khác gợi ý (422 MSG-10-07, BR-10-08), chỉ sửa khi ở `NHAP`
 * (409), `If-Match` (428, 409). Mọi đơn vị trong đường dẫn bảng phân nhóm được coi là đơn vị của người
 * xem (Ban Tổ chức Đảng uỷ khối).
 */

const ID = '01928f3e-7b1a-7c3e-9a51-3f2d9c1b';
/** Kỳ năm của dữ liệu giả lập kỳ (`periods.fixture.ts`). */
export const MOCK_ANNUAL_PERIOD = 'p-2026';
const BAN = { id: `${ID}0101`, name: 'Ban Tổ chức Đảng uỷ khối' };
const TONG_HOP = { id: `${ID}0111`, name: 'Phòng Tổng hợp' };
const CO_SO = { id: `${ID}0112`, name: 'Phòng Cơ sở đảng' };
const CAN_BO = { id: `${ID}0113`, name: 'Phòng Cán bộ' };
const UNITS = [TONG_HOP, CO_SO, CAN_BO];
const QUARTERS = ['I', 'II', 'III', 'IV'].map((q, i) => ({
  id: `${ID}000${i + 1}`,
  label: `Quý ${q}/2026`,
  startDate: `2026-${String(i * 3 + 1).padStart(2, '0')}-01`,
  endDate: ['2026-03-31', '2026-06-30', '2026-09-30', '2026-12-31'][i],
}));
const QUARTER_POLICY = ['Khối Đảng v1.4', 'Khối Đảng v1.4', 'Khối Đảng v1.4', 'Khối Đảng v2.0'];

interface Seed {
  name: string;
  title: string;
  unit: { id: string; name: string };
  quarters: RatingLevel[];
  scores: number[];
  self: RatingLevel;
  system: RatingLevel;
  /** Tỷ lệ nhiệm vụ vượt mục tiêu cả năm. */
  exceed: number;
  excellentYears?: number;
}

const X: RatingLevel = 'HTXSNV';
const T: RatingLevel = 'HTTNV';
const N: RatingLevel = 'HTNV';
const K: RatingLevel = 'KHTNV';

const SEEDS: Seed[] = [
  {
    name: 'Đỗ Quang Vinh',
    title: 'Trưởng phòng Cơ sở đảng',
    unit: CO_SO,
    quarters: [X, X, T, X],
    scores: [94, 93.2, 90.1, 95.1],
    self: X,
    system: X,
    exceed: 0.36,
    excellentYears: 3,
  },
  {
    name: 'Trần Minh Hoàng',
    title: 'Trưởng phòng Tổng hợp',
    unit: TONG_HOP,
    quarters: [T, X, T, T],
    scores: [91.8, 88.6, 90.2, 91],
    self: X,
    system: T,
    exceed: 0.24,
  },
  {
    name: 'Lê Thu Hà',
    title: 'Phó Trưởng phòng Tổng hợp',
    unit: TONG_HOP,
    quarters: [T, T, T, T],
    scores: [86.4, 85.2, 86.8, 85.6],
    self: T,
    system: T,
    exceed: 0.18,
  },
  {
    name: 'Phạm Đức Long',
    title: 'Chuyên viên chính',
    unit: CAN_BO,
    quarters: [X, K, T, N],
    scores: [92.4, 74.6, 90.9, 78.9],
    self: X,
    system: T,
    exceed: 0.31,
  },
  {
    name: 'Bùi Văn Khánh',
    title: 'Chuyên viên',
    unit: TONG_HOP,
    quarters: [N, K, K, K],
    scores: [66, 52.4, 50.2, 53.8],
    self: N,
    system: K,
    exceed: 0,
  },
  {
    name: 'Hoàng Thị Mai',
    title: 'Chuyên viên',
    unit: CO_SO,
    quarters: [N, N, T, N],
    scores: [66.1, 65.8, 78.4, 65.3],
    self: N,
    system: N,
    exceed: 0.05,
  },
  {
    name: 'Vũ Ngọc Sơn',
    title: 'Phó Trưởng phòng Cán bộ',
    unit: CAN_BO,
    quarters: [T, T, X, T],
    scores: [86.2, 87, 90.6, 87],
    self: T,
    system: T,
    exceed: 0.22,
  },
  {
    name: 'Nguyễn Hải Yến',
    title: 'Chuyên viên',
    unit: TONG_HOP,
    quarters: [T, T, T, T],
    scores: [82, 81.4, 83.2, 82.2],
    self: T,
    system: T,
    exceed: 0.12,
  },
];

const FAMILY = ['Nguyễn', 'Trần', 'Lê', 'Phạm', 'Hoàng', 'Vũ', 'Đặng', 'Bùi', 'Đỗ', 'Hồ'];
const MIDDLE = ['Văn', 'Thị', 'Minh', 'Đức', 'Thu', 'Quốc', 'Ngọc', 'Hữu'];
const GIVEN = ['An', 'Bình', 'Châu', 'Dũng', 'Giang', 'Hải', 'Hương', 'Khoa', 'Lan', 'Linh'];

/** 40 hồ sơ còn lại: 6 xuất sắc, 28 tốt, 5 hoàn thành, 1 hồ sơ tốt có quý III không hoàn thành. */
function generated(): Seed[] {
  const levels: RatingLevel[] = [
    ...Array<RatingLevel>(6).fill(X),
    ...Array<RatingLevel>(28).fill(T),
    ...Array<RatingLevel>(5).fill(N),
  ];
  const base: Record<RatingLevel, number> = { HTXSNV: 90.5, HTTNV: 80, HTNV: 64, KHTNV: 52 };
  const seeds: Seed[] = levels.map((level, i) => {
    const s = Math.round((base[level] + ((i * 37) % 90) / 10) * 10) / 10;
    return {
      name: `${FAMILY[(i + 3) % FAMILY.length]} ${MIDDLE[(i * 5) % MIDDLE.length]} ${GIVEN[(i * 3) % GIVEN.length]}`,
      title: i % 5 === 0 ? 'Chuyên viên chính' : 'Chuyên viên',
      unit: UNITS[i % UNITS.length],
      quarters: [level, level, level, level],
      scores: [s, s, s, s],
      self: level,
      system: level,
      exceed: level === X ? 0.32 : 0.15,
      excellentYears: level === X ? 1 + (i % 2) : undefined,
    };
  });
  seeds.push({
    name: 'Đặng Hữu Phúc',
    title: 'Chuyên viên',
    unit: CO_SO,
    quarters: [T, T, K, T],
    scores: [84, 83.6, 70.2, 84.6],
    self: T,
    system: N,
    exceed: 0.1,
  });
  return seeds;
}

const avg = (xs: number[]) => Math.round((xs.reduce((a, b) => a + b, 0) / xs.length) * 10) / 10;
const vn = (n: number) => n.toFixed(1).replace('.', ',');
const quarterName = (i: number) => `quý ${['I', 'II', 'III', 'IV'][i]}`;

function consecutiveK(q: RatingLevel[]): [number, number] | null {
  for (let i = 0; i + 1 < q.length; i++) if (q[i] === K && q[i + 1] === K) return [i, i + 1];
  return null;
}

let seeds: Seed[] = [];
let rows: AnnualRatingRow[] = [];
let board: ProspectGroupBoard | null = null;
let taskSeq = 0;

function linkageNote(s: Seed): string {
  if (consecutiveK(s.quarters))
    return 'Hai quý liên tiếp không hoàn thành nhiệm vụ: đề xuất bố trí lại (QĐ khoản 15.6)';
  if (s.quarters.includes(K))
    return 'Có quý không hoàn thành nhiệm vụ: không xếp loại xuất sắc năm (QĐ khoản 15.5)';
  if (s.self === X && s.system !== X && s.exceed < 0.3)
    return 'Chưa đạt điều kiện 30% nhiệm vụ vượt mục tiêu cả năm';
  return '';
}

function load(): void {
  seeds = [...SEEDS, ...generated()];
  rows = seeds.map((s, i) => {
    const hasK = s.quarters.includes(K);
    const pair = consecutiveK(s.quarters);
    return {
      id: `${ID}${(0xb01 + i).toString(16).padStart(4, '0')}`,
      profileId: `${ID}${(0xa01 + i).toString(16).padStart(4, '0')}`,
      person: { id: `${ID}${(0x401 + i).toString(16).padStart(4, '0')}`, name: s.name },
      orgUnit: s.unit,
      flow: 'DANG',
      profileStatus: 'CHO_THAM_DINH',
      quarterRatings: [...s.quarters],
      annualScore: avg(s.scores),
      selfProposedLevel: s.self,
      systemLevel: s.system,
      ratingCode: null,
      hasKhtnvQuarter: hasK,
      consecutiveKhtnv: !!pair,
      blockReason: hasK ? 'CO_QUY_KHTNV' : null,
      reassignFlag: !!pair,
      relocationProposed: false,
      linkageNote: linkageNote(s),
      rowVersion: 2,
    } satisfies AnnualRatingRow;
  });
  board = null;
}

export function resetAnnual(): void {
  load();
  taskSeq = 0;
}

const all = (): AnnualRatingRow[] => {
  if (!rows.length) load();
  return rows;
};
const seedOf = (r: AnnualRatingRow) => seeds[all().indexOf(r)];

function explanation(r: AnnualRatingRow): AnnualRatingExplanation {
  const s = seedOf(r);
  const score = r.annualScore ?? 0;
  const conditions: AppraisalRuleCheck[] = [
    {
      ratingCode: X,
      label: 'Không có quý nào không hoàn thành nhiệm vụ',
      met: !r.hasKhtnvQuarter,
      legalRef: 'QĐ khoản 15.5',
    },
    {
      ratingCode: X,
      label: 'Điểm năm từ 90 trở lên',
      met: score >= 90,
      actual: vn(score),
      threshold: '90',
      legalRef: 'QĐ Điều 11',
    },
    {
      ratingCode: X,
      label: 'Tỷ lệ nhiệm vụ vượt mục tiêu cả năm từ 30% trở lên',
      met: s.exceed >= 0.3,
      actual: `${Math.round(s.exceed * 100)}%`,
      threshold: '30%',
      legalRef: 'QĐ Điều 11',
    },
    {
      ratingCode: T,
      label: 'Hoàn thành 100% mục tiêu năm',
      met: s.system !== K && s.system !== N,
      legalRef: 'QĐ Điều 11',
    },
  ];
  const level = RATING_LEVEL_LABEL[s.system];
  const kq = s.quarters.map((q, i) => (q === K ? quarterName(i) : null)).filter(Boolean);
  const pair = consecutiveK(s.quarters);
  let text: string;
  if (pair)
    text =
      `Cán bộ ${s.name} có hai quý liên tiếp không hoàn thành nhiệm vụ (${quarterName(pair[0])}, ` +
      `${quarterName(pair[1])}), cần lập đề xuất bố trí lại công việc (QĐ khoản 15.6). Hệ thống đề xuất ${level}.`;
  else if (kq.length)
    text =
      `Hồ sơ có ${kq.join(', ')} xếp loại Không hoàn thành nhiệm vụ nên không được xếp loại Hoàn thành ` +
      `xuất sắc nhiệm vụ năm (QĐ khoản 15.5). Hệ thống đề xuất ${level}.`;
  else if (score >= 90 && s.exceed < 0.3)
    text =
      `Hệ thống đề xuất ${level}: điểm năm ${vn(score)} đủ mức xuất sắc nhưng tỷ lệ nhiệm vụ vượt mục tiêu ` +
      `cả năm ${Math.round(s.exceed * 100)}% chưa đạt 30% (QĐ Điều 11), nên chuyển xuống mức liền kề.`;
  else text = `Hệ thống đề xuất ${level}: điểm năm ${vn(score)} và các điều kiện của mức đều đạt.`;
  return {
    annualRatingId: r.id,
    yearCalcMethod: 'TRUNG_BINH_QUY',
    formula: `(${s.scores.map(vn).join(' + ')}) / 4 = ${vn(score)}`,
    annualScore: score,
    exceedRatio: s.exceed,
    quarters: s.quarters.map((q, i) => ({
      period: QUARTERS[i],
      ratingCode: q,
      totalScore: s.scores[i],
      policyVersion: QUARTER_POLICY[i],
    })),
    conditions,
    systemLevel: s.system,
    text,
    legalRef: pair ? 'QĐ khoản 15.6' : kq.length ? 'QĐ khoản 15.5' : 'QĐ Điều 11',
    policyVersion: 'Khối Đảng v2.0',
  };
}

/** Nhóm gợi ý (BR-10-08): theo mức năm, điểm năm và cờ liên thông; hệ thống không tự phân nhóm. */
function suggest(r: AnnualRatingRow): ProspectGroupCode {
  const level = r.ratingCode ?? r.systemLevel;
  const score = r.annualScore ?? 0;
  if (r.reassignFlag || level === K) return 'NHOM_4';
  if (r.blockReason || level === N) return 'NHOM_3';
  if (level === X) return score >= 92 ? 'NHOM_1' : 'NHOM_2';
  return score >= 82 ? 'NHOM_2' : 'NHOM_3';
}

function summary(r: AnnualRatingRow): string {
  const s = seedOf(r);
  if (r.reassignFlag) return 'Hai quý liên tiếp không hoàn thành nhiệm vụ';
  const years = s.excellentYears ? ` · ${s.excellentYears} năm liền xuất sắc` : '';
  return `${s.title}${years}`.slice(0, 80);
}

const SON_REASON =
  'Đồng chí Vũ Ngọc Sơn đã hoàn thành chương trình lý luận chính trị cao cấp và được quy hoạch chức danh Trưởng phòng.';

function initialBoard(): ProspectGroupBoard {
  const entries: ProspectGroupEntry[] = all().map((r) => {
    const suggested = suggest(r);
    const moved = r.person.name === 'Vũ Ngọc Sơn';
    return {
      id: r.id.replace(/0b(..)$/, '0c$1'),
      person: r.person,
      summary: summary(r),
      annualRatingCode: r.ratingCode ?? r.systemLevel,
      reassignFlag: r.reassignFlag,
      groupCode: moved ? 'NHOM_1' : suggested,
      suggestedGroup: suggested,
      rankInGroup: null,
      ...(moved ? { reason: SON_REASON, trainingPlanRef: 'KH-BD-2027-04' } : {}),
    };
  });
  // Thứ tự gợi ý: các thẻ của mockup trước, còn lại theo điểm năm giảm dần.
  const order = new Map(
    all().map((r, i) => [r.person.id, i < SEEDS.length ? 1000 - i : (r.annualScore ?? 0)]),
  );
  for (const g of ['NHOM_1', 'NHOM_2', 'NHOM_3', 'NHOM_4'] as ProspectGroupCode[])
    entries
      .filter((e) => e.groupCode === g)
      .sort((a, b) => order.get(b.person.id)! - order.get(a.person.id)!)
      .forEach((e, i) => (e.rankInGroup = i + 1));
  return {
    periodId: MOCK_ANNUAL_PERIOD,
    orgUnit: BAN,
    status: 'NHAP',
    policyVersion: 'Khối Đảng v2.0',
    entries,
    rowVersion: 5,
  };
}

const GROUP_NAME: Record<ProspectGroupCode, string> = {
  NHOM_1: 'Nhóm 1',
  NHOM_2: 'Nhóm 2',
  NHOM_3: 'Nhóm 3',
  NHOM_4: 'Nhóm 4',
};

function versionGuard(req: MockRequest, rowVersion: number): MockResponse | null {
  const match = req.headers.get('If-Match');
  if (!match)
    return problem(
      428,
      'Dữ liệu trên màn hình đã cũ. Vui lòng tải lại rồi thực hiện lại thao tác.',
    );
  if (match.replace(/"/g, '') !== String(rowVersion))
    return problem(409, 'Dữ liệu đã được người khác cập nhật, vui lòng tải lại', {
      code: 'APPRAISAL-409-VERSION-CONFLICT',
    });
  return null;
}

function boardFor(req: MockRequest): ProspectGroupBoard | MockResponse {
  const periodId = req.pathParams['periodId'];
  if (periodId !== MOCK_ANNUAL_PERIOD) {
    if (/^p-\d{4}q\d$/.test(periodId))
      return problem(409, 'Phân nhóm triển vọng chỉ thực hiện ở kỳ năm (QĐ khoản 12.1.5).', {
        code: 'APPRAISAL-409-PROSPECT-QUARTER',
      });
    return problem(404, 'Không tìm thấy kỳ đánh giá', { code: 'APPRAISAL-404' });
  }
  board ??= initialBoard();
  return board;
}

/** Kiểm danh sách gửi lên (BR-10-08, BR-10-09, thứ tự không trùng); `null` khi hợp lệ. */
/** `draft`: lưu nháp không bắt buộc lý do (hợp đồng WEB-063), chỉ kiểm khi trình. */
function validate(
  b: ProspectGroupBoard,
  input: ProspectGroupBoardInput,
  draft = false,
): MockResponse | null {
  const byPerson = new Map(b.entries.map((e) => [e.person.id, e]));
  const errors: { field: string; message: string; code?: string }[] = [];
  const ranks = new Set<string>();
  let notEligible = false;
  input.entries.forEach((x, i) => {
    const e = byPerson.get(x.personId);
    if (!e) {
      errors.push({ field: `entries[${i}].personId`, message: 'Cán bộ không thuộc danh sách.' });
      return;
    }
    const key = `${x.groupCode}|${x.rankInGroup}`;
    if (!(x.rankInGroup >= 1) || ranks.has(key))
      errors.push({
        field: `entries[${i}].rankInGroup`,
        message: `Thứ tự của ${e.person.name} trùng trong ${GROUP_NAME[x.groupCode]}.`,
      });
    ranks.add(key);
    if (
      (x.groupCode === 'NHOM_1' || x.groupCode === 'NHOM_2') &&
      (e.annualRatingCode === K || e.reassignFlag)
    ) {
      notEligible = true;
      errors.push({
        field: `entries[${i}].groupCode`,
        code: 'MSG-10-06',
        message: e.reassignFlag
          ? `Cán bộ ${e.person.name} có hai quý liên tiếp không hoàn thành nhiệm vụ nên không thể đưa vào ${GROUP_NAME[x.groupCode]}.`
          : `Cán bộ ${e.person.name} xếp loại năm ${RATING_LEVEL_LABEL[K]} nên không thể đưa vào ${GROUP_NAME[x.groupCode]}.`,
      });
    }
    const len = x.reason?.trim().length ?? 0;
    if (x.groupCode !== e.suggestedGroup && (draft ? len > 1000 : len < 20 || len > 1000))
      errors.push({
        field: `entries[${i}].reason`,
        code: 'MSG-10-07',
        message: `Đồng chí đã xếp ${e.person.name} khác nhóm gợi ý. Vui lòng ghi lý do điều chỉnh (20 đến 1.000 ký tự).`,
      });
  });
  if (!errors.length) return null;
  return problem(422, 'Danh sách phân nhóm chưa hợp lệ', {
    code: notEligible ? 'APPRAISAL-422-PROSPECT-NOT-ELIGIBLE' : 'APPRAISAL-422-REASON-REQUIRED',
    errors,
  });
}

function save(req: MockRequest): MockResponse {
  const b = boardFor(req);
  if (!('entries' in b)) return b;
  if (b.status !== 'NHAP')
    return problem(409, 'Danh sách đã trình nên không sửa được', {
      code: 'APPRAISAL-409-INVALID-STATUS',
    });
  const blocked = versionGuard(req, b.rowVersion);
  if (blocked) return blocked;
  const input = (req.body ?? { entries: [] }) as ProspectGroupBoardInput;
  const invalid = validate(b, input, true);
  if (invalid) return invalid;
  const now = new Date().toISOString();
  for (const x of input.entries) {
    const e = b.entries.find((y) => y.person.id === x.personId)!;
    e.groupCode = x.groupCode;
    e.rankInGroup = x.rankInGroup;
    if (x.reason?.trim()) e.reason = x.reason.trim();
    else delete e.reason;
    if (x.trainingPlanRef?.trim()) e.trainingPlanRef = x.trainingPlanRef.trim();
    else delete e.trainingPlanRef;
    e.decidedAt = now;
  }
  b.rowVersion++;
  return ok(structuredClone(b), { ETag: `"${b.rowVersion}"` });
}

function submit(req: MockRequest): MockResponse {
  const b = boardFor(req);
  if (!('entries' in b)) return b;
  if (b.status !== 'NHAP')
    return problem(409, 'Danh sách đã được trình', { code: 'APPRAISAL-409-INVALID-STATUS' });
  const blocked = versionGuard(req, b.rowVersion);
  if (blocked) return blocked;
  const invalid = validate(b, {
    entries: b.entries.map((e) => ({
      personId: e.person.id,
      groupCode: e.groupCode,
      rankInGroup: e.rankInGroup ?? 0,
      reason: e.reason,
    })),
  });
  if (invalid) return invalid;
  b.status = 'DA_TRINH';
  b.rowVersion++;
  return ok(structuredClone(b), { ETag: `"${b.rowVersion}"` });
}

/** Tuyến `/appraisals/annual` và `/appraisals/prospect-groups` của giả lập MOD-04. */
export function annualRoutes(): MockRoute[] {
  const find = (req: MockRequest) =>
    all().find((r) => r.id === req.pathParams['id']) ??
    problem(404, 'Không tìm thấy hồ sơ năm', { code: 'APPRAISAL-404' });
  return [
    {
      method: 'GET',
      pattern: '/api/v1/appraisals/annual',
      handle: (req) => {
        const period = req.params.get('period');
        if (!period)
          return problem(400, 'Thiếu kỳ năm', {
            errors: [{ field: 'period', message: 'Chọn kỳ năm.' }],
          });
        const unit = req.params.get('unit');
        const flow = req.params.get('flow');
        const q = req.params.get('q')?.toLocaleLowerCase('vi');
        const scope = period === MOCK_ANNUAL_PERIOD ? all() : [];
        const inUnit = scope
          .filter((r) => !unit || unit === BAN.id || r.orgUnit?.id === unit)
          .filter((r) => !flow || r.flow === flow);
        const items = inUnit
          .filter(
            (r) => req.params.get('flag') !== 'lien_thong' || !!r.blockReason || r.reassignFlag,
          )
          .filter((r) => !q || r.person.name.toLocaleLowerCase('vi').includes(q));
        const limit = Math.min(Number(req.params.get('limit') ?? 50), 200);
        const start = Number(req.params.get('cursor') ?? 0);
        return ok({
          items: structuredClone(items.slice(start, start + limit)),
          nextCursor: start + limit < items.length ? String(start + limit) : null,
          counts: {
            profilesCreated: inUnit.length,
            profilesTotal: inUnit.length,
            blockedExcellent: inUnit.filter((r) => r.blockReason).length,
            reassignProposed: inUnit.filter((r) => r.reassignFlag).length,
            awaitingProspectGrouping: inUnit.length,
          },
          policyNote:
            'Quy định đánh giá khối Đảng phiên bản 2.0; quý I đến quý III giữ phiên bản 1.4 theo kỳ đã khoá.',
        });
      },
    },
    {
      method: 'GET',
      pattern: '/api/v1/appraisals/annual/:id/explanation',
      handle: (req) => {
        const r = find(req);
        return 'person' in r ? ok(explanation(r)) : r;
      },
    },
    {
      method: 'POST',
      pattern: '/api/v1/appraisals/annual/:id/reassignment-proposal',
      handle: (req) => {
        const r = find(req);
        if (!('person' in r)) return r;
        if (!r.reassignFlag)
          return problem(409, 'Hồ sơ không thuộc trường hợp đề xuất bố trí lại', {
            code: 'APPRAISAL-409-NO-REASSIGN-FLAG',
          });
        if (r.relocationProposed)
          return problem(409, 'Đề xuất bố trí lại của hồ sơ này đã được lập', {
            code: 'APPRAISAL-409-REASSIGN-EXISTS',
          });
        const blocked = versionGuard(req, r.rowVersion);
        if (blocked) return blocked;
        const note = (req.body as { note?: string } | null)?.note ?? '';
        if (note.length > 1000)
          return problem(422, 'Dữ liệu không hợp lệ', {
            errors: [{ field: 'note', message: 'Ghi chú tối đa 1.000 ký tự.' }],
          });
        r.relocationProposed = true;
        r.rowVersion++;
        const taskId = `${ID}${(0xe01 + taskSeq++).toString(16).padStart(4, '0')}`;
        return {
          status: 201,
          body: { annualRatingId: r.id, taskId, relocationProposed: true },
          headers: { Location: `/api/v1/tasks/${taskId}`, ETag: `"${r.rowVersion}"` },
        };
      },
    },
    {
      method: 'GET',
      pattern: '/api/v1/appraisals/prospect-groups/:periodId/:orgUnitId',
      handle: (req) => {
        const b = boardFor(req);
        return 'entries' in b ? ok(structuredClone(b), { ETag: `"${b.rowVersion}"` }) : b;
      },
    },
    {
      method: 'PUT',
      pattern: '/api/v1/appraisals/prospect-groups/:periodId/:orgUnitId',
      handle: save,
    },
    {
      method: 'POST',
      pattern: '/api/v1/appraisals/prospect-groups/:periodId/:orgUnitId/submit',
      handle: submit,
    },
  ];
}
