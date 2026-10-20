import {
  AppraisalAnomalyHint,
  AppraisalProfile,
  AppraisalScoreTaskLineView,
  AppraisalScoreView,
  AppraisalSelfAssessmentView,
  GeneralCriteriaResult,
  RATING_LEVEL_LABEL,
  RatingLevel,
} from '../../lib/models/appraisal';
import { QualityLevel } from '../../lib/models/evidence';

/**
 * Hàng đợi thẩm định giả lập của SCR-0902 (WEB-036): bảy hồ sơ `CHO_THAM_DINH` của Ban Tổ chức Đảng uỷ
 * khối quý IV/2026 theo mockup TL-02b Hình B1.4. Hồ sơ được sinh từ bảng gọn dưới đây để giữ dữ liệu
 * nhất quán: KPI nhiệm vụ = (A + B + C) / 3 với A = 1, điểm nhóm = KPI nhóm × điểm tối đa (BR-09-01,
 * BR-09-03). Hồ sơ Lê Thu Hà (`…0a21`) có sẵn trong `appraisals.json`, ở đây chỉ bổ sung B, C tự nhận.
 */

const ID = '01928f3e-7b1a-7c3e-9a51-3f2d9c1b';
const UNIT = { id: `${ID}0101`, name: 'Ban Tổ chức Đảng uỷ khối' };
const PERIOD = {
  id: `${ID}0001`,
  label: 'Quý IV/2026',
  startDate: '2026-10-01',
  endDate: '2026-12-31',
};

/** Người thẩm định của bản giả lập (AC-07). */
export const MOCK_APPRAISER = { id: `${ID}0207`, name: 'Lương Thị Thanh' };

/** Một nhiệm vụ then chốt: B, C tự nhận và B, C nghiệm thu; căn cứ khi khác. */
interface TaskSpec {
  title: string;
  selfB: QualityLevel;
  b: QualityLevel;
  selfC: number;
  c: number;
  note?: string;
}

interface Spec {
  key: string;
  name: string;
  title: string;
  self: RatingLevel | null;
  auto: RatingLevel;
  regular: number;
  tasks: TaskSpec[];
  claimed?: number;
  exceedRatio?: number;
  downgrade?: string;
  anomaly?: AppraisalAnomalyHint;
  returnCount?: number;
  /** Người thẩm định của bản giả lập đã nghiệm thu một nhiệm vụ của hồ sơ (BR-09-14). */
  acceptedByViewer?: boolean;
  submittedAt: string;
  hash: string;
}

