import { AppraisalProfileRow } from '../../lib/models/appraisal';
import { MOCK_ME } from '../mock-session';

/**
 * Hồ sơ đánh giá của hai kỳ đã khoá trong giả lập (Quý I/2026, Quý II/2026, xem `periods.fixture.ts`),
 * dùng chung cho hậu kiểm: khiếu nại (SCR-1201, SCR-1202), cảnh báo (SCR-1401), mở lại kỳ (SCR-1301) và
 * sổ cái (SCR-1302). Mã hồ sơ dạng UUID như `appraisal.appraisal_profile.id` của máy chủ (hợp đồng
 * `Uuid`), để giả lập bắt được lỗi gửi chuỗi tự gõ thay cho mã hồ sơ (HT-34). Các dòng này hiện trong
 * `GET /appraisals/profiles?period=…&status=DA_KHOA` (chỉ khi lọc đúng trạng thái), không vào hàng đợi
 * thẩm định hay quyết định.
 */
/** Mã dạng UUID trong dải giả lập của hậu kiểm (`01928f3e-…-3f2d9c1d` + 4 chữ số hex). */
const uuid = (tail: string) => `01928f3e-7b1a-7c3e-9a51-3f2d9c1d${tail}`;

export const LOCKED_PROFILE = {
  hoangQ2: uuid('1301'),
  maiQ2: uuid('1302'),
  lanQ2: uuid('1303'),
  meQ2: uuid('1304'),
  binhQ1: uuid('1311'),
  khanhQ1: uuid('1312'),
  meQ1: uuid('1313'),
} as const;

export const LOCKED_Q2 = {
  id: 'p-2026q2',
  label: 'Quý II/2026',
  startDate: '2026-04-01',
  endDate: '2026-06-30',
};
export const LOCKED_Q1 = {
  id: 'p-2026q1',
  label: 'Quý I/2026',
  startDate: '2026-01-01',
  endDate: '2026-03-31',
};

const TONG_HOP = { id: uuid('13a1'), name: 'Phòng Tổng hợp' };
const VAN_PHONG = { id: uuid('13a2'), name: 'Văn phòng Đảng uỷ' };

interface Seed {
  id: string;
  person: { id: string; name: string };
  jobTitle: string;
  unit: { id: string; name: string };
  period: typeof LOCKED_Q1;
  score: number;
  rating: 'HTXSNV' | 'HTTNV' | 'HTNV' | 'KHTNV';
}

const SEEDS: Seed[] = [
  {
    id: LOCKED_PROFILE.hoangQ2,
    person: { id: 'u-tran-minh-hoang', name: 'Trần Minh Hoàng' },
    jobTitle: 'Trưởng phòng Tổng hợp',
    unit: TONG_HOP,
    period: LOCKED_Q2,
    score: 91.2,
    rating: 'HTTNV',
  },
  {
    id: LOCKED_PROFILE.maiQ2,
    person: { id: 'u-nguyen-thi-mai', name: 'Nguyễn Thị Mai' },
    jobTitle: 'Phó Trưởng phòng',
    unit: TONG_HOP,
    period: LOCKED_Q2,
    score: 79.4,
    rating: 'HTNV',
  },
  {
    id: LOCKED_PROFILE.lanQ2,
    person: { id: 'u-pham-thi-lan', name: 'Phạm Thị Lan' },
    jobTitle: 'Chuyên viên',
    unit: VAN_PHONG,
    period: LOCKED_Q2,
    score: 82,
    rating: 'HTTNV',
  },
  {
    id: LOCKED_PROFILE.meQ2,
    person: { id: MOCK_ME.id, name: MOCK_ME.name },
    jobTitle: 'Chuyên viên',
    unit: VAN_PHONG,
    period: LOCKED_Q2,
    score: 88.5,
    rating: 'HTTNV',
  },
  {
    id: LOCKED_PROFILE.binhQ1,
    person: { id: 'u-nguyen-van-binh', name: 'Nguyễn Văn Bình' },
    jobTitle: 'Chuyên viên',
    unit: TONG_HOP,
    period: LOCKED_Q1,
    score: 84.6,
    rating: 'HTTNV',
  },
  {
    id: LOCKED_PROFILE.khanhQ1,
    person: { id: 'u-vu-quoc-khanh', name: 'Vũ Quốc Khánh' },
    jobTitle: 'Chuyên viên',
    unit: VAN_PHONG,
    period: LOCKED_Q1,
    score: 68.5,
    rating: 'HTNV',
  },
  {
    id: LOCKED_PROFILE.meQ1,
    person: { id: MOCK_ME.id, name: MOCK_ME.name },
    jobTitle: 'Chuyên viên',
    unit: VAN_PHONG,
    period: LOCKED_Q1,
    score: 79,
    rating: 'HTNV',
  },
];

/** Dòng danh sách (`AppraisalProfileRow`) của các hồ sơ kỳ đã khoá; mỗi lần gọi trả bản sao mới. */
export function lockedProfileRows(): AppraisalProfileRow[] {
  return SEEDS.map((s) => ({
    id: s.id,
    person: { ...s.person },
    jobTitle: s.jobTitle,
    orgUnit: { ...s.unit },
    period: { ...s.period },
    periodKind: 'QUY',
    flow: 'DANG',
    status: 'DA_KHOA',
    isHead: false,
    totalScore: s.score,
    autoRatingCode: s.rating,
    levels: { selfProposed: s.rating, appraised: s.rating, proposed: s.rating, decided: s.rating },
    differsExplained: false,
    noSelfAssessment: false,
    anomalyHints: [],
    rowVersion: 7,
  }));
}

/** Tên hiển thị "Họ tên · Chức vụ" của hồ sơ kỳ đã khoá (bảng ảnh hưởng tính thử của SCR-1301). */
export const LOCKED_PROFILE_NAMES: Record<string, string> = Object.fromEntries(
  SEEDS.map((s) => [s.id, `${s.person.name} · ${s.jobTitle}`]),
);
