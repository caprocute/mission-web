import type {
  Period,
  PeriodLockBatch,
  PeriodLockEvent,
  PeriodLockFailure,
  PeriodLockOrder,
  PeriodLockStep,
  PeriodPinnedPolicyPack,
  SagaStatus,
} from '../../lib/models/period';

/** Tham chiếu người, đơn vị (`Ref` của hợp đồng: `id`, `name`). */
export type Ref = NonNullable<PeriodLockOrder['approvedBy']>;

/**
 * Dữ liệu mẫu của SCR-0801 (WEB-034) theo tình huống Hình 9.3 của TL-02, dời về tháng 10/2026: tenant
 * Đảng uỷ các cơ quan Đảng Trung ương đang khoá kỳ Quý III/2026 bằng saga `chot-ky` phiên bản 3 (bước 2
 * chạy 78%, 17 hồ sơ lỗi); Tháng 8/2026 tạm dừng sau bước 2 và có đề nghị huỷ chờ người duyệt thứ hai;
 * Tháng 9/2026 có lệnh đã duyệt, chờ khởi chạy; Quý IV/2026 có lệnh chờ phê duyệt; Năm 2026 có lệnh
 * đã duyệt nhưng chưa qua hạn nghiệm thu tồn đọng (bước 0 không đạt); Quý II/2026 đã khoá sau một lần mở lại hậu kiểm; Quý I/2027 đã tạo, chưa tới ngày bắt đầu,
 * chưa có lệnh.
 */

export const PEOPLE = {
  dung: { id: 'u-hoang-duc-dung', name: 'Hoàng Đức Dũng' },
  huy: { id: 'u-pham-quang-huy', name: 'Phạm Quang Huy' },
  lan: { id: 'u-vu-thi-lan', name: 'Vũ Thị Lan' },
  ha: { id: 'u-tran-thu-ha', name: 'Trần Thu Hà' },
} satisfies Record<string, Ref>;

/** Người duyệt thứ hai nhận đề nghị huỷ (MSG-08-05); hợp đồng chưa trả tên này, xem wiki/14. */
export const SECOND_APPROVER = PEOPLE.ha;

export const SAGA_TEMPLATE = { key: 'chot-ky', version: 3 };

/** Sáu bước saga và hành động bù trừ (TL-02 QT-08, bảng bù trừ). */
export const STEP_DEFS: { name: string; compensation: string | null }[] = [
  { name: 'Kiểm tra tiền điều kiện', compensation: null },
  {
    name: 'Khoá kỳ',
    compensation: 'Trả kỳ về MO, phát sự kiện huỷ khoá kỳ, các module mở ghi lại',
  },
  {
    name: 'Tính điểm hàng loạt',
    compensation: 'Đánh dấu các lô điểm của lần chạy là bị thay thế (superseded), không xoá',
  },
  {
    name: 'Sinh mẫu và bảng chấm điểm',
    compensation: 'Đánh dấu thu hồi các tệp đã sinh; tệp vẫn giữ trong kho chỉ ghi',
  },
  {
    name: 'Ký số lô',
    compensation: 'Huỷ các yêu cầu ký đang chờ',
  },
  { name: 'Hoàn tất', compensation: null },
];

/** BPMN activity id của từng bước trong mẫu `chot-ky` (suy luận theo ví dụ của hợp đồng). */
export const STEP_ACTIVITY_IDS = [
  'buoc0TienDieuKien',
  'buoc1KhoaKy',
  'buoc2TinhDiem',
  'buoc3SinhMau',
  'buoc4KySo',
  'buoc5HoanTat',
];

const PACKS: PeriodPinnedPolicyPack[] = [
  {
    flow: 'DANG',
    packVersionId: '0192e1c4-5d6e-7f80-9a1b-2c3d4e5f6a70',
    packCode: 'khoi-dang/quy-dinh-moi',
    version: '2.0',
  },
  {
    flow: 'NHA_NUOC',
    packVersionId: '0192e1c4-5d6e-7f80-9a1b-2c3d4e5f6a71',
    packCode: 'nha-nuoc/nd-335',
    version: '1.3',
  },
];