const SPECS: Spec[] = [
  {
    key: '0a31',
    name: 'Trần Minh Hoàng',
    title: 'Trưởng phòng Tổng hợp',
    self: 'HTXSNV',
    auto: 'HTTNV',
    regular: 0.92,
    claimed: 93.4,
    exceedRatio: 0.22,
    downgrade: 'Thiếu điều kiện 30% nhiệm vụ vượt mục tiêu',
    anomaly: { code: 'DAU_HIEU_1', label: 'Tự chấm cao 3 quý' },
    submittedAt: '2027-01-04T09:12:00+07:00',
    hash: '9c1ea07bd2f4c6e8a1b3d5f7092c4e6a8b0d2f4a6c8e0b2d4f6a8c0e2b4d6f8a',
    tasks: [
      { title: 'Đề án chuyển đổi số công tác tổ chức', selfB: 0.75, b: 0.75, selfC: 0.95, c: 0.95 },
      {
        title: 'Báo cáo sơ kết chuyên đề tổ chức cơ sở đảng',
        selfB: 0.75,
        b: 0.5,
        selfC: 0.85,
        c: 0.85,
        note: 'Chất lượng: phải sửa nội dung cơ bản (biên bản nghiệm thu 18/12)',
      },
      {
        title: 'Thẩm định hồ sơ quy hoạch cán bộ',
        selfB: 1,
        b: 1,
        selfC: 1,
        c: 0.85,
        note: 'Tiến độ: chậm 3 ngày làm việc do chủ quan, không có miễn trừ',
      },
      { title: 'Hướng dẫn kiểm điểm chi bộ', selfB: 1, b: 1, selfC: 1, c: 1 },
    ],
  },
  {
    key: '0a32',
    name: 'Phạm Đức Long',
    title: 'Chuyên viên chính',
    self: 'HTTNV',
    auto: 'HTNV',
    regular: 0.67,
    submittedAt: '2027-01-04T15:40:00+07:00',
    hash: '1f3a5c7e9b0d2f4a6c8e0a2c4e6b8d0f2a4c6e8b0d2f4a6c8e0b2d4f6a8c0e2b',
    tasks: [
      {
        title: 'Cập nhật cơ sở dữ liệu đảng viên',
        selfB: 0.75,
        b: 0.75,
        selfC: 1,
        c: 0.7,
        note: 'Tiến độ: chậm 5 ngày làm việc, đã nhắc hạn hai lần',
      },
      { title: 'Báo cáo thống kê đảng viên năm', selfB: 0.75, b: 0.75, selfC: 0.85, c: 0.85 },
    ],
  },
  {
    key: '0a33',
    name: 'Nguyễn Hải Yến',
    title: 'Chuyên viên',
    self: 'HTTNV',
    auto: 'HTTNV',
    regular: 0.85,
    submittedAt: '2027-01-05T08:05:00+07:00',
    hash: '2a4c6e8b0d2f4a6c8e0b2d4f6a8c0e2b4d6f8a0c2e4b6d8f0a2c4e6b8d0f2a4c',
    tasks: [
      { title: 'Tổng hợp kết quả phân loại tổ chức đảng', selfB: 0.75, b: 0.75, selfC: 1, c: 1 },
      { title: 'Chuẩn bị hồ sơ hội nghị cán bộ', selfB: 0.75, b: 0.75, selfC: 0.85, c: 0.85 },
    ],
  },
  {
    key: '0a34',
    name: 'Đỗ Quang Vinh',
    title: 'Trưởng phòng Cơ sở đảng',
    self: 'HTXSNV',
    auto: 'HTXSNV',
    regular: 0.93,
    exceedRatio: 0.35,
    submittedAt: '2027-01-03T16:20:00+07:00',
    hash: '3b5d7f9a1c3e5b7d9f1a3c5e7b9d1f3a5c7e9b1d3f5a7c9e1b3d5f7a9c1e3b5d',
    tasks: [
      { title: 'Đề án kiện toàn tổ chức cơ sở đảng', selfB: 1, b: 1, selfC: 0.95, c: 0.95 },
      { title: 'Hướng dẫn đại hội chi bộ', selfB: 1, b: 1, selfC: 0.85, c: 0.85 },
    ],
  },
  {
    key: '0a35',
    name: 'Hoàng Thị Mai',
    title: 'Chuyên viên',
    self: 'HTNV',
    auto: 'HTNV',
    regular: 0.6,
    acceptedByViewer: true,
    submittedAt: '2027-01-05T10:30:00+07:00',
    hash: '4c6e8a0b2d4f6a8c0e2b4d6f8a0c2e4b6d8f0a2c4e6b8d0f2a4c6e8b0d2f4a6c',
    tasks: [
      { title: 'Soạn quy chế phối hợp với Văn phòng', selfB: 0.5, b: 0.5, selfC: 0.7, c: 0.7 },
      { title: 'Lưu trữ hồ sơ kiểm tra', selfB: 0.75, b: 0.75, selfC: 0.75, c: 0.75 },
    ],
  },
  {
    key: '0a36',
    name: 'Vũ Ngọc Sơn',
    title: 'Phó trưởng phòng Cán bộ',
    self: 'HTTNV',
    auto: 'HTTNV',
    regular: 0.9,
    returnCount: 2,
    submittedAt: '2027-01-05T11:45:00+07:00',
    hash: '5d7f9b1a3c5e7d9f1b3a5c7e9d1f3b5a7c9e1d3f5b7a9c1e3d5f7b9a1c3e5d7f',
    tasks: [
      {
        title: 'Rà soát quy hoạch cán bộ nhiệm kỳ 2026-2031',
        selfB: 0.75,
        b: 0.75,
        selfC: 1,
        c: 1,
      },
      { title: 'Lấy phiếu tín nhiệm giữa nhiệm kỳ', selfB: 0.75, b: 0.75, selfC: 0.85, c: 0.85 },
    ],
  },
];

/** B, C tự nhận của hồ sơ Lê Thu Hà có sẵn trong fixture (khớp nghiệm thu). */
const LE_THU_HA_CLAIMS = [
  { taskId: `${ID}0c21`, qualityLevel: 0.75 as QualityLevel, delayDays: 4, delayFactor: 0.8 },
];

