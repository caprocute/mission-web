import type { RatingLevel } from '../../lib/models/appraisal';
import {
  DossierComment,
  DossierCommentCreate,
  DossierCommentKind,
  DossierMeeting,
  DossierMeetingCreate,
  DossierMeetingRole,
  DossierMeetingType,
  DossierMeetingUpdate,
  DossierMinutesRequest,
  DossierParticipantStatus,
  DossierRemediationItem,
  DossierRemediationItemUpdate,
  DossierSpecialCase,
} from '../../lib/models/dossier';
import { Job } from '../../lib/models/job';
import { MOCK_ME, mockSession } from '../mock-session';
import { MockRequest, MockResponse, MockRoute, ok, problem } from '../mock-router';
import { ORG_PERSONS } from '../org/org.mock';

/**
 * Giả lập hội nghị kiểm điểm của MOD-06 (QT-11, SCR-1101, WEB-040) theo `dossiers.yaml`.
 *
 * Mỗi hội nghị mẫu gắn sẵn vai trò của người dùng hiện tại (`myRole`) để thử được mọi vai trên cùng một
 * tài khoản: M1 thư ký, hội nghị đang họp (theo mockup); M2 người được kiểm điểm, chưa gửi dự thảo và gửi hôm
 * nay sẽ không đạt thời hạn (MSG-11-01); M3 chủ trì, đã lập biên bản, chờ kết thúc; M4 thành viên góp ý;
 * M5 người được kiểm điểm khi tenant bật ẩn danh (BR-11-06); M6 đã ký biên bản; M7 chủ trì, thời hạn không đạt,
 * chờ ghi lý do giữ ngày. Ngày làm việc là thứ Hai đến thứ Sáu nằm trọn giữa thời điểm gửi và ngày họp
 * (`kiem_diem.cach_dem_ngay` mặc định, chưa có ngày nghỉ lễ). "Sinh biên bản" cần phiên AAL2 (401 step-up).
 * Hội nghị lập mới có `myRole` rỗng nếu người dùng không thuộc thành phần.
 */

const ID = '0192a3f4-1101-7000-8000-';
const PID = '0192a3f4-0003-7000-8000-';
const meetingId = (n: number) => `${ID}${String(n).padStart(12, '0')}`;

const UNIT = { id: '0192a3f4-0001-7000-8000-000000000010', name: 'Chi bộ Ban Tổ chức' };
const ME = { id: `${PID}000000000100`, name: MOCK_ME.name };
const P = {
  tam: { id: `${PID}000000000101`, name: 'Ngô Thanh Tâm' },
  hoang: { id: `${PID}000000000102`, name: 'Trần Minh Hoàng' },
  ha: { id: `${PID}000000000103`, name: 'Lê Thu Hà' },
  vinh: { id: `${PID}000000000104`, name: 'Đỗ Quang Vinh' },
  long: { id: `${PID}000000000105`, name: 'Phạm Đức Long' },
  mai: { id: `${PID}000000000106`, name: 'Hoàng Thị Mai' },
};

/** Kỳ dùng được cho hội nghị (khớp `periods.fixture.ts`). */
const PERIODS: Record<string, DossierMeeting['period']> = {
  'p-2025': { id: 'p-2025', label: 'Năm 2025', startDate: '2025-01-01', endDate: '2025-12-31' },
  'p-2026': { id: 'p-2026', label: 'Năm 2026', startDate: '2026-01-01', endDate: '2026-12-31' },
  'p-2026q1': {
    id: 'p-2026q1',
    label: 'Quý I/2026',
    startDate: '2026-01-01',
    endDate: '2026-03-31',
  },
  'p-2026q2': {
    id: 'p-2026q2',
    label: 'Quý II/2026',
    startDate: '2026-04-01',
    endDate: '2026-06-30',
  },
  'p-2026q3': {
    id: 'p-2026q3',
    label: 'Quý III/2026',
    startDate: '2026-07-01',
    endDate: '2026-09-30',
  },
  'p-2026q4': {
    id: 'p-2026q4',
    label: 'Quý IV/2026',
    startDate: '2026-10-01',
    endDate: '2026-12-31',
  },
  'p-2027q1': {
    id: 'p-2027q1',
    label: 'Quý I/2027',
    startDate: '2027-01-01',
    endDate: '2027-03-31',
  },
};

const REQUIRED_DAYS = 3;
const ANONYMOUS = true;

/** Số ngày làm việc trọn vẹn nằm giữa hai mốc (không tính ngày gửi và ngày họp). */
export function workingDaysBetween(sent: Date, meeting: Date): number {
  const d = new Date(sent);
  d.setHours(0, 0, 0, 0);
  d.setDate(d.getDate() + 1);
  const end = new Date(meeting);
  end.setHours(0, 0, 0, 0);
  let n = 0;
  while (d < end) {
    if (d.getDay() !== 0 && d.getDay() !== 6) n++;
    d.setDate(d.getDate() + 1);
  }
  return n;
}

/** Lùi hoặc tiến `n` ngày làm việc từ `from`, giữ giờ `hh:mm`. */
function shiftWorkingDays(from: Date, n: number, hh: number, mm: number): Date {
  const d = new Date(from);
  const step = n < 0 ? -1 : 1;
  let left = Math.abs(n);
  while (left > 0) {
    d.setDate(d.getDate() + step);
    if (d.getDay() !== 0 && d.getDay() !== 6) left--;
  }
  d.setHours(hh, mm, 0, 0);
  return d;
}

const at = (d: Date, hh: number, mm: number) => {
  const x = new Date(d);
  x.setHours(hh, mm, 0, 0);
  return x;
};

