import {
  ComplainableProfile,
  Complaint,
  ComplaintResponseRecord,
} from '../../lib/models/complaint';
import { MOCK_ME } from '../mock-session';

/**
 * Dữ liệu mẫu QT-12 (WEB-042), dựng theo ví dụ KN-2027-0031 của TL-02 (Hình 11.3, 11.4) nhưng đặt vào kỳ
 * Quý I, Quý II/2026 đã `DA_KHOA` của giả lập kỳ đánh giá. Người dùng thử nghiệm đóng nhiều vai: người
 * khiếu nại (KN-2026-0038 đã trả lời, KN-2026-0050 nháp), người xử lý (KN-2026-0040 chờ kết luận,
 * KN-2026-0045 mới, KN-2026-0042 mới nhưng vướng tách vai trò), bên được yêu cầu giải trình (KN-2026-0044).
 * KN-2026-0031 đã kết luận, gắn yêu cầu mở lại kỳ MLK-2026-007 của giả lập QT-13.
 */
export const COMPLAINT_PEOPLE = {
  hoang: { id: 'u-tran-minh-hoang', name: 'Trần Minh Hoàng' },
  binh: { id: 'u-nguyen-van-binh', name: 'Nguyễn Văn Bình' },
  lan: { id: 'u-pham-thi-lan', name: 'Phạm Thị Lan' },
  khanh: { id: 'u-vu-quoc-khanh', name: 'Vũ Quốc Khánh' },
  mai: { id: 'u-nguyen-thi-mai', name: 'Nguyễn Thị Mai' },
  ha: { id: 'u-le-thu-ha', name: 'Lê Thu Hà' },
  tam: { id: 'u-ngo-thanh-tam', name: 'Ngô Thanh Tâm' },
  me: { id: MOCK_ME.id, name: MOCK_ME.name },
} as const;

export const BAN_TO_CHUC = { id: 'u-btc', name: 'Ban Tổ chức' };
export const DANG_UY_X = { id: 'un-dang-uy-co-so-x', name: 'Đảng uỷ cơ sở X' };

const Q2 = { id: 'p-2026q2', label: 'Quý II/2026', startDate: '2026-04-01', endDate: '2026-06-30' };
const Q1 = { id: 'p-2026q1', label: 'Quý I/2026', startDate: '2026-01-01', endDate: '2026-03-31' };

export const MY_PROFILE_Q2 = 'ap-2026q2-dev-user';
export const MY_PROFILE_Q1 = 'ap-2026q1-dev-user';

/** Kết quả hiện hành của hồ sơ (để tính thử) và người đã tham gia hồ sơ (tách vai trò, BR-12-04). */
export const PROFILE_FACTS: Record<
  string,
  { score: number; rating: 'HTXSNV' | 'HTTNV' | 'HTNV' | 'KHTNV'; participants: string[] }
> = {
  'ap-2026q2-tran-minh-hoang': {
    score: 91.2,
    rating: 'HTTNV',
    participants: [COMPLAINT_PEOPLE.tam.id, 'u-phan-van-quan'],
  },
  'ap-2026q1-nguyen-van-binh': {
    score: 84.6,
    rating: 'HTTNV',
    participants: [COMPLAINT_PEOPLE.tam.id],
  },
  'ap-2026q2-pham-thi-lan': { score: 82, rating: 'HTTNV', participants: [COMPLAINT_PEOPLE.tam.id] },
  'ap-2026q1-vu-quoc-khanh': {
    score: 68.5,
    rating: 'HTNV',
    participants: [MOCK_ME.id],
  },
  'ap-2026q2-nguyen-thi-mai': { score: 79.4, rating: 'HTNV', participants: [MOCK_ME.id] },
  [MY_PROFILE_Q2]: { score: 88.5, rating: 'HTTNV', participants: [COMPLAINT_PEOPLE.ha.id] },
  [MY_PROFILE_Q1]: { score: 79, rating: 'HTNV', participants: [COMPLAINT_PEOPLE.ha.id] },
};

const at = (offset: number, hhmm = '08:00') => {
  const d = new Date();
  d.setDate(d.getDate() + offset);
  const [h, m] = hhmm.split(':').map(Number);
  d.setHours(h, m, 0, 0);
  return d.toISOString();
};