/** Hồ sơ mà người thẩm định của bản giả lập đã nghiệm thu (403 MSG-09-08). */
export const ACCEPTED_BY_VIEWER = new Set(
  SPECS.filter((s) => s.acceptedByViewer).map((s) => ID + s.key),
);

const round = (n: number, d: number) => Math.round(n * 10 ** d) / 10 ** d;
const kpiOf = (b: number, c: number) => round((1 + b + c) / 3, 3);

function criteria(): GeneralCriteriaResult {
  const labels = [
    'Bản lĩnh chính trị, tư tưởng',
    'Phẩm chất đạo đức, lối sống',
    'Ý thức tổ chức kỷ luật',
    'Tác phong, lề lối làm việc',
    'Trách nhiệm nêu gương',
    'Đoàn kết nội bộ',
    'Năng lực đổi mới, sáng tạo',
    'Thực hiện điều đảng viên không được làm',
    'Dám nghĩ, dám làm, dám chịu trách nhiệm',
  ];
  return {
    stage: 'TU_DANH_GIA',
    items: labels.map((label, i) => ({ criteriaCode: `TCC-0${i + 1}`, label, met: true })),
    complianceRatio: 1,
    level: 'THUC_HIEN_TOT',
  };
}

function build(s: Spec): { profile: AppraisalProfile; view: AppraisalScoreView } {
  const id = ID + s.key;
  const personId = `${ID}02${s.key.slice(2)}`;
  const person = { id: personId, name: s.name };
  const tasks: AppraisalScoreTaskLineView[] = s.tasks.map((t, i) => ({
    taskId: `${ID}0c${s.key.slice(2)}${i}`,
    title: t.title,
    approvedQuantity: 1,
    plannedQuantity: 1,
    a: 1,
    b: t.b,
    c: t.c,
    kpi: kpiOf(t.b, t.c),
    ...(t.note ? { acceptanceNote: t.note } : {}),
  }));
  const keyKpi = round(tasks.reduce((sum, t) => sum + t.kpi, 0) / tasks.length, 3);
  const keyScore = round(keyKpi * 70, 1);
  const regScore = round(s.regular * 30, 1);
  const total = round(keyScore + regScore, 1);
  const claimsKpi =
    s.tasks.reduce((sum, t) => sum + kpiOf(t.selfB, t.selfC), 0) / Math.max(s.tasks.length, 1);
  const sa: AppraisalSelfAssessmentView = {
    versionNo: (s.returnCount ?? 0) + 1,
    formCode: 'MAU_1B',
    formVersion: '1.0',
    selfRatingCode: s.self,
    content: `Hoàn thành các nhiệm vụ then chốt được giao trong quý; ${s.name} tự nhận còn hạn chế về tiến độ một số báo cáo và đã đề ra hướng khắc phục.`,
    ...(s.self === 'HTXSNV'
      ? {
          breakthrough:
            'Đề xuất và triển khai quy trình số hoá hồ sơ tổ chức, rút ngắn thời gian tổng hợp báo cáo.',
        }
      : {}),
    ...(s.self && s.self !== s.auto
      ? {
          reason:
            'Đồng chí cho rằng các nhiệm vụ then chốt đều đạt chất lượng và tiến độ như tự nhận khi nộp minh chứng.',
        }
      : {}),
    claims: s.tasks.map((t, i) => ({
      taskId: tasks[i].taskId,
      qualityLevel: t.selfB,
      delayDays: t.selfC >= 1 ? 0 : Math.round((1 - t.selfC) * 20),
      delayFactor: t.selfC,
    })),
    claimedScore: s.claimed ?? round(round(claimsKpi, 3) * 70 + regScore, 1),
    contentHash: s.hash,
    submittedAt: s.submittedAt,
    signedAt: s.submittedAt.replace(/:00\+/, ':40+'),
  };
  const profile: AppraisalProfile = {
    id,
    person,
    jobTitle: s.title,
    orgUnit: UNIT,
    period: PERIOD,
    periodKind: 'QUY',
    flow: 'DANG',
    availableFlows: ['DANG'],
    status: 'CHO_THAM_DINH',
    isHead: false,
    isLeadership: s.title.startsWith('Trưởng') || s.title.startsWith('Phó'),
    policyVersion: 'khối Đảng, phiên bản 2.0',
    currentSnapshotId: `${ID}0d${s.key.slice(2)}`,
    finalRatingCode: null,
    selfAssessDue: '2027-01-05',
    workingDaysLeft: 0,
    returnCount: s.returnCount ?? 0,
    returnLimit: 2,
    noSelfAssessment: false,
    submittedAt: s.submittedAt,
    generalCriteria: [criteria()],
    selfAssessment: sa as AppraisalProfile['selfAssessment'],
    submissionSignature: {
      signedAt: sa.signedAt!,
      provider: 'MYSIGN',
      contentHash: s.hash,
      valid: true,
    },
    ratingTrail: s.self
      ? [
          {
            id: `${ID}0f${s.key.slice(2)}`,
            levelKind: 'TU_DE_XUAT',
            ratingCode: s.self,
            actor: person,
            actorRole: 'staff',
            isCurrent: true,
            differsFromPrev: s.self !== s.auto,
            explanationRequired: s.self !== s.auto,
            isBulkDecision: false,
            ...(sa.reason ? { reason: sa.reason } : {}),
            proposedAt: s.submittedAt,
            signedAt: sa.signedAt,
          },
        ]
      : [],
    anomalyHints: s.anomaly ? [s.anomaly] : [],
    rowVersion: 4,
  };
  const view: AppraisalScoreView = {
    profileId: id,
    flow: 'DANG',
    snapshot: {
      id: profile.currentSnapshotId!,
      profileId: id,
      flow: 'DANG',
      snapshotKind: 'TAM_TINH',
      policyVersion: 'khối Đảng, phiên bản 2.0',
      inputHash: s.hash.split('').reverse().join(''),
      snapshotHash: s.hash.slice(32) + s.hash.slice(0, 32),
      totalScore: total,
      regularScore: null,
      keyTaskScore: null,
      generalCriteriaScore: null,
      kpiScore: null,
      exceedRatio: s.exceedRatio ?? 0,
      objectiveRatio: 1,
      generalCriteriaLevel: 'THUC_HIEN_TOT',
      autoRatingCode: s.auto,
      ruleChecks: [],
      downgradeReasons: s.downgrade
        ? [
            {
              fromRating: 'HTXSNV',
              toRating: s.auto,
              text: s.downgrade,
              legalRef: 'Điều 11 Quy định 366',
            },
          ]
        : [],
      groups: [
        {
          code: 'THEN_CHOT',
          label: 'Nhiệm vụ then chốt',
          kpi: keyKpi,
          maxScore: 70,
          score: keyScore,
          tasks,
        },
        {
          code: 'THUONG_XUYEN',
          label: 'Nhiệm vụ thường xuyên',
          kpi: s.regular,
          maxScore: 30,
          score: regScore,
          tasks: [],
        },
      ],
      computedAt: '2027-01-02T01:40:00+07:00',
    },
    explanation: {
      systemLevel: s.auto,
      conditions: [],
      text: `Đạt ${total.toLocaleString('vi-VN')} điểm; mức hệ thống tính: ${RATING_LEVEL_LABEL[s.auto]}. Căn cứ: Bộ quy định đánh giá khối Đảng, phiên bản 2.0.`,
      legalRef: 'Điều 11 Quy định 366',
    },
  };
  return { profile, view };
}

/** Hồ sơ và ảnh chụp điểm của hàng đợi; gọi lại mỗi lần bản giả lập được đặt lại. */
export function reviewQueue(): {
  profiles: AppraisalProfile[];
  views: Record<string, AppraisalScoreView>;
} {
  const built = SPECS.map(build);
  return {
    profiles: built.map((b) => b.profile),
    views: Object.fromEntries(built.map((b) => [b.profile.id, b.view])),
  };
}

/** Bổ sung B, C tự nhận cho hồ sơ có sẵn trong fixture. */
export function patchFixtureProfile(p: AppraisalProfile): void {
  if (p.id === `${ID}0a21` && p.selfAssessment) {
    (p.selfAssessment as AppraisalSelfAssessmentView).claims = LE_THU_HA_CLAIMS;
    (p.selfAssessment as AppraisalSelfAssessmentView).claimedScore = 84.5;
  }
}