export interface SagaFixture {
  status: SagaStatus;
  pauseRequested: boolean;
  totals: { profiles: number; scored: number; failed: number };
  ratePerMinute: number | null;
  estimatedFinishAt: string | null;
  budgetFinishAt: string | null;
  excludedEvidenceCount: number;
  steps: PeriodLockStep[];
  cancelRequest: {
    requestedBy: Ref;
    requestedAt: string;
    reason: string;
  } | null;
  batches: PeriodLockBatch[];
  failures: PeriodLockFailure[];
  events: PeriodLockEvent[];
}

export interface PeriodFixture {
  period: Period;
  order: PeriodLockOrder | null;
  saga: SagaFixture | null;
}

const step = (
  stepNo: number,
  status: PeriodLockStep['status'],
  extra: Partial<PeriodLockStep> = {},
): PeriodLockStep => ({
  stepNo,
  name: STEP_DEFS[stepNo].name,
  status,
  startedAt: null,
  finishedAt: null,
  compensationAction: STEP_DEFS[stepNo].compensation,
  compensatedAt: null,
  ...extra,
});

const UNITS: Ref[] = [
  { id: 'ou-ban-to-chuc', name: 'Ban Tổ chức Đảng uỷ' },
  { id: 'ou-van-phong', name: 'Văn phòng Đảng uỷ' },
  { id: 'ou-ban-tuyen-giao', name: 'Ban Tuyên giáo Đảng uỷ' },
  { id: 'ou-uy-ban-kiem-tra', name: 'Uỷ ban Kiểm tra Đảng uỷ' },
  { id: 'ou-dang-bo-bao', name: 'Đảng bộ Báo Nhân Dân' },
];

function batches(prefix: string, done: number, running: number, failed: number, at: string) {
  const out: PeriodLockBatch[] = [];
  const total = done + running + failed + 4;
  for (let i = 0; i < total; i++) {
    const status: PeriodLockBatch['status'] =
      i < done
        ? 'XONG'
        : i < done + failed
          ? 'LOI'
          : i < done + failed + running
            ? 'DANG_CHAY'
            : 'CHO';
    out.push({
      id: `${prefix}-lo-${String(i + 1).padStart(3, '0')}`,
      unit: UNITS[i % UNITS.length],
      profileCount: 1000,
      status,
      attempts: status === 'LOI' ? 3 : status === 'CHO' ? 0 : 1,
      finishedAt: status === 'XONG' || status === 'LOI' ? at : null,
    });
  }
  return out;
}

const FAILURE_ERRORS = [
  'Thiếu phân công chính tại ngày cuối kỳ.',
  'Nhiệm vụ then chốt chưa có kết quả nghiệm thu cuối cùng.',
  'Hồ sơ thuộc đơn vị đang lệch tổ chức, chưa xác định luồng đánh giá.',
];

function failures(prefix: string, n: number, at: string): PeriodLockFailure[] {
  const names = [
    'Nguyễn Thị Mai',
    'Trần Văn Bình',
    'Lê Thị Hồng',
    'Phạm Minh Tuấn',
    'Đỗ Thu Trang',
    'Bùi Quốc Khánh',
  ];
  return Array.from({ length: n }, (_, i) => ({
    profileId: `${prefix}-hs-${String(i + 1).padStart(3, '0')}`,
    person: { id: `${prefix}-cb-${i + 1}`, name: names[i % names.length] },
    unit: UNITS[i % UNITS.length],
    batchId: `${prefix}-lo-${String(40 + (i % 3)).padStart(3, '0')}`,
    error: FAILURE_ERRORS[i % FAILURE_ERRORS.length],
    attempts: 3,
    lastAttemptAt: at,
  }));
}