interface StoredMeeting {
  m: DossierMeeting;
  comments: DossierComment[];
  /** Hạn chế kỳ trước của người được kiểm điểm. */
  previous: DossierRemediationItem[];
  /** Hạn chế do hội nghị này tạo khi kết thúc. */
  created: DossierRemediationItem[];
}

let meetings: StoredMeeting[] = [];
const jobs = new Map<string, Job>();
const draftFiles = new Map<string, string[]>();
let seq = 0;
const nextId = (prefix: string) => `${prefix}${String(++seq).padStart(12, '0')}`;

function deadline(
  draftSentAt: string | null | undefined,
  scheduledAt: string,
  overrideReason: string | null = null,
): DossierMeeting['deadlineCheck'] {
  if (!draftSentAt)
    return {
      workingDays: null,
      requiredWorkingDays: REQUIRED_DAYS,
      passed: false,
      legalRef: 'QĐ Điều 6',
    };
  const days = workingDaysBetween(new Date(draftSentAt), new Date(scheduledAt));
  const passed = days >= REQUIRED_DAYS;
  return {
    workingDays: days,
    requiredWorkingDays: REQUIRED_DAYS,
    passed,
    legalRef: 'QĐ Điều 6',
    ...(passed ? {} : { msg: 'MSG-11-01' }),
    overrideReason,
  };
}

function title(type: DossierMeetingType, period: DossierMeeting['period'], unit: string): string {
  if (type === 'KIEM_DIEM_NAM')
    return `Hội nghị kiểm điểm cuối năm ${period.startDate.slice(0, 4)} của ${unit}`;
  if (type === 'CHUYEN_DE') return `Hội nghị kiểm điểm chuyên đề ${period.label} của ${unit}`;
  return `Hội nghị kiểm điểm ${period.label.charAt(0).toLowerCase()}${period.label.slice(1)} của ${unit}`;
}

type Part = [{ id: string; name: string }, DossierMeetingRole, DossierParticipantStatus];

function meeting(
  n: number,
  o: {
    type: DossierMeetingType;
    period: string;
    myRole: DossierMeetingRole | null;
    status: DossierMeeting['status'];
    scheduledAt: Date;
    draftSentAt?: Date | null;
    subject: { id: string; name: string };
    chair: { id: string; name: string };
    secretary: { id: string; name: string };
    parts: Part[];
    vote?: DossierMeeting['vote'];
    meetingAt?: Date | null;
    proposedLevel?: RatingLevel;
    overrideReason?: string | null;
  },
): DossierMeeting {
  const period = PERIODS[o.period];
  const formId = `0192a3f4-1103-7000-8000-${String(n).padStart(12, '0')}`;
  draftFiles.set(formId, [
    'Mau 1B - Ban kiem diem ca nhan (du thao)',
    `Nguoi duoc kiem diem: ${o.subject.name}`,
    `Ky: ${period.label}`,
    'Ban du thao sinh tu du lieu da quyet dinh, chi dung de gop y.',
  ]);
  const sent = o.draftSentAt ? o.draftSentAt.toISOString() : null;
  const scheduledAt = o.scheduledAt.toISOString();
  return {
    id: meetingId(n),
    title: title(o.type, period, UNIT.name),
    meetingType: o.type,
    orgUnit: UNIT,
    period,
    scheduledAt,
    status: o.status,
    ...(o.myRole ? { myRole: o.myRole } : {}),
    location: 'Phòng họp tầng 3, trụ sở Ban Tổ chức',
    meetingAt: o.meetingAt ? o.meetingAt.toISOString() : null,
    subjectPerson: o.subject,
    chair: o.chair,
    secretary: o.secretary,
    draftSentAt: sent,
    deadlineCheck: deadline(sent, scheduledAt, o.overrideReason ?? null),
    participants: o.parts.map(([person, role, status]) => ({ person, role, status })),
    draftForms: [{ id: formId, formCode: 'MAU_1B', status: 'DA_SINH' }],
    proposedLevel: o.proposedLevel ?? 'HTTNV',
    vote: o.vote ?? null,
    minutesSha256: o.vote ? hex(64) : null,
    signRequestId: null,
    commentsLocked: o.status === 'DA_KY',
    rowVersion: 1,
  };
}

function hex(n: number): string {
  let s = '';
  for (let i = 0; i < n; i++) s += '0123456789abcdef'[Math.floor(Math.random() * 16)];
  return s;
}

function comment(
  m: DossierMeeting,
  who: { id: string; name: string },
  kind: DossierCommentKind,
  content: string,
  createdAt: Date,
  phase: DossierComment['phase'] = 'TRUOC_HOI_NGHI',
): DossierComment {
  return {
    id: nextId('0192a3f4-1104-7000-8000-'),
    meetingId: m.id,
    subjectPersonId: m.subjectPerson?.id ?? '',
    commenter: who,
    source: 'GOP_Y',
    kind,
    phase,
    content,
    createdAt: createdAt.toISOString(),
  };
}

function remediation(
  person: { id: string; name: string },
  content: string,
  status: DossierRemediationItem['status'],
  period = 'p-2025',
): DossierRemediationItem {
  return {
    id: nextId('0192a3f4-1105-7000-8000-'),
    person,
    meetingId: meetingId(0),
    reviewCommentId: null,
    sourcePeriod: PERIODS[period],
    duePeriodId: null,
    content,
    status,
    verifiedBy: null,
    verifiedAt: null,
    rowVersion: 1,
  };
}