/** Ngày (yyyy-mm-dd) sau `n` ngày làm việc kể từ hôm nay; `n` âm thì lùi (bỏ thứ Bảy, Chủ nhật). */
export function workingDate(n: number, from = new Date()): string {
  const d = new Date(from);
  let left = Math.abs(n);
  const step = n < 0 ? -1 : 1;
  while (left > 0) {
    d.setDate(d.getDate() + step);
    if (d.getDay() !== 0 && d.getDay() !== 6) left--;
  }
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

export function eligibleFixtures(): ComplainableProfile[] {
  return [
    {
      profileId: MY_PROFILE_Q2,
      period: Q2,
      rating: 'HTTNV',
      score: 88.5,
      publishedAt: at(-3, '08:00'),
      publishDecision: 'QĐ 215-QĐ/ĐUK',
      complaintDeadline: workingDate(7),
      workingDaysLeft: 7,
      allowedKinds: ['KHIEU_NAI', 'GIAI_TRINH', 'BAO_LUU'],
      targets: [
        { key: 'NV-THEN-CHOT-1.B', stage: 'THAM_DINH', label: 'Nhiệm vụ then chốt 1 · B = 0,75' },
        { key: 'NV-THEN-CHOT-2.B', stage: 'THAM_DINH', label: 'Nhiệm vụ then chốt 2 · B = 0,50' },
        { key: 'NV-THEN-CHOT-3.C', stage: 'THAM_DINH', label: 'Nhiệm vụ then chốt 3 · C = 0,85' },
        { key: 'TIEU-CHI-CHUNG-5', stage: 'DE_XUAT', label: 'Tiêu chí chung 5 · Khá' },
        { key: 'XEP-LOAI', stage: 'QUYET_DINH', label: 'Xếp loại · Hoàn thành tốt nhiệm vụ' },
      ],
    },
    {
      profileId: MY_PROFILE_Q1,
      period: Q1,
      rating: 'HTNV',
      score: 79,
      publishedAt: at(-70, '08:00'),
      publishDecision: 'QĐ 142-QĐ/ĐUK',
      complaintDeadline: workingDate(-40),
      workingDaysLeft: -40,
      allowedKinds: ['BAO_LUU'],
      targets: [
        {
          key: 'NV-THUONG-XUYEN-2.C',
          stage: 'THAM_DINH',
          label: 'Nhiệm vụ thường xuyên 2 · C = 0,80',
        },
        { key: 'XEP-LOAI', stage: 'QUYET_DINH', label: 'Xếp loại · Hoàn thành nhiệm vụ' },
      ],
    },
  ];
}

export function complaintFixtures(): Complaint[] {
  const P = COMPLAINT_PEOPLE;
  return [
    {
      id: 'kn-2026-0031',
      code: 'KN-2026-0031',
      complainant: P.hoang,
      subjectLine: 'Khiếu nại kết quả xếp loại Quý II/2026',
      kind: 'KHIEU_NAI',
      targetStage: 'THAM_DINH',
      status: 'DANG_THI_HANH',
      filedAt: at(-32, '09:12'),
      responseDeadline: workingDate(-8),
      workingDaysLeft: null,
      profileId: 'ap-2026q2-tran-minh-hoang',
      period: Q2,
      targets: [
        { key: 'NV-THEN-CHOT-3.C', stage: 'THAM_DINH', label: 'Nhiệm vụ then chốt 3 · C = 0,85' },
      ],
      content:
        'Nhiệm vụ then chốt 3 bị trừ hệ số C do chậm 6 ngày. Nguyên nhân chậm là Đảng uỷ cơ sở X gửi hồ sơ muộn, có công văn 45-CV/ĐU xác nhận; đề nghị áp miễn trừ chậm do khách quan theo PL 3 mục 2.',
      requestText: 'Đề nghị không trừ hệ số C của nhiệm vụ then chốt 3.',
      reserveOpinion: true,
      reservedOpinion: null,
      attachmentObjectIds: ['f-0031-cv-45'],
      handlerUnit: BAN_TO_CHUC,
      handler: P.ha,
      outcome: 'DIEU_CHINH',
      conclusion: {
        outcome: 'CHAP_NHAN_MOT_PHAN',
        adjustments: [
          {
            targetKey: 'NV-THEN-CHOT-3.C',
            oldValue: 0.85,
            newValue: 1,
            reason: 'Miễn trừ chậm do khách quan theo PL 3 mục 2.',
          },
        ],
        responseDocRef: '08-CV/BTC',
        signRequestId: 'sr-0031',
        periodLocked: true,
      },
      reopenRequestId: 'rr-2026-007',
      timeline: [
        { at: at(-32, '09:12'), action: 'Gửi khiếu nại', actor: P.hoang },
        { at: at(-31, '10:30'), action: 'Tiếp nhận', actor: P.ha },
        { at: at(-31, '14:05'), action: 'Yêu cầu giải trình', actor: P.ha },
        { at: at(-28, '16:40'), action: 'Đã nhận giải trình' },
        { at: at(-12, '16:20'), action: 'Kết luận: chấp nhận một phần', actor: P.ha },
        {
          at: at(-11, '09:00'),
          action: 'Ký văn bản trả lời 08-CV/BTC',
          actor: { id: 'u-phan-van-quan', name: 'Phan Văn Quân' },
        },
      ],
      rowVersion: 9,
    },
    {
      id: 'kn-2026-0040',
      code: 'KN-2026-0040',
      complainant: P.binh,
      subjectLine: 'Khiếu nại kết quả xếp loại Quý I/2026',
      kind: 'KHIEU_NAI',
      targetStage: 'THAM_DINH',
      status: 'CHO_KET_LUAN',
      filedAt: at(-9, '14:20'),
      responseDeadline: workingDate(5),
      workingDaysLeft: 5,
      profileId: 'ap-2026q1-nguyen-van-binh',
      period: Q1,
      targets: [
        { key: 'NV-THEN-CHOT-2.B', stage: 'THAM_DINH', label: 'Nhiệm vụ then chốt 2 · B = 0,50' },
        {
          key: 'NV-THUONG-XUYEN-4.C',
          stage: 'THAM_DINH',
          label: 'Nhiệm vụ thường xuyên 4 · C = 0,80',
        },
      ],
      content:
        'Sản phẩm của nhiệm vụ then chốt 2 được đánh giá mức B = 0,50 dù báo cáo đã được Thường trực Đảng uỷ thông qua không phải sửa. Nhiệm vụ thường xuyên 4 bị trừ C vì nộp chậm 3 ngày trong khi hạn bị dời theo Thông báo 27-TB/ĐUK nhưng hệ thống chưa cập nhật hạn mới.',
      requestText:
        'Đề nghị nâng mức B của nhiệm vụ then chốt 2 lên 0,75 và không trừ C của nhiệm vụ thường xuyên 4.',
      reserveOpinion: false,
      reservedOpinion: null,
      attachmentObjectIds: ['f-0040-tb-27', 'f-0040-bien-ban'],
      handlerUnit: BAN_TO_CHUC,
      handler: P.me,
      outcome: null,
      conclusion: null,
      reopenRequestId: null,
      timeline: [
        { at: at(-9, '14:20'), action: 'Gửi khiếu nại', actor: P.binh },
        { at: at(-8, '09:05'), action: 'Tiếp nhận', actor: P.me },
        { at: at(-8, '09:20'), action: 'Yêu cầu giải trình', actor: P.me },
        { at: at(-6, '15:10'), action: 'Đã nhận giải trình', actor: P.tam },
      ],
      rowVersion: 6,
    },
    {
      id: 'kn-2026-0045',
      code: 'KN-2026-0045',
      complainant: P.lan,
      subjectLine: 'Giải trình bổ sung kết quả Quý II/2026',
      kind: 'GIAI_TRINH',
      targetStage: 'DE_XUAT',
      status: 'MOI',
      filedAt: at(-1, '16:45'),
      responseDeadline: workingDate(14),
      workingDaysLeft: 14,
      profileId: 'ap-2026q2-pham-thi-lan',
      period: Q2,
      targets: [
        { key: 'TIEU-CHI-CHUNG-3', stage: 'DE_XUAT', label: 'Tiêu chí chung 3 · Trung bình' },
      ],
      content:
        'Trong quý tôi được cử đi học tập trung 6 tuần theo Quyết định 88-QĐ/ĐUK nên không tham gia sinh hoạt chi bộ hai kỳ; đề nghị ghi nhận lý do này khi xem xét tiêu chí chung 3.',
      requestText: 'Đề nghị ghi nhận lý do vắng sinh hoạt vào hồ sơ đánh giá.',
      reserveOpinion: false,
      reservedOpinion: null,
      attachmentObjectIds: ['f-0045-qd-88'],
      handlerUnit: BAN_TO_CHUC,
      handler: P.me,
      outcome: null,
      conclusion: null,
      reopenRequestId: null,
      timeline: [{ at: at(-1, '16:45'), action: 'Gửi khiếu nại', actor: P.lan }],
      rowVersion: 1,
    },
    {
      id: 'kn-2026-0042',
      code: 'KN-2026-0042',
      complainant: P.khanh,
      subjectLine: 'Khiếu nại kết quả xếp loại Quý I/2026',
      kind: 'KHIEU_NAI',
      targetStage: 'THAM_DINH',
      status: 'MOI',
      filedAt: at(-2, '10:00'),
      responseDeadline: workingDate(13),
      workingDaysLeft: 13,
      profileId: 'ap-2026q1-vu-quoc-khanh',
      period: Q1,
      targets: [
        {
          key: 'NV-THUONG-XUYEN-1.B',
          stage: 'THAM_DINH',
          label: 'Nhiệm vụ thường xuyên 1 · B = 0,25',
        },
      ],
      content:
        'Sản phẩm nhiệm vụ thường xuyên 1 bị đánh giá mức B thấp nhất nhưng người nghiệm thu không nêu tiêu chuẩn nào chưa đạt; đề nghị xem xét lại mức chất lượng.',
      requestText: 'Đề nghị đánh giá lại mức B của nhiệm vụ thường xuyên 1.',
      reserveOpinion: true,
      reservedOpinion: null,
      attachmentObjectIds: [],
      handlerUnit: BAN_TO_CHUC,
      handler: P.me,
      outcome: null,
      conclusion: null,
      reopenRequestId: null,
      timeline: [{ at: at(-2, '10:00'), action: 'Gửi khiếu nại', actor: P.khanh }],
      rowVersion: 1,
    },
    {
      id: 'kn-2026-0044',
      code: 'KN-2026-0044',
      complainant: P.mai,
      subjectLine: 'Khiếu nại kết quả nghiệm thu Quý II/2026',
      kind: 'KHIEU_NAI',
      targetStage: 'THAM_DINH',
      status: 'DANG_GIAI_TRINH',
      filedAt: at(-5, '08:40'),
      responseDeadline: workingDate(10),
      workingDaysLeft: 10,
      profileId: 'ap-2026q2-nguyen-thi-mai',
      period: Q2,
      targets: [
        { key: 'NV-THEN-CHOT-4.B', stage: 'THAM_DINH', label: 'Nhiệm vụ then chốt 4 · B = 0,50' },
      ],
      content:
        'Báo cáo chuyên đề của nhiệm vụ then chốt 4 được yêu cầu sửa vì thiếu số liệu quý I, trong khi đề cương được duyệt chỉ yêu cầu số liệu quý II; đề nghị xem lại mức chất lượng.',
      requestText: 'Đề nghị nâng mức B của nhiệm vụ then chốt 4 lên 0,75.',
      reserveOpinion: false,
      reservedOpinion: null,
      attachmentObjectIds: ['f-0044-de-cuong'],
      handlerUnit: BAN_TO_CHUC,
      handler: P.ha,
      outcome: null,
      conclusion: null,
      reopenRequestId: null,
      timeline: [
        { at: at(-5, '08:40'), action: 'Gửi khiếu nại', actor: P.mai },
        { at: at(-4, '10:15'), action: 'Tiếp nhận', actor: P.ha },
        { at: at(-4, '10:30'), action: 'Yêu cầu giải trình', actor: P.ha },
      ],
      rowVersion: 3,
    },
    {
      id: 'kn-2026-0038',
      code: 'KN-2026-0038',
      complainant: P.me,
      subjectLine: 'Khiếu nại kết quả xếp loại Quý I/2026',
      kind: 'KHIEU_NAI',
      targetStage: 'QUYET_DINH',
      status: 'DA_TRA_LOI',
      filedAt: at(-62, '09:30'),
      responseDeadline: workingDate(-30),
      workingDaysLeft: null,
      profileId: MY_PROFILE_Q1,
      period: Q1,
      targets: [{ key: 'XEP-LOAI', stage: 'QUYET_DINH', label: 'Xếp loại · Hoàn thành nhiệm vụ' }],
      content:
        'Điểm của tôi là 79,0, chỉ thấp hơn ngưỡng HTTNV 1 điểm; nhiệm vụ thường xuyên 2 bị trừ C do chờ ý kiến của Văn phòng, đề nghị xem xét lại mức xếp loại.',
      requestText: 'Đề nghị xếp loại Hoàn thành tốt nhiệm vụ.',
      reserveOpinion: true,
      reservedOpinion: null,
      attachmentObjectIds: [],
      handlerUnit: BAN_TO_CHUC,
      handler: P.ha,
      outcome: 'GIU_NGUYEN',
      conclusion: {
        outcome: 'GIU_NGUYEN',
        adjustments: [],
        responseDocRef: '05-CV/BTC',
        signRequestId: 'sr-0038',
        periodLocked: true,
      },
      reopenRequestId: null,
      timeline: [
        { at: at(-62, '09:30'), action: 'Gửi khiếu nại', actor: P.me },
        { at: at(-61, '08:45'), action: 'Tiếp nhận', actor: P.ha },
        { at: at(-50, '15:00'), action: 'Kết luận: giữ nguyên', actor: P.ha },
        {
          at: at(-2, '09:00'),
          action: 'Ký văn bản trả lời 05-CV/BTC',
          actor: { id: 'u-phan-van-quan', name: 'Phan Văn Quân' },
        },
      ],
      rowVersion: 7,
    },
    {
      id: 'kn-2026-0050',
      code: 'KN-2026-0050',
      complainant: P.me,
      subjectLine: 'Khiếu nại kết quả xếp loại Quý II/2026',
      kind: 'KHIEU_NAI',
      targetStage: 'THAM_DINH',
      status: null,
      filedAt: null,
      responseDeadline: null,
      workingDaysLeft: null,
      profileId: MY_PROFILE_Q2,
      period: Q2,
      targets: [
        { key: 'NV-THEN-CHOT-2.B', stage: 'THAM_DINH', label: 'Nhiệm vụ then chốt 2 · B = 0,50' },
      ],
      content: 'Báo cáo của nhiệm vụ then chốt 2 được Thường trực thông qua',
      requestText: '',
      reserveOpinion: false,
      reservedOpinion: null,
      attachmentObjectIds: [],
      handlerUnit: undefined,
      handler: undefined,
      outcome: null,
      conclusion: null,
      reopenRequestId: null,
      timeline: [],
      rowVersion: 1,
    },
  ];
}

export function responseFixtures(): ComplaintResponseRecord[] {
  const P = COMPLAINT_PEOPLE;
  return [
    {
      id: 'cr-0031-1',
      complaintId: 'kn-2026-0031',
      recordKind: 'YEU_CAU',
      responder: P.ha,
      party: { kind: 'NGUOI', id: P.tam.id },
      dueDate: workingDate(-27),
      content: 'Đề nghị giải trình căn cứ áp hệ số C = 0,85 cho nhiệm vụ then chốt 3.',
      respondedAt: at(-31, '14:05'),
    },
    {
      id: 'cr-0031-2',
      complaintId: 'kn-2026-0031',
      recordKind: 'YEU_CAU',
      responder: P.ha,
      party: { kind: 'DON_VI', id: DANG_UY_X.id },
      dueDate: workingDate(-27),
      content: 'Đề nghị giải trình lý do hồ sơ nhiệm vụ then chốt 3 được gửi sau hạn.',
      respondedAt: at(-31, '14:05'),
    },
    {
      id: 'cr-0031-3',
      complaintId: 'kn-2026-0031',
      recordKind: 'GIAI_TRINH',
      responder: P.tam,
      party: { kind: 'NGUOI', id: P.tam.id },
      requestId: 'cr-0031-1',
      content:
        'Hệ số C = 0,85 áp theo ngày nộp thực tế; khi nghiệm thu chưa nhận được công văn xác nhận nguyên nhân khách quan.',
      respondedAt: at(-29, '10:20'),
    },
    {
      id: 'cr-0031-4',
      complaintId: 'kn-2026-0031',
      recordKind: 'GIAI_TRINH',
      responder: { id: 'u-dang-uy-x', name: 'Đảng uỷ cơ sở X' },
      party: { kind: 'DON_VI', id: DANG_UY_X.id },
      requestId: 'cr-0031-2',
      content:
        'Đảng uỷ cơ sở X thừa nhận gửi hồ sơ muộn 6 ngày do sáp nhập chi bộ, kèm công văn 51-CV/ĐU.',
      attachmentObjectIds: ['f-0031-cv-51'],
      respondedAt: at(-28, '16:40'),
    },
    {
      id: 'cr-0031-5',
      complaintId: 'kn-2026-0031',
      recordKind: 'KET_LUAN',
      responder: P.ha,
      outcome: 'CHAP_NHAN_MOT_PHAN',
      adjustments: [
        {
          targetKey: 'NV-THEN-CHOT-3.C',
          oldValue: 0.85,
          newValue: 1,
          reason: 'Miễn trừ chậm do khách quan theo PL 3 mục 2.',
        },
      ],
      content:
        'Chấp nhận một phần nội dung khiếu nại; điều chỉnh hệ số C của nhiệm vụ then chốt 3 từ 0,85 thành 1,00.',
      responseDocRef: '08-CV/BTC',
      respondedAt: at(-12, '16:20'),
    },
    {
      id: 'cr-0040-1',
      complaintId: 'kn-2026-0040',
      recordKind: 'YEU_CAU',
      responder: P.me,
      party: { kind: 'NGUOI', id: P.tam.id },
      dueDate: workingDate(-5),
      content:
        'Đề nghị giải trình căn cứ đánh giá mức B = 0,50 cho nhiệm vụ then chốt 2 và việc áp hạn cũ cho nhiệm vụ thường xuyên 4.',
      respondedAt: at(-8, '09:20'),
    },
    {
      id: 'cr-0040-2',
      complaintId: 'kn-2026-0040',
      recordKind: 'GIAI_TRINH',
      responder: P.tam,
      party: { kind: 'NGUOI', id: P.tam.id },
      requestId: 'cr-0040-1',
      content:
        'Mức B = 0,50 do báo cáo thiếu phụ lục số liệu theo đề cương. Với nhiệm vụ thường xuyên 4, Thông báo 27-TB/ĐUK dời hạn sang ngày 28/3 nhưng chưa được cập nhật lên hệ thống nên hệ số C bị tính theo hạn cũ; đề nghị điều chỉnh C về 1,00.',
      respondedAt: at(-6, '15:10'),
    },
    {
      id: 'cr-0044-1',
      complaintId: 'kn-2026-0044',
      recordKind: 'YEU_CAU',
      responder: P.ha,
      party: { kind: 'NGUOI', id: P.me.id },
      dueDate: workingDate(2),
      content:
        'Đề nghị đồng chí, với vai trò người nghiệm thu, giải trình căn cứ yêu cầu bổ sung số liệu quý I cho báo cáo chuyên đề của nhiệm vụ then chốt 4.',
      respondedAt: at(-4, '10:30'),
    },
    {
      id: 'cr-0044-2',
      complaintId: 'kn-2026-0044',
      recordKind: 'YEU_CAU',
      responder: P.ha,
      party: { kind: 'DON_VI', id: BAN_TO_CHUC.id },
      dueDate: workingDate(2),
      content: 'Đề nghị Ban Tổ chức cung cấp đề cương đã duyệt của báo cáo chuyên đề.',
      respondedAt: at(-4, '10:30'),
    },
    {
      id: 'cr-0044-3',
      complaintId: 'kn-2026-0044',
      recordKind: 'GIAI_TRINH',
      responder: { id: 'u-van-phong-btc', name: 'Văn phòng Ban Tổ chức' },
      party: { kind: 'DON_VI', id: BAN_TO_CHUC.id },
      requestId: 'cr-0044-2',
      content: 'Gửi kèm đề cương đã duyệt ngày 02/4/2026; đề cương chỉ yêu cầu số liệu quý II.',
      attachmentObjectIds: ['f-0044-de-cuong-duyet'],
      respondedAt: at(-3, '11:00'),
    },
    {
      id: 'cr-0038-1',
      complaintId: 'kn-2026-0038',
      recordKind: 'KET_LUAN',
      responder: P.ha,
      outcome: 'GIU_NGUYEN',
      adjustments: [],
      content:
        'Giữ nguyên mức xếp loại Hoàn thành nhiệm vụ: việc chờ ý kiến của Văn phòng không thuộc trường hợp chậm do khách quan theo PL 3 mục 2 vì đã được báo trước hạn 10 ngày.',
      responseDocRef: '05-CV/BTC',
      respondedAt: at(-50, '15:00'),
    },
  ];
}

/** Tên hiển thị của bên được yêu cầu giải trình (người hoặc đơn vị) theo mã. */
export const PARTY_NAMES: Record<string, string> = {
  ...Object.fromEntries(Object.values(COMPLAINT_PEOPLE).map((p) => [p.id, p.name])),
  [BAN_TO_CHUC.id]: BAN_TO_CHUC.name,
  [DANG_UY_X.id]: DANG_UY_X.name,
};
