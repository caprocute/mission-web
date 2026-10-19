import {
  AppraisalProfileView,
  AppraisalRatingProposal,
  AppraisalRuleCheck,
  AppraisalScoreView,
} from '../../lib/models/appraisal';
import fixture from './appraisals.json';
import { MOCK_APPRAISER } from './review-queue';

/**
 * Hồ sơ giả lập của SCR-0903 Phiếu xếp loại có giải thích (WEB-037), theo mockup TL-02b Hình B1.5:
 *
 * - `…0a41` (luồng Đảng) và `…0a42` (luồng Nhà nước) của đồng chí Trần Minh Hoàng, quý IV/2026, đã thẩm
 *   định (`DA_THAM_DINH`): tự đề xuất HTXSNV ngày 04/01, thẩm định HTTNV ngày 07/01 có giải trình; quản
 *   lý trực tiếp ghi mức đề xuất ở đây.
 * - `…0a43` đồng chí Đỗ Văn Nam, đã thẩm định; người dùng giả lập là quản lý đã nghiệm thu một nhiệm vụ
 *   của hồ sơ nên máy chủ chặn đề xuất (BR-09-14, 403).
 * - `…0a45` hồ sơ của tôi quý III/2026, đã ký số quyết định (`DA_KY_SO`), còn hạn khiếu nại, có Mẫu 1B
 *   đã ký.
 *
 * Điểm, điều kiện và cấu phần lấy từ ảnh chụp mẫu của `appraisals.json` (91,2 điểm, 22% nhiệm vụ vượt
 * mục tiêu), bổ sung đủ điều kiện hai mức và câu giải thích theo mockup.
 */

const ID = '01928f3e-7b1a-7c3e-9a51-3f2d9c1b';
const UNIT = { id: `${ID}0101`, name: 'Ban Tổ chức Đảng uỷ khối' };
const HOANG = { id: `${ID}0201`, name: 'Trần Minh Hoàng' };
const NAM = { id: `${ID}0209`, name: 'Đỗ Văn Nam' };
/** Quản lý trực tiếp của bản giả lập (AC-05). */
export const MOCK_MANAGER = { id: `${ID}0205`, name: 'Vũ Quang Huy' };
const DECIDER = { id: `${ID}0206`, name: 'Nguyễn Văn Thắng' };
const Q4 = {
  id: `${ID}0001`,
  label: 'Quý IV/2026',
  startDate: '2026-10-01',
  endDate: '2026-12-31',
};
const Q3 = {
  id: `${ID}0003`,
  label: 'Quý III/2026',
  startDate: '2026-07-01',
  endDate: '2026-09-30',
};

/** Hồ sơ của tôi đã có quyết định, hiện trong "Kết quả xếp loại" của cán bộ. */
export const RESULT_MINE = [`${ID}0a45`];
/** Hồ sơ mà quản lý giả lập đã nghiệm thu một nhiệm vụ nên không được đề xuất mức (BR-09-14). */
export const PROPOSAL_BLOCKED = new Set([`${ID}0a43`]);

const DANG_CHECKS: AppraisalRuleCheck[] = [
  {
    ratingCode: 'HTXSNV',
    label: 'Từ 90 điểm trở lên',
    met: true,
    actual: '91,2',
    threshold: '90',
    legalRef: 'QĐ Điều 11',
  },
  {
    ratingCode: 'HTXSNV',
    label: 'Hoàn thành 100% mục tiêu O',
    met: true,
    actual: '100%',
    threshold: '100%',
    legalRef: 'QĐ Điều 11',
  },
  {
    ratingCode: 'HTXSNV',
    label: 'Từ 30% nhiệm vụ vượt mục tiêu trở lên',
    met: false,
    actual: '22%',
    threshold: '30%',
    legalRef: 'QĐ Điều 11',
  },
  {
    ratingCode: 'HTXSNV',
    label: 'Có giải pháp đột phá được kiểm chứng',
    met: true,
    legalRef: 'QĐ Điều 11',
  },
  {
    ratingCode: 'HTXSNV',
    label: 'Tiêu chí chung "Thực hiện tốt"',
    met: true,
    actual: 'Thực hiện tốt',
    legalRef: 'QĐ Điều 11',
  },
  {
    ratingCode: 'HTTNV',
    label: 'Từ 80 điểm trở lên',
    met: true,
    actual: '91,2',
    threshold: '80',
    legalRef: 'QĐ Điều 12',
  },
  {
    ratingCode: 'HTTNV',
    label: 'Không trượt nhiệm vụ then chốt do chủ quan',
    met: true,
    legalRef: 'QĐ Điều 12',
  },
  {
    ratingCode: 'HTTNV',
    label: 'Tiêu chí chung "Đảm bảo" trở lên',
    met: true,
    actual: 'Thực hiện tốt',
    legalRef: 'QĐ Điều 12',
  },
];