function seed(): StoredMeeting[] {
  seq = 0;
  draftFiles.clear();
  const today = new Date();
  const out: StoredMeeting[] = [];

  // M1: hội nghị cuối năm theo mockup, người dùng là thư ký, đang họp hôm nay
  const s1 = at(today, 8, 0);
  const m1 = meeting(1, {
    type: 'KIEM_DIEM_NAM',
    period: 'p-2026',
    myRole: 'THU_KY',
    status: 'DA_GUI_DU_THAO',
    scheduledAt: s1,
    draftSentAt: shiftWorkingDays(s1, -4, 16, 20),
    subject: P.hoang,
    chair: P.tam,
    secretary: ME,
    parts: [
      [P.tam, 'CHU_TRI', 'DA_GOP_Y'],
      [P.hoang, 'NGUOI_DUOC_KIEM_DIEM', 'DA_GOP_Y'],
      [ME, 'THU_KY', 'DA_DOC'],
      [P.ha, 'THANH_VIEN', 'DA_DOC'],
      [P.vinh, 'THANH_VIEN', 'DA_GOP_Y'],
      [P.long, 'THANH_VIEN', 'CHUA_MO'],
      [P.mai, 'THANH_VIEN', 'VANG_CO_LY_DO'],
    ],
  });
  const d1 = new Date(m1.draftSentAt!);
  out.push({
    m: m1,
    comments: [
      comment(
        m1,
        P.tam,
        'UU_DIEM',
        'Chủ động tham mưu Đề án chuyển đổi số công tác tổ chức, hoàn thành trước tiến độ.',
        shiftWorkingDays(d1, 1, 9, 10),
      ),
      comment(
        m1,
        P.vinh,
        'HAN_CHE',
        'Báo cáo sơ kết chuyên đề chậm 3 ngày làm việc, chất lượng lần đầu phải sửa nội dung cơ bản.',
        shiftWorkingDays(d1, 2, 14, 30),
      ),
      comment(
        m1,
        P.hoang,
        'KIEN_NGHI',
        'Đề nghị chi bộ hỗ trợ phối hợp số liệu với các đảng uỷ cơ sở để báo cáo đúng hạn.',
        shiftWorkingDays(d1, 2, 16, 5),
      ),
      comment(
        m1,
        ME,
        'KIEN_NGHI',
        'Đề nghị bổ sung hướng khắc phục cụ thể đối với việc phối hợp số liệu với đảng uỷ cơ sở.',
        at(today, 8, 42),
        'HOI_NGHI',
      ),
    ],
    previous: [
      remediation(P.hoang, 'Chậm tổng hợp báo cáo tháng.', 'DA_KHAC_PHUC'),
      remediation(P.hoang, 'Phối hợp số liệu với cơ sở chưa chặt chẽ.', 'DANG_KHAC_PHUC'),
      remediation(P.hoang, 'Cập nhật minh chứng muộn so với hạn.', 'DA_KHAC_PHUC'),
    ],
    created: [],
  });

  // M2: người dùng là người được kiểm điểm, chưa gửi dự thảo; họp sau 2 ngày làm việc nên gửi hôm nay không đạt
  const m2 = meeting(2, {
    type: 'KIEM_DIEM_QUY',
    period: 'p-2026q4',
    myRole: 'NGUOI_DUOC_KIEM_DIEM',
    status: 'DU_KIEN',
    scheduledAt: shiftWorkingDays(today, 2, 14, 0),
    subject: ME,
    chair: P.tam,
    secretary: P.ha,
    parts: [
      [P.tam, 'CHU_TRI', 'CHUA_MO'],
      [P.ha, 'THU_KY', 'CHUA_MO'],
      [ME, 'NGUOI_DUOC_KIEM_DIEM', 'CHUA_MO'],
      [P.vinh, 'THANH_VIEN', 'CHUA_MO'],
      [P.long, 'THANH_VIEN', 'CHUA_MO'],
    ],
  });
  out.push({
    m: m2,
    comments: [],
    previous: [
      remediation(
        ME,
        'Chủ động hơn trong việc đôn đốc tiến độ nhiệm vụ được giao.',
        'DANG_KHAC_PHUC',
        'p-2026q3',
      ),
    ],
    created: [],
  });

  // M3: người dùng là chủ trì, đã họp và lập biên bản, chờ kết thúc hội nghị
  const s3 = shiftWorkingDays(today, -2, 8, 0);
  const m3 = meeting(3, {
    type: 'KIEM_DIEM_NAM',
    period: 'p-2026',
    myRole: 'CHU_TRI',
    status: 'DA_LAP_BIEN_BAN',
    scheduledAt: s3,
    draftSentAt: shiftWorkingDays(s3, -5, 10, 0),
    meetingAt: at(s3, 8, 5),
    subject: P.long,
    chair: ME,
    secretary: P.ha,
    parts: [
      [ME, 'CHU_TRI', 'CO_MAT'],
      [P.ha, 'THU_KY', 'CO_MAT'],
      [P.long, 'NGUOI_DUOC_KIEM_DIEM', 'CO_MAT'],
      [P.tam, 'THANH_VIEN', 'CO_MAT'],
      [P.vinh, 'THANH_VIEN', 'CO_MAT'],
      [P.mai, 'THANH_VIEN', 'VANG_CO_LY_DO'],
    ],
    vote: { HTXSNV: 0, HTTNV: 4, HTNV: 1, KHTNV: 0 },
    proposedLevel: 'HTTNV',
  });
  out.push({
    m: m3,
    comments: [
      comment(
        m3,
        P.tam,
        'UU_DIEM',
        'Hoàn thành đúng hạn các báo cáo định kỳ, số liệu chính xác.',
        shiftWorkingDays(s3, -3, 9, 0),
      ),
      comment(
        m3,
        P.vinh,
        'HAN_CHE',
        'Chưa chủ động đề xuất giải pháp khi tiến độ nhiệm vụ bị chậm.',
        shiftWorkingDays(s3, -2, 15, 0),
      ),
      comment(
        m3,
        P.ha,
        'HAN_CHE',
        'Việc lưu trữ hồ sơ điện tử còn chưa đầy đủ theo quy định.',
        at(s3, 9, 20),
        'HOI_NGHI',
      ),
    ],
    previous: [],
    created: [],
  });

  // M4: người dùng là thành viên, góp ý trước hội nghị
  const s4 = shiftWorkingDays(today, 5, 14, 0);
  const m4 = meeting(4, {
    type: 'KIEM_DIEM_NAM',
    period: 'p-2026',
    myRole: 'THANH_VIEN',
    status: 'DA_GUI_DU_THAO',
    scheduledAt: s4,
    draftSentAt: shiftWorkingDays(today, -1, 10, 30),
    subject: P.ha,
    chair: P.tam,
    secretary: P.vinh,
    parts: [
      [P.tam, 'CHU_TRI', 'DA_DOC'],
      [P.vinh, 'THU_KY', 'DA_DOC'],
      [P.ha, 'NGUOI_DUOC_KIEM_DIEM', 'DA_DOC'],
      [ME, 'THANH_VIEN', 'CHUA_MO'],
      [P.long, 'THANH_VIEN', 'CHUA_MO'],
    ],
  });
  out.push({
    m: m4,
    comments: [
      comment(
        m4,
        P.tam,
        'UU_DIEM',
        'Tham mưu tốt công tác quy hoạch cán bộ, hồ sơ chuẩn bị chu đáo.',
        at(today, 9, 0),
      ),
    ],
    previous: [],
    created: [],
  });

  // M5: người dùng là người được kiểm điểm, tenant bật ẩn danh
  const s5 = shiftWorkingDays(today, 4, 8, 30);
  const m5 = meeting(5, {
    type: 'CHUYEN_DE',
    period: 'p-2026q4',
    myRole: 'NGUOI_DUOC_KIEM_DIEM',
    status: 'DA_GUI_DU_THAO',
    scheduledAt: s5,
    draftSentAt: shiftWorkingDays(today, -1, 15, 0),
    subject: ME,
    chair: P.tam,
    secretary: P.ha,
    parts: [
      [P.tam, 'CHU_TRI', 'DA_GOP_Y'],
      [P.ha, 'THU_KY', 'DA_DOC'],
      [ME, 'NGUOI_DUOC_KIEM_DIEM', 'DA_DOC'],
      [P.long, 'THANH_VIEN', 'DA_GOP_Y'],
    ],
  });
  out.push({
    m: m5,
    comments: [
      comment(
        m5,
        P.tam,
        'UU_DIEM',
        'Tinh thần trách nhiệm cao, sẵn sàng nhận nhiệm vụ khó.',
        at(today, 8, 15),
      ),
      comment(
        m5,
        P.long,
        'HAN_CHE',
        'Cần sắp xếp thời gian hợp lý hơn để không dồn việc vào cuối kỳ.',
        at(today, 10, 40),
      ),
    ],
    previous: [],
    created: [],
  });

  // M6: hội nghị quý III đã ký biên bản
  const s6 = new Date('2026-10-08T08:00:00+07:00');
  const m6 = meeting(6, {
    type: 'KIEM_DIEM_QUY',
    period: 'p-2026q3',
    myRole: 'THANH_VIEN',
    status: 'DA_KY',
    scheduledAt: s6,
    draftSentAt: new Date('2026-10-02T09:00:00+07:00'),
    meetingAt: s6,
    subject: P.vinh,
    chair: P.tam,
    secretary: P.ha,
    parts: [
      [P.tam, 'CHU_TRI', 'CO_MAT'],
      [P.ha, 'THU_KY', 'CO_MAT'],
      [P.vinh, 'NGUOI_DUOC_KIEM_DIEM', 'CO_MAT'],
      [ME, 'THANH_VIEN', 'CO_MAT'],
    ],
    vote: { HTXSNV: 0, HTTNV: 3, HTNV: 0, KHTNV: 0 },
  });
  // ui-text-ok: mã yêu cầu ký giả lập, không hiển thị
  m6.signRequestId = '0192a3f4-1810-7000-8000-000000000006';
  out.push({ m: m6, comments: [], previous: [], created: [] });

  // M7: người dùng là chủ trì, dự thảo gửi muộn, thời hạn không đạt
  const s7 = shiftWorkingDays(today, 1, 8, 0);
  const m7 = meeting(7, {
    type: 'KIEM_DIEM_QUY',
    period: 'p-2026q4',
    myRole: 'CHU_TRI',
    status: 'DA_GUI_DU_THAO',
    scheduledAt: s7,
    draftSentAt: at(today, 9, 0),
    subject: P.mai,
    chair: ME,
    secretary: P.ha,
    parts: [
      [ME, 'CHU_TRI', 'DA_DOC'],
      [P.ha, 'THU_KY', 'DA_DOC'],
      [P.mai, 'NGUOI_DUOC_KIEM_DIEM', 'DA_DOC'],
      [P.vinh, 'THANH_VIEN', 'CHUA_MO'],
    ],
  });
  out.push({ m: m7, comments: [], previous: [], created: [] });
  return out;
}