export function periodFixtures(): PeriodFixture[] {
  return [
    {
      period: {
        id: 'p-2026q3',
        code: '2026-Q3',
        name: 'Quý III/2026',
        kind: 'QUY',
        flow: 'CA_HAI',
        parentPeriodId: 'p-2026',
        startDate: '2026-07-01',
        endDate: '2026-09-30',
        periodBucket: 20263,
        planDeadline: '2026-07-09',
        submissionDeadline: '2026-10-08',
        status: 'DANG_KHOA',
        notStarted: false,
        lockedAt: null,
        lockedBy: null,
        lockSagaId: 'saga-ck-2026q3',
        reopenCount: 0,
        milestones: {
          planApprovalDeadline: '2026-07-09',
          evidenceDeadline: '2026-09-30',
          acceptanceBacklogDeadline: '2026-10-16',
          plannedLockDate: '2026-10-19',
        },
        pinnedPolicyPacks: PACKS,
        warnings: [],
        rowVersion: 12,
      },
      order: {
        id: 'lo-2026q3',
        periodId: 'p-2026q3',
        status: 'DANG_CHAY',
        basisDoc: 'Thông báo 21-TB/ĐUK ngày 13/10/2026',
        plannedLockAt: '2026-10-19T07:00:00+07:00',
        requestedBy: PEOPLE.dung,
        requestedAt: '2026-10-14T09:15:00+07:00',
        approvedBy: PEOPLE.huy,
        approvedAt: '2026-10-15T16:40:00+07:00',
        startedBy: PEOPLE.lan,
        startedAt: '2026-10-19T07:02:00+07:00',
        sagaId: 'saga-ck-2026q3',
        idempotencyKey: 'CK-2026Q3',
        rowVersion: 4,
      },
      saga: {
        status: 'DANG_CHAY',
        pauseRequested: false,
        totals: { profiles: 298_760, scored: 233_040, failed: 17 },
        ratePerMinute: 5800,
        estimatedFinishAt: '2026-10-19T10:30:00+07:00',
        budgetFinishAt: '2026-10-19T11:02:00+07:00',
        excludedEvidenceCount: 214,
        steps: [
          step(0, 'DAT_CO_CANH_BAO', {
            startedAt: '2026-10-19T07:02:05+07:00',
            finishedAt: '2026-10-19T07:05:31+07:00',
            resultSummary: { note: '214 minh chứng còn DA_NOP không được tính trong kỳ' },
          }),
          step(1, 'DAT', {
            startedAt: '2026-10-19T07:05:31+07:00',
            finishedAt: '2026-10-19T07:05:40+07:00',
            resultSummary: { note: 'Kỳ chuyển DANG_KHOA, đã phát EVT-09' },
          }),
          step(2, 'DANG_CHAY', {
            startedAt: '2026-10-19T07:05:42+07:00',
            resultSummary: { note: '3 lô lỗi đang tự thử lại' },
          }),
          step(3, 'CHO'),
          step(4, 'CHO'),
          step(5, 'CHO'),
        ],
        cancelRequest: null,
        batches: batches('q3', 38, 6, 3, '2026-10-19T07:41:13+07:00'),
        failures: failures('q3', 17, '2026-10-19T07:30:02+07:00'),
        events: [
          {
            at: '2026-10-19T07:41:13+07:00',
            event:
              'Lô q3-lo-040, q3-lo-041, q3-lo-042 lỗi sau 3 lần thử, 17 hồ sơ vào hàng đợi lỗi.',
            evt: null,
            actor: null,
            ledgerEntryId: 'le-q3-6',
          },
          {
            at: '2026-10-19T07:21:13+07:00',
            event: 'Đã tính xong 100.000 hồ sơ đầu tiên, phát DiemDaTinh theo lô.',
            evt: 'EVT-10',
            actor: null,
            ledgerEntryId: 'le-q3-5',
          },
          {
            at: '2026-10-19T07:05:42+07:00',
            event: 'Bắt đầu bước 2: chia 299 lô tính điểm, 24 worker.',
            evt: null,
            actor: null,
            ledgerEntryId: 'le-q3-4',
          },
          {
            at: '2026-10-19T07:05:40+07:00',
            event: 'Kỳ Quý III/2026 chuyển DANG_KHOA, phát KyDaKhoa.',
            evt: 'EVT-09',
            actor: null,
            ledgerEntryId: 'le-q3-3',
          },
          {
            at: '2026-10-19T07:05:31+07:00',
            event: 'Bước 0 đạt có cảnh báo: 214 minh chứng còn DA_NOP không được tính trong kỳ.',
            evt: null,
            actor: null,
            ledgerEntryId: 'le-q3-2',
          },
          {
            at: '2026-10-19T07:02:00+07:00',
            event: 'Khởi chạy saga chot-ky phiên bản 3, khoá chống lặp CK-2026Q3.',
            evt: null,
            actor: PEOPLE.lan,
            ledgerEntryId: 'le-q3-1',
          },
        ],
      },
    },
    {
      period: {
        id: 'p-2026m08',
        code: '2026-08',
        name: 'Tháng 8/2026',
        kind: 'THANG',
        flow: 'NHA_NUOC',
        parentPeriodId: 'p-2026q3',
        startDate: '2026-08-01',
        endDate: '2026-08-31',
        periodBucket: 202608,
        status: 'DANG_KHOA',
        notStarted: false,
        lockSagaId: 'saga-ck-2026m08',
        reopenCount: 0,
        milestones: {
          evidenceDeadline: '2026-08-31',
          acceptanceBacklogDeadline: '2026-09-07',
          plannedLockDate: '2026-09-08',
        },
        pinnedPolicyPacks: [PACKS[1]],
        warnings: [],
        rowVersion: 9,
      },
      order: {
        id: 'lo-2026m08',
        periodId: 'p-2026m08',
        status: 'DANG_CHAY',
        basisDoc: 'Quyết định 118-QĐ/ĐUK ngày 05/9/2026',
        plannedLockAt: '2026-09-08T08:00:00+07:00',
        requestedBy: PEOPLE.dung,
        requestedAt: '2026-09-05T10:00:00+07:00',
        approvedBy: PEOPLE.huy,
        approvedAt: '2026-09-06T15:20:00+07:00',
        startedBy: PEOPLE.lan,
        startedAt: '2026-09-08T08:03:00+07:00',
        sagaId: 'saga-ck-2026m08',
        idempotencyKey: 'CK-202608',
        rowVersion: 6,
      },
      saga: {
        status: 'CHO_SU_KIEN',
        pauseRequested: true,
        totals: { profiles: 12_480, scored: 12_480, failed: 0 },
        ratePerMinute: 2100,
        estimatedFinishAt: null,
        budgetFinishAt: '2026-09-08T12:03:00+07:00',
        excludedEvidenceCount: 36,
        steps: [
          step(0, 'DAT_CO_CANH_BAO', {
            startedAt: '2026-09-08T08:03:04+07:00',
            finishedAt: '2026-09-08T08:04:10+07:00',
            resultSummary: { note: '36 minh chứng còn DA_NOP không được tính trong kỳ' },
          }),
          step(1, 'DAT', {
            startedAt: '2026-09-08T08:04:10+07:00',
            finishedAt: '2026-09-08T08:04:16+07:00',
            resultSummary: { note: 'Kỳ chuyển DANG_KHOA, đã phát EVT-09' },
          }),
          step(2, 'DAT', {
            startedAt: '2026-09-08T08:04:18+07:00',
            finishedAt: '2026-09-08T08:10:12+07:00',
            resultSummary: { note: '12.480 hồ sơ đã tính, không còn hồ sơ lỗi' },
          }),
          step(3, 'CHO'),
          step(4, 'CHO'),
          step(5, 'CHO'),
        ],
        cancelRequest: {
          requestedBy: PEOPLE.dung,
          requestedAt: '2026-09-08T08:16:00+07:00',
          reason:
            'Phát hiện sai sót cấu hình trọng số khối 3 khi đang tính điểm; cần sửa Policy Pack trước khi khoá lại.',
        },
        batches: batches('m08', 13, 0, 0, '2026-09-08T08:10:12+07:00').filter(
          (b) => b.status === 'XONG',
        ),
        failures: [],
        events: [
          {
            at: '2026-09-08T08:16:00+07:00',
            event: 'Đề nghị huỷ và bù trừ toàn bộ, chờ người duyệt thứ hai.',
            evt: 'EVT-21',
            actor: PEOPLE.dung,
            ledgerEntryId: 'le-m08-5',
          },
          {
            at: '2026-09-08T08:10:12+07:00',
            event: 'Bước 2 hoàn tất; saga tạm dừng theo cờ tạm dừng.',
            evt: 'EVT-10',
            actor: null,
            ledgerEntryId: 'le-m08-4',
          },
          {
            at: '2026-09-08T08:08:30+07:00',
            event: 'Đặt cờ tạm dừng sau bước hiện tại.',
            evt: null,
            actor: PEOPLE.lan,
            ledgerEntryId: 'le-m08-3',
          },
          {
            at: '2026-09-08T08:04:16+07:00',
            event: 'Kỳ Tháng 8/2026 chuyển DANG_KHOA, phát KyDaKhoa.',
            evt: 'EVT-09',
            actor: null,
            ledgerEntryId: 'le-m08-2',
          },
          {
            at: '2026-09-08T08:03:00+07:00',
            event: 'Khởi chạy saga chot-ky phiên bản 3, khoá chống lặp CK-202608.',
            evt: null,
            actor: PEOPLE.lan,
            ledgerEntryId: 'le-m08-1',
          },
        ],
      },
    },
    {
      period: {
        id: 'p-2026m09',
        code: '2026-09',
        name: 'Tháng 9/2026',
        kind: 'THANG',
        flow: 'NHA_NUOC',
        parentPeriodId: 'p-2026q3',
        startDate: '2026-09-01',
        endDate: '2026-09-30',
        periodBucket: 202609,
        status: 'MO',
        notStarted: false,
        reopenCount: 0,
        milestones: {
          evidenceDeadline: '2026-09-30',
          acceptanceBacklogDeadline: '2026-10-07',
          plannedLockDate: '2026-10-20',
        },
        pinnedPolicyPacks: [PACKS[1]],
        warnings: [
          'Ngày khoá dự kiến 20/10/2026 muộn hơn tham số của Policy Pack (ngày 10 tháng sau).',
        ],
        rowVersion: 5,
      },
      order: {
        id: 'lo-2026m09',
        periodId: 'p-2026m09',
        status: 'DA_DUYET',
        basisDoc: 'Thông báo 24-TB/ĐUK ngày 15/10/2026',
        plannedLockAt: '2026-10-20T07:30:00+07:00',
        requestedBy: PEOPLE.dung,
        requestedAt: '2026-10-15T08:30:00+07:00',
        approvedBy: PEOPLE.huy,
        approvedAt: '2026-10-16T14:05:00+07:00',
        startedBy: null,
        startedAt: null,
        sagaId: null,
        idempotencyKey: 'CK-202609',
        rowVersion: 2,
      },
      saga: null,
    },
    {
      period: {
        id: 'p-2026q4',
        code: '2026-Q4',
        name: 'Quý IV/2026',
        kind: 'QUY',
        flow: 'CA_HAI',
        parentPeriodId: 'p-2026',
        startDate: '2026-10-01',
        endDate: '2026-12-31',
        periodBucket: 20264,
        planDeadline: '2026-10-09',
        submissionDeadline: '2027-01-08',
        status: 'MO',
        notStarted: false,
        reopenCount: 0,
        milestones: {
          planApprovalDeadline: '2026-10-09',
          evidenceDeadline: '2026-12-31',
          acceptanceBacklogDeadline: '2027-01-03',
          plannedLockDate: '2027-01-04',
        },
        pinnedPolicyPacks: PACKS,
        warnings: [],
        rowVersion: 3,
      },
      order: {
        id: 'lo-2026q4',
        periodId: 'p-2026q4',
        status: 'CHO_DUYET',
        basisDoc: 'Kế hoạch 15-KH/ĐUK ngày 12/10/2026 về đánh giá quý IV',
        plannedLockAt: '2027-01-04T07:00:00+07:00',
        requestedBy: PEOPLE.dung,
        requestedAt: '2026-10-16T09:00:00+07:00',
        approvedBy: null,
        approvedAt: null,
        startedBy: null,
        startedAt: null,
        sagaId: null,
        idempotencyKey: 'CK-2026Q4',
        rowVersion: 1,
      },
      saga: null,
    },
    {
      period: {
        id: 'p-2026',
        code: '2026',
        name: 'Năm 2026',
        kind: 'NAM',
        flow: 'CA_HAI',
        startDate: '2026-01-01',
        endDate: '2026-12-31',
        periodBucket: 2026,
        submissionDeadline: '2027-01-15',
        status: 'MO',
        notStarted: false,
        reopenCount: 0,
        milestones: {
          evidenceDeadline: '2026-12-31',
          acceptanceBacklogDeadline: '2027-01-08',
          plannedLockDate: '2027-01-11',
        },
        pinnedPolicyPacks: PACKS,
        warnings: [],
        rowVersion: 7,
      },
      order: {
        id: 'lo-2026',
        periodId: 'p-2026',
        status: 'DA_DUYET',
        basisDoc: 'Kế hoạch 16-KH/ĐUK ngày 14/10/2026 về đánh giá năm 2026',
        plannedLockAt: '2027-01-11T07:00:00+07:00',
        requestedBy: PEOPLE.dung,
        requestedAt: '2026-10-14T10:00:00+07:00',
        approvedBy: PEOPLE.huy,
        approvedAt: '2026-10-17T09:30:00+07:00',
        startedBy: null,
        startedAt: null,
        sagaId: null,
        idempotencyKey: 'CK-2026',
        rowVersion: 2,
      },
      saga: null,
    },
    {
      period: {
        id: 'p-2026q2',
        code: '2026-Q2',
        name: 'Quý II/2026',
        kind: 'QUY',
        flow: 'CA_HAI',
        parentPeriodId: 'p-2026',
        startDate: '2026-04-01',
        endDate: '2026-06-30',
        periodBucket: 20262,
        status: 'DA_KHOA',
        notStarted: false,
        lockedAt: '2026-08-20T17:12:00+07:00',
        lockedBy: PEOPLE.lan,
        lockSagaId: 'saga-ck-2026q2-r1',
        reopenCount: 1,
        milestones: {
          planApprovalDeadline: '2026-04-09',
          evidenceDeadline: '2026-06-30',
          acceptanceBacklogDeadline: '2026-07-03',
          plannedLockDate: '2026-07-06',
        },
        pinnedPolicyPacks: PACKS,
        warnings: [],
        rowVersion: 21,
      },
      order: null,
      saga: null,
    },
    {
      period: {
        id: 'p-2026q1',
        code: '2026-Q1',
        name: 'Quý I/2026',
        kind: 'QUY',
        flow: 'CA_HAI',
        parentPeriodId: 'p-2026',
        startDate: '2026-01-01',
        endDate: '2026-03-31',
        periodBucket: 20261,
        status: 'DA_KHOA',
        notStarted: false,
        lockedAt: '2026-04-06T10:41:00+07:00',
        lockedBy: PEOPLE.lan,
        lockSagaId: 'saga-ck-2026q1',
        reopenCount: 0,
        milestones: {
          planApprovalDeadline: '2026-01-09',
          evidenceDeadline: '2026-03-31',
          acceptanceBacklogDeadline: '2026-04-03',
          plannedLockDate: '2026-04-06',
        },
        pinnedPolicyPacks: PACKS,
        warnings: [],
        rowVersion: 14,
      },
      order: null,
      saga: null,
    },
    {
      period: {
        id: 'p-2027q1',
        code: '2027-Q1',
        name: 'Quý I/2027',
        kind: 'QUY',
        flow: 'CA_HAI',
        parentPeriodId: 'p-2027',
        startDate: '2027-01-01',
        endDate: '2027-03-31',
        periodBucket: 20271,
        planDeadline: '2027-01-08',
        status: 'MO',
        notStarted: true,
        reopenCount: 0,
        milestones: {
          planApprovalDeadline: '2027-01-08',
          evidenceDeadline: '2027-03-31',
          acceptanceBacklogDeadline: '2027-04-03',
          plannedLockDate: '2027-04-05',
        },
        pinnedPolicyPacks: PACKS,
        warnings: [],
        rowVersion: 1,
      },
      order: null,
      saga: null,
    },
  ];
}