const DANG_TEXT =
  'Đủ điểm mức Hoàn thành xuất sắc nhiệm vụ (91,2 ≥ 90) nhưng chưa đạt điều kiện 30% nhiệm vụ vượt ' +
  'mục tiêu (thực tế 22%, theo QĐ Điều 11), nên chuyển xuống mức liền kề theo PL 6 §12. Mức cuối cùng ' +
  'là mức thấp hơn giữa kết quả nhiệm vụ (Hoàn thành tốt nhiệm vụ) và tiêu chí chung (Thực hiện tốt, ' +
  'không giới hạn mức). Áp dụng bộ tiêu chí Đảng v2.0.';

/** Ảnh chụp điểm mẫu của `appraisals.json`, gắn vào hồ sơ mới; dòng nhiệm vụ có minh chứng đã nghiệm thu. */
function viewOf(
  source: string,
  profileId: string,
  kind: 'TAM_TINH' | 'CHOT_KY',
): AppraisalScoreView {
  const base = structuredClone(
    (fixture.scores as unknown as Record<string, AppraisalScoreView>)[`${ID}${source}`],
  );
  base.profileId = profileId;
  base.snapshot.profileId = profileId;
  base.snapshot.snapshotKind = kind;
  base.snapshot.computedAt = '2027-01-07T16:02:00+07:00';
  let n = 0;
  for (const g of base.snapshot.groups)
    for (const t of g.tasks ?? []) {
      n++;
      t.evidenceId = `${ID}${(0xe00 + n).toString(16)}`;
      t.evidenceVersionNo = n % 3 === 0 ? 2 : 1;
    }
  return base;
}

function dangView(profileId: string, kind: 'TAM_TINH' | 'CHOT_KY'): AppraisalScoreView {
  const v = viewOf('0a01', profileId, kind);
  v.snapshot.ruleChecks = structuredClone(DANG_CHECKS);
  v.snapshot.inputHash = '4be1c07a9d2f6e8b1a3c5d7f9e0b2d4f6a8c0e2b4d6f8a0c2e4b6d8f0a2c92cd';
  v.explanation = {
    explanationType: 'HA_MUC',
    systemLevel: 'HTTNV',
    conditions: structuredClone(DANG_CHECKS),
    text: DANG_TEXT,
    legalRef: 'QĐ Điều 11; PL 6 §12',
  };
  return v;
}

function nnView(profileId: string): AppraisalScoreView {
  const v = viewOf('0a11', profileId, 'TAM_TINH');
  v.explanation = {
    systemLevel: v.snapshot.autoRatingCode,
    conditions: structuredClone(v.snapshot.ruleChecks),
    text:
      'Đạt 91,8 điểm (28,0 điểm tiêu chí chung và 63,8 điểm kết quả thực hiện nhiệm vụ), đủ điều kiện ' +
      'mức Hoàn thành xuất sắc nhiệm vụ theo NĐ 335/2025. Áp dụng bộ tiêu chí NĐ 335 v1.3.',
    legalRef: 'NĐ 335/2025',
  };
  return v;
}

function step(
  levelKind: AppraisalRatingProposal['levelKind'],
  ratingCode: AppraisalRatingProposal['ratingCode'],
  actor: { id: string; name: string },
  actorRole: AppraisalRatingProposal['actorRole'],
  at: string,
  extra: Partial<AppraisalRatingProposal> = {},
): AppraisalRatingProposal {
  return {
    id: crypto.randomUUID(),
    levelKind,
    ratingCode,
    actor,
    actorRole,
    isCurrent: true,
    differsFromPrev: false,
    explanationRequired: false,
    isBulkDecision: false,
    proposedAt: at,
    ...extra,
  };
}

const SELF_REASON =
  'Đã chủ trì đề án chuyển đổi số công tác tổ chức, được Ban Thường vụ đánh giá là giải pháp đột phá.';
const APPRAISAL_REASON =
  'Tỷ lệ nhiệm vụ vượt mục tiêu 22%, thấp hơn ngưỡng 30% của mức xuất sắc (QĐ Điều 11).';

function appraisedTrail(): AppraisalRatingProposal[] {
  return [
    step('TU_DE_XUAT', 'HTXSNV', HOANG, 'staff', '2027-01-04T09:12:00+07:00', {
      reason: SELF_REASON,
      signedAt: '2027-01-04T09:12:00+07:00',
    }),
    step('THAM_DINH', 'HTTNV', MOCK_APPRAISER, 'appraiser', '2027-01-07T10:30:00+07:00', {
      differsFromPrev: true,
      explanationRequired: true,
      reason: APPRAISAL_REASON,
      comment: 'Hoàn thành tốt các nhiệm vụ then chốt; tỷ lệ việc vượt chưa đạt ngưỡng xuất sắc.',
      signedAt: '2027-01-07T10:30:00+07:00',
    }),
  ];
}