/** Trường hợp đặc thù phát hiện trong kỳ (BR-11-05); người thuộc `org.json` và hội nghị mẫu. */
function specialCases(): DossierSpecialCase[] {
  const base = (
    person: { id: string; name: string },
    caseCode: string,
    legalRef: string,
    handling: string,
    effect: DossierSpecialCase['effect'],
  ): DossierSpecialCase => ({
    id: nextId('0192a3f4-1106-7000-8000-'),
    person,
    caseCode,
    legalRef,
    handling,
    detectedBy: 'TU_DONG',
    effect,
    effectiveFrom: null,
    effectiveTo: null,
  });
  const tuan = ORG_PERSONS.find((p) => p.id === 'p-tuan');
  const ha = ORG_PERSONS.find((p) => p.id === 'p-ha');
  return [
    ...(tuan
      ? [
          base(
            { id: tuan.id, name: tuan.name },
            'DIEU_DONG_DUOI_2_THANG',
            'PL 6 §1',
            'Đơn vị cũ đánh giá, đơn vị mới cho ý kiến',
            'XEP_LOAI_TAI_DON_VI_CU',
          ),
        ]
      : []),
    ...(ha
      ? [
          base(
            { id: ha.id, name: ha.name },
            'NGHI_THAI_SAN_OM_DAU_DAI_NGAY',
            'PL 6 §4',
            'Không xếp loại kỳ quý; ghi chú trên Mẫu 2',
            'KHONG_XEP_LOAI',
          ),
        ]
      : []),
    base(
      P.hoang,
      'KIEM_NHIEM',
      'PL 6 §3',
      'Đánh giá tại nơi giữ chức vụ chính, nơi kiêm nhiệm cho ý kiến',
      'XEP_LOAI_BINH_THUONG',
    ),
  ];
}
let cases: DossierSpecialCase[] = [];

function find(req: MockRequest): StoredMeeting | MockResponse {
  const s = meetings.find((x) => x.m.id === req.pathParams['id']);
  return s ?? problem(404, 'Không tìm thấy hội nghị kiểm điểm');
}
const isRes = (x: unknown): x is MockResponse =>
  !!x && typeof x === 'object' && 'status' in x && !('m' in x);

function versionGuard(req: MockRequest, rowVersion: number): MockResponse | null {
  const match = req.headers.get('If-Match');
  if (!match)
    return problem(
      428,
      'Dữ liệu trên màn hình đã cũ. Vui lòng tải lại rồi thực hiện lại thao tác.',
    );
  if (match.replace(/"/g, '') !== String(rowVersion))
    return problem(409, 'Dữ liệu đã được người khác cập nhật, vui lòng tải lại', {
      code: 'DOSSIER-409-VERSION-CONFLICT',
    });
  return null;
}

const etag = (m: { rowVersion: number }) => ({ ETag: `"${m.rowVersion}"` });
/** Trả bản sao như máy chủ thật, để màn hình không giữ chung tham chiếu với dữ liệu giả lập. */
const copy = <T>(x: T): T => structuredClone(x);

function list(req: MockRequest): MockResponse {
  const period = req.params.get('period');
  const status = req.params.get('status');
  const items = meetings
    .map((s) => s.m)
    .filter((m) => (!period || m.period.id === period) && (!status || m.status === status))
    .sort((a, b) => b.scheduledAt.localeCompare(a.scheduledAt))
    .map(({ id, title: t, meetingType, orgUnit, period: p, scheduledAt, status: st, myRole }) => ({
      id,
      title: t,
      meetingType,
      orgUnit,
      period: p,
      scheduledAt,
      status: st,
      ...(myRole ? { myRole } : {}),
    }));
  return ok({ items, nextCursor: null });
}

function create(req: MockRequest): MockResponse {
  const b = (req.body ?? {}) as Partial<DossierMeetingCreate>;
  const errors: { field: string; message: string }[] = [];
  const period = b.periodId ? PERIODS[b.periodId] : undefined;
  if (!period) errors.push({ field: 'periodId', message: 'Chọn kỳ kiểm điểm.' });
  if (!b.meetingType) errors.push({ field: 'meetingType', message: 'Chọn loại hội nghị.' });
  if (!b.scheduledAt || isNaN(Date.parse(b.scheduledAt)))
    errors.push({ field: 'scheduledAt', message: 'Nhập ngày giờ hội nghị.' });
  const person = (id?: string) => ORG_PERSONS.find((p) => p.id === id);
  const chair = person(b.chairPersonId);
  const secretary = person(b.secretaryPersonId);
  const subject = b.subjectPersonId ? person(b.subjectPersonId) : undefined;
  if (!chair) errors.push({ field: 'chairPersonId', message: 'Chọn chủ trì hội nghị.' });
  if (!secretary) errors.push({ field: 'secretaryPersonId', message: 'Chọn thư ký hội nghị.' });
  else if (chair && chair.id === secretary.id)
    errors.push({ field: 'secretaryPersonId', message: 'Thư ký phải khác chủ trì.' });
  if (b.subjectPersonId && !subject)
    errors.push({ field: 'subjectPersonId', message: 'Không tìm thấy người được kiểm điểm.' });
  const others = (b.participantPersonIds ?? []).map(person);
  const all = [chair, secretary, subject, ...others].filter((p) => !!p);
  if (all.some((p) => p.unitId !== b.orgUnitId))
    errors.push({
      field: 'participantPersonIds',
      message: 'Thành phần dự phải thuộc đơn vị tổ chức hội nghị.',
    });
  if (b.location && b.location.length > 500)
    errors.push({ field: 'location', message: 'Địa điểm tối đa 500 ký tự.' });
  if (errors.length)
    return problem(422, 'Dữ liệu không hợp lệ', { code: 'DOSSIER-422-VALIDATION', errors });
  if (
    subject &&
    meetings.some(
      (s) =>
        s.m.subjectPerson?.id === subject.id &&
        s.m.period.id === b.periodId &&
        s.m.meetingType === b.meetingType,
    )
  )
    return problem(409, 'Đồng chí này đã có hội nghị kiểm điểm cùng loại trong kỳ.', {
      code: 'DOSSIER-409-MEETING-EXISTS',
    });
  const ref = (p: { id: string; name: string }) => ({ id: p.id, name: p.name });
  const unitName = all[0]?.unitName ?? UNIT.name;
  const n = 100 + ++seq;
  const formId = `0192a3f4-1103-7000-8000-${String(n).padStart(12, '0')}`;
  if (subject)
    draftFiles.set(formId, [
      'Mau 1B - Ban kiem diem ca nhan (du thao)',
      `Nguoi duoc kiem diem: ${subject.name}`,
      `Ky: ${period!.label}`,
    ]);
  const seen = new Set<string>();
  const parts: DossierMeeting['participants'] = [];
  const add = (p: (typeof all)[number] | undefined, role: DossierMeetingRole) => {
    if (!p || seen.has(p.id)) return;
    seen.add(p.id);
    parts.push({ person: ref(p), role, status: 'CHUA_MO' });
  };
  add(chair, 'CHU_TRI');
  add(secretary, 'THU_KY');
  add(subject, 'NGUOI_DUOC_KIEM_DIEM');
  others.forEach((p) => add(p, 'THANH_VIEN'));
  const scheduledAt = new Date(b.scheduledAt!).toISOString();
  const m: DossierMeeting = {
    id: meetingId(n),
    title: title(b.meetingType!, period!, unitName),
    meetingType: b.meetingType!,
    orgUnit: { id: b.orgUnitId!, name: unitName },
    period: period!,
    scheduledAt,
    status: 'DU_KIEN',
    location: b.location ?? '',
    meetingAt: null,
    subjectPerson: subject ? ref(subject) : null,
    chair: ref(chair!),
    secretary: ref(secretary!),
    draftSentAt: null,
    deadlineCheck: deadline(null, scheduledAt),
    participants: parts,
    draftForms: subject ? [{ id: formId, formCode: 'MAU_1B', status: 'DA_SINH' }] : [],
    proposedLevel: 'HTTNV',
    vote: null,
    minutesSha256: null,
    signRequestId: null,
    commentsLocked: false,
    rowVersion: 1,
  };
  meetings.push({ m, comments: [], previous: [], created: [] });
  return {
    status: 201,
    body: copy(m),
    headers: { ...etag(m), Location: `/api/v1/dossiers/meetings/${m.id}` },
  };
}

function update(req: MockRequest): MockResponse {
  const s = find(req);
  if (isRes(s)) return s;
  const v = versionGuard(req, s.m.rowVersion);
  if (v) return v;
  if (s.m.status !== 'DU_KIEN' && s.m.status !== 'DA_GUI_DU_THAO')
    return problem(409, 'Hội nghị đã họp, không sửa được kế hoạch.', {
      code: 'DOSSIER-409-MEETING-HELD',
    });
  const b = (req.body ?? {}) as DossierMeetingUpdate;
  if (!Object.keys(b).length)
    return problem(422, 'Dữ liệu không hợp lệ', {
      errors: [{ field: 'body', message: 'Không có thay đổi.' }],
    });
  if (b.deadlineOverrideReason !== undefined) {
    if (s.m.myRole !== 'CHU_TRI')
      return problem(403, 'Chỉ chủ trì hội nghị được ghi lý do giữ ngày họp.', {
        code: 'DOSSIER-403-NOT-CHAIR',
      });
    const r = b.deadlineOverrideReason.trim();
    if (r.length < 10 || r.length > 2000)
      return problem(422, 'Dữ liệu không hợp lệ', {
        errors: [{ field: 'deadlineOverrideReason', message: 'Lý do từ 10 đến 2.000 ký tự.' }],
      });
  }
  if (b.scheduledAt !== undefined && isNaN(Date.parse(b.scheduledAt)))
    return problem(422, 'Dữ liệu không hợp lệ', {
      errors: [{ field: 'scheduledAt', message: 'Ngày giờ không hợp lệ.' }],
    });
  if (b.scheduledAt) s.m.scheduledAt = new Date(b.scheduledAt).toISOString();
  if (b.location !== undefined) s.m.location = b.location;
  const reason = b.deadlineOverrideReason?.trim() ?? s.m.deadlineCheck.overrideReason ?? null;
  s.m.deadlineCheck = deadline(s.m.draftSentAt, s.m.scheduledAt, reason);
  s.m.rowVersion++;
  return ok(copy(s.m), etag(s.m));
}

function sendDraft(req: MockRequest): MockResponse {
  const s = find(req);
  if (isRes(s)) return s;
  const v = versionGuard(req, s.m.rowVersion);
  if (v) return v;
  if (s.m.myRole !== 'NGUOI_DUOC_KIEM_DIEM')
    return problem(403, 'Chỉ người được kiểm điểm gửi dự thảo của mình.', {
      code: 'DOSSIER-403-NOT-SUBJECT',
    });
  if (s.m.status !== 'DU_KIEN')
    return problem(409, 'Dự thảo đã được gửi trước đó.', { code: 'DOSSIER-409-INVALID-STATE' });
  s.m.draftSentAt = new Date().toISOString();
  s.m.deadlineCheck = deadline(s.m.draftSentAt, s.m.scheduledAt);
  s.m.status = 'DA_GUI_DU_THAO';
  s.m.participants = s.m.participants?.map((p) =>
    p.role === 'NGUOI_DUOC_KIEM_DIEM' ? { ...p, status: 'DA_DOC' } : p,
  );
  s.m.rowVersion++;
  return ok(copy(s.m), etag(s.m));
}

function listComments(req: MockRequest): MockResponse {
  const s = find(req);
  if (isRes(s)) return s;
  const phase = req.params.get('phase');
  const kind = req.params.get('kind');
  const hide = ANONYMOUS && s.m.myRole === 'NGUOI_DUOC_KIEM_DIEM';
  const items = s.comments
    .filter((c) => (!phase || c.phase === phase) && (!kind || c.kind === kind))
    .map((c) => (hide ? { ...c, commenter: null } : c));
  return ok(copy({ anonymous: ANONYMOUS, items, nextCursor: null }));
}

function addComment(req: MockRequest): MockResponse {
  const s = find(req);
  if (isRes(s)) return s;
  const b = (req.body ?? {}) as Partial<DossierCommentCreate>;
  if (!s.m.myRole)
    return problem(403, 'Đồng chí không thuộc thành phần hội nghị.', {
      code: 'DOSSIER-403-NOT-PARTICIPANT',
    });
  if (b.phase === 'HOI_NGHI' && s.m.myRole !== 'THU_KY')
    return problem(403, 'Chỉ thư ký hội nghị ghi ý kiến tại hội nghị.', {
      code: 'DOSSIER-403-NOT-SECRETARY',
    });
  if (s.m.commentsLocked || s.m.status === 'DA_KY')
    return problem(409, 'Hội nghị đã kết thúc, không nhận thêm góp ý.', {
      code: 'DOSSIER-409-COMMENTS-LOCKED',
    });
  if (s.m.status === 'DU_KIEN')
    return problem(409, 'Dự thảo chưa được gửi, chưa nhận góp ý.', {
      code: 'DOSSIER-409-INVALID-STATE',
    });
  const content = (b.content ?? '').trim();
  const errors: { field: string; message: string }[] = [];
  if (!b.kind || !['UU_DIEM', 'HAN_CHE', 'KIEN_NGHI'].includes(b.kind))
    errors.push({ field: 'kind', message: 'Chọn loại nội dung.' });
  if (!content || content.length > 4000)
    errors.push({ field: 'content', message: 'Nội dung từ 1 đến 4.000 ký tự.' });
  if (errors.length) return problem(422, 'Dữ liệu không hợp lệ', { errors });
  const c = comment(s.m, ME, b.kind!, content, new Date(), b.phase ?? 'TRUOC_HOI_NGHI');
  s.comments.push(c);
  if (c.phase === 'TRUOC_HOI_NGHI')
    s.m.participants = s.m.participants?.map((p) =>
      p.person.id === ME.id ? { ...p, status: 'DA_GOP_Y' } : p,
    );
  return {
    status: 201,
    body: copy(c),
    headers: { Location: `/api/v1/dossiers/meetings/${s.m.id}/comments/${c.id}` },
  };
}

function stepUpRequired(): MockResponse {
  const r = problem(401, 'Cần xác thực nhiều lớp trước khi lập biên bản', {
    type: 'https://datatracker.ietf.org/doc/html/rfc9470',
    code: 'insufficient_user_authentication',
  });
  r.headers = {
    ...r.headers,
    // ui-text-ok: tiêu đề HTTP theo RFC 9470, không hiện lên màn hình
    'WWW-Authenticate':
      'Bearer error="insufficient_user_authentication", acr_values="aal2", max_age=900',
  };
  return r;
}

function minutes(req: MockRequest): MockResponse {
  const s = find(req);
  if (isRes(s)) return s;
  const v = versionGuard(req, s.m.rowVersion);
  if (v) return v;
  if (s.m.myRole !== 'THU_KY')
    return problem(403, 'Chỉ thư ký hội nghị lập biên bản.', { code: 'DOSSIER-403-NOT-SECRETARY' });
  if (!mockSession.recentMfa()) return stepUpRequired();
  if (s.m.status !== 'DA_GUI_DU_THAO' && s.m.status !== 'DA_HOP')
    return problem(409, 'Hội nghị chưa gửi dự thảo hoặc đã kết thúc.', {
      code: 'DOSSIER-409-INVALID-STATE',
    });
  const b = (req.body ?? {}) as Partial<DossierMinutesRequest>;
  const attendance = b.attendance ?? [];
  if (!attendance.length || !b.vote)
    return problem(422, 'Dữ liệu không hợp lệ', {
      errors: [{ field: 'attendance', message: 'Cần điểm danh và kết quả biểu quyết.' }],
    });
  const present = attendance.filter((a) => a.attendance === 'CO_MAT').length;
  const total = Object.values(b.vote).reduce((a, n) => a + (n ?? 0), 0);
  if (Object.values(b.vote).some((n) => (n ?? 0) < 0) || total > present)
    return problem(422, 'Tổng số phiếu biểu quyết vượt số người có mặt.', {
      code: 'DOSSIER-422-VOTE-EXCEEDS-ATTENDEES',
      errors: [
        { field: 'vote', message: `Tổng số phiếu (${total}) vượt số người có mặt (${present}).` },
      ],
    });
  const status = new Map(attendance.map((a) => [a.personId, a.attendance]));
  s.m.participants = s.m.participants?.map((p) => ({
    ...p,
    status: status.get(p.person.id) ?? p.status,
  }));
  s.m.vote = b.vote;
  s.m.meetingAt = b.meetingAt ?? new Date().toISOString();
  s.m.status = 'DA_LAP_BIEN_BAN';
  s.m.minutesSha256 = hex(64);
  s.m.rowVersion++;
  const id = crypto.randomUUID();
  jobs.set(id, {
    id,
    type: 'RENDER',
    status: 'HOAN_TAT',
    progress: 100,
    message: 'Đã sinh biên bản hội nghị',
    resultLocation: `/api/v1/dossiers/meetings/${s.m.id}`,
  });
  return {
    status: 202,
    body: { id, type: 'RENDER', status: 'CHO', progress: 0 },
    headers: { Location: `/api/v1/dossiers/form-jobs/${id}` },
  };
}

function close(req: MockRequest): MockResponse {
  const s = find(req);
  if (isRes(s)) return s;
  const v = versionGuard(req, s.m.rowVersion);
  if (v) return v;
  if (s.m.myRole !== 'CHU_TRI')
    return problem(403, 'Chỉ chủ trì kết thúc hội nghị.', { code: 'DOSSIER-403-NOT-CHAIR' });
  if (!s.m.vote)
    return problem(409, 'Chưa có kết quả biểu quyết, chưa kết thúc được hội nghị.', {
      code: 'DOSSIER-409-NO-VOTE',
    });
  if (s.m.commentsLocked)
    return problem(409, 'Hội nghị đã kết thúc.', { code: 'DOSSIER-409-INVALID-STATE' });
  s.m.commentsLocked = true;
  s.created = s.comments
    .filter((c) => c.kind === 'HAN_CHE')
    .map((c) => ({
      ...remediation(s.m.subjectPerson ?? ME, c.content, 'MO', s.m.period.id),
      meetingId: s.m.id,
      reviewCommentId: c.id,
    }));
  if (s.m.status === 'DA_LAP_BIEN_BAN') s.m.signRequestId = crypto.randomUUID();
  s.m.rowVersion++;
  return ok(copy(s.m), etag(s.m));
}

function remediationList(req: MockRequest): MockResponse {
  const s = find(req);
  if (isRes(s)) return s;
  const origin = req.params.get('origin');
  const items =
    origin === 'KY_TRUOC'
      ? s.previous
      : origin === 'HOI_NGHI_NAY'
        ? s.created
        : [...s.previous, ...s.created];
  return ok(copy({ items, nextCursor: null }));
}

function updateRemediation(req: MockRequest): MockResponse {
  const id = req.pathParams['id'];
  const owner = meetings.find((s) => [...s.previous, ...s.created].some((i) => i.id === id));
  const item = owner && [...owner.previous, ...owner.created].find((i) => i.id === id);
  if (!owner || !item) return problem(404, 'Không tìm thấy hạn chế cần khắc phục');
  const v = versionGuard(req, item.rowVersion);
  if (v) return v;
  if (owner.m.myRole !== 'THU_KY')
    return problem(403, 'Chỉ thư ký hội nghị cập nhật trạng thái khắc phục.', {
      code: 'DOSSIER-403-NOT-SECRETARY',
    });
  const b = (req.body ?? {}) as Partial<DossierRemediationItemUpdate>;
  if (!b.status || !['MO', 'DANG_KHAC_PHUC', 'DA_KHAC_PHUC', 'CHUA_KHAC_PHUC'].includes(b.status))
    return problem(422, 'Dữ liệu không hợp lệ', {
      errors: [{ field: 'status', message: 'Chọn trạng thái khắc phục.' }],
    });
  item.status = b.status;
  if (b.status === 'DA_KHAC_PHUC' || b.status === 'CHUA_KHAC_PHUC') {
    item.verifiedBy = ME;
    item.verifiedAt = new Date().toISOString();
  }
  item.rowVersion++;
  return ok(copy(item), etag(item));
}

function specialCaseList(req: MockRequest): MockResponse {
  if (!req.params.get('period'))
    return problem(422, 'Dữ liệu không hợp lệ', {
      errors: [{ field: 'period', message: 'Chọn kỳ.' }],
    });
  const groups = [...new Map(cases.map((c) => [c.legalRef, c])).values()].map((c) => ({
    legalRef: c.legalRef,
    caseCode: c.caseCode,
    handling: c.handling,
    count: cases.filter((x) => x.legalRef === c.legalRef).length,
  }));
  return ok({ groups, items: cases, nextCursor: null });
}

/** Tệp dự thảo Mẫu 1B của hội nghị (`GET /dossiers/forms/{id}/file`). */
export function meetingDraftFile(id: string): string[] | undefined {
  return draftFiles.get(id);
}

/** Job sinh biên bản (`Location` của "Sinh biên bản"). */
export function meetingJob(id: string): Job | undefined {
  return jobs.get(id);
}

export function resetMeetings(): void {
  jobs.clear();
  meetings = seed();
  cases = specialCases();
}
resetMeetings();

export const MEETING_ROUTES: MockRoute[] = [
  { method: 'GET', pattern: '/api/v1/dossiers/meetings', handle: list },
  { method: 'POST', pattern: '/api/v1/dossiers/meetings', handle: create },
  {
    method: 'GET',
    pattern: '/api/v1/dossiers/meetings/:id',
    handle: (req) => {
      const s = find(req);
      return isRes(s) ? s : ok(copy(s.m), etag(s.m));
    },
  },
  { method: 'PATCH', pattern: '/api/v1/dossiers/meetings/:id', handle: update },
  { method: 'POST', pattern: '/api/v1/dossiers/meetings/:id/send-draft', handle: sendDraft },
  { method: 'GET', pattern: '/api/v1/dossiers/meetings/:id/comments', handle: listComments },
  { method: 'POST', pattern: '/api/v1/dossiers/meetings/:id/comments', handle: addComment },
  { method: 'POST', pattern: '/api/v1/dossiers/meetings/:id/minutes', handle: minutes },
  { method: 'POST', pattern: '/api/v1/dossiers/meetings/:id/close', handle: close },
  {
    method: 'GET',
    pattern: '/api/v1/dossiers/meetings/:id/remediation-items',
    handle: remediationList,
  },
  { method: 'PATCH', pattern: '/api/v1/dossiers/remediation-items/:id', handle: updateRemediation },
  { method: 'GET', pattern: '/api/v1/dossiers/special-cases', handle: specialCaseList },
];