function profile(
  key: string,
  person: { id: string; name: string },
  extra: Partial<AppraisalProfileView>,
): AppraisalProfileView {
  return {
    id: `${ID}${key}`,
    person,
    jobTitle: 'Trưởng phòng Tổng hợp',
    orgUnit: UNIT,
    period: Q4,
    periodKind: 'QUY',
    flow: 'DANG',
    availableFlows: ['DANG', 'NHA_NUOC'],
    status: 'DA_THAM_DINH',
    isHead: false,
    isLeadership: true,
    policyVersion: 'Đảng v2.0',
    finalRatingCode: null,
    noSelfAssessment: false,
    submittedAt: '2027-01-04T09:12:00+07:00',
    appraiser: MOCK_APPRAISER,
    generalCriteria: [],
    ratingTrail: appraisedTrail(),
    anomalyHints: [],
    rowVersion: 7,
    ...extra,
  };
}

/** Ngày `days` ngày sau hôm nay (ISO), để hạn khiếu nại giả lập luôn còn hiệu lực. */
function daysFromToday(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() + days);
  return d.toISOString().slice(0, 10);
}

/** Hồ sơ và ảnh chụp điểm của SCR-0903, sinh mới mỗi lần nạp lại dữ liệu giả lập. */
export function ratingSheets(): {
  profiles: AppraisalProfileView[];
  views: Record<string, AppraisalScoreView>;
} {
  const decided: AppraisalRatingProposal[] = [
    step('TU_DE_XUAT', 'HTTNV', HOANG, 'staff', '2026-10-05T08:40:00+07:00', {
      signedAt: '2026-10-05T08:40:00+07:00',
    }),
    step('THAM_DINH', 'HTTNV', MOCK_APPRAISER, 'appraiser', '2026-10-08T14:00:00+07:00', {
      signedAt: '2026-10-08T14:00:00+07:00',
    }),
    step('DE_XUAT', 'HTTNV', MOCK_MANAGER, 'manager', '2026-10-09T09:00:00+07:00'),
    step('QUYET_DINH', 'HTTNV', DECIDER, 'decider', '2026-10-12T15:30:00+07:00', {
      isBulkDecision: true,
      signedAt: '2026-10-12T15:30:00+07:00',
    }),
  ];
  const profiles: AppraisalProfileView[] = [
    profile('0a41', HOANG, {}),
    profile('0a42', HOANG, { flow: 'NHA_NUOC', policyVersion: 'NĐ 335 v1.3' }),
    profile('0a43', NAM, {
      jobTitle: 'Phó Trưởng phòng Tổng hợp',
      availableFlows: ['DANG'],
      ratingTrail: [
        step('TU_DE_XUAT', 'HTTNV', NAM, 'staff', '2027-01-04T16:20:00+07:00', {
          signedAt: '2027-01-04T16:20:00+07:00',
        }),
        step('THAM_DINH', 'HTTNV', MOCK_APPRAISER, 'appraiser', '2027-01-07T11:05:00+07:00', {
          signedAt: '2027-01-07T11:05:00+07:00',
        }),
      ],
    }),
    profile('0a45', HOANG, {
      period: Q3,
      availableFlows: ['DANG'],
      status: 'DA_KY_SO',
      finalRatingCode: 'HTTNV',
      submittedAt: '2026-10-05T08:40:00+07:00',
      decidedAt: '2026-10-12T15:30:00+07:00',
      decider: DECIDER,
      complaintDeadline: daysFromToday(14),
      ratingTrail: decided,
      signedFormId: `${ID}0f45`,
      rowVersion: 12,
    }),
  ];
  const nam = viewOf('0a21', `${ID}0a43`, 'TAM_TINH');
  nam.explanation = {
    systemLevel: 'HTTNV',
    conditions: structuredClone(nam.snapshot.ruleChecks),
    text: 'Đạt 84,5 điểm, đủ điều kiện mức Hoàn thành tốt nhiệm vụ theo QĐ Điều 12. Áp dụng bộ tiêu chí Đảng v2.0.',
    legalRef: 'QĐ Điều 12',
  };
  const q3 = dangView(`${ID}0a45`, 'CHOT_KY');
  return {
    profiles,
    views: {
      [`${ID}0a41`]: dangView(`${ID}0a41`, 'TAM_TINH'),
      [`${ID}0a42`]: nnView(`${ID}0a42`),
      [`${ID}0a43`]: nam,
      [`${ID}0a45`]: {
        ...q3,
        snapshot: { ...q3.snapshot, computedAt: '2026-10-02T07:45:00+07:00' },
      },
    },
  };
}
