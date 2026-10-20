import { ReopenImpact, ReopenRequest } from '../../lib/models/reopen';
import { MOCK_ME } from '../mock-session';

/**
 * Dữ liệu mẫu QT-13 (WEB-043), dựng theo ví dụ MLK-2027-007 của TL-02 Hình 11.7 nhưng đặt vào Quý II/2026
 * (kỳ `DA_KHOA` của giả lập kỳ đánh giá) để khớp thời gian chạy: một yêu cầu chờ người duyệt thứ hai,
 * một chờ người duyệt thứ nhất, một do chính người dùng thử nghiệm tạo (để thử huỷ và lỗi bốn mắt), một
 * đã mở lại kỳ và một bị từ chối.
 */
export const REOPEN_PEOPLE = {
  ha: { id: 'u-le-thu-ha', name: 'Lê Thu Hà' },
  tam: { id: 'u-ngo-thanh-tam', name: 'Ngô Thanh Tâm' },
  quan: { id: 'u-phan-van-quan', name: 'Phan Văn Quân' },
  lam: { id: 'u-do-van-lam', name: 'Đỗ Văn Lâm' },
  me: { id: MOCK_ME.id, name: MOCK_ME.name },
} as const;

export const PROFILE_HOANG = 'ap-2026q2-tran-minh-hoang';
export const PROFILE_NAMES: Record<string, string> = {
  [PROFILE_HOANG]: 'Trần Minh Hoàng · Trưởng phòng Tổng hợp',
  'ap-2026q2-nguyen-thi-mai': 'Nguyễn Thị Mai · Phó Trưởng phòng',
  'ap-2026q1-vu-quoc-khanh': 'Vũ Quốc Khánh · Chuyên viên',
};

const Q2 = { id: 'p-2026q2', label: 'Quý II/2026', startDate: '2026-04-01', endDate: '2026-06-30' };
const Q1 = { id: 'p-2026q1', label: 'Quý I/2026', startDate: '2026-01-01', endDate: '2026-03-31' };

const day = (offset: number, hhmm = '08:00') => {
  const d = new Date();
  d.setDate(d.getDate() + offset);
  const [h, m] = hhmm.split(':').map(Number);
  d.setHours(h, m, 0, 0);
  return d.toISOString();
};

export function reopenFixtures(): ReopenRequest[] {
  const P = REOPEN_PEOPLE;
  return [
    {
      id: 'rr-2026-007',
      code: 'MLK-2026-007',
      period: Q2,
      periodStatus: 'DA_KHOA',
      auditKind: 'PHAN_ANH',
      scope: 'HO_SO',
      scopeIds: [PROFILE_HOANG],
      scopeDetail: {
        items: [
          {
            profileId: PROFILE_HOANG,
            table: 'product.acceptance',
            recordId: 'acc-2026q2-then-chot-3',
            fields: ['c_factor'],
          },
        ],
      },
      openUntil: day(4, '17:00'),
      reason:
        'Thi hành kết luận khiếu nại KN-2026-0031 chấp nhận một phần: điều chỉnh hệ số tiến độ (C) của nhiệm vụ then chốt 3 do chậm vì nguyên nhân khách quan (chờ văn bản của cơ quan cấp trên).',
      basis: { kind: 'KHIEU_NAI', id: 'kn-2026-0031', code: 'KN-2026-0031' },
      requestedBy: P.ha,
      approvalsRequired: 2,
      status: 'CHO_DUYET_2',
      approvers: [
        {
          round: 1,
          role: 'decider',
          assignee: P.tam,
          approval: {
            id: 'ra-2026-007-1',
            approver: P.tam,
            approverRole: 'decider',
            decision: 'DONG_Y',
            mfaAcr: 'aal2',
            signRequestId: 'sr-2026-007-1',
            comment:
              'Đồng ý mở lại trong phạm vi một hồ sơ, chỉ sửa hệ số tiến độ (C) của nhiệm vụ then chốt 3.',
            decidedAt: day(-1, '10:14'),
          },
        },
        { round: 2, role: 'second_approver', assignee: P.quan, approval: null },
      ],
      executedAt: null,
      relockedAt: null,
      createdAt: day(-2, '15:40'),
      rowVersion: 3,
    },
    {
      id: 'rr-2026-008',
      code: 'MLK-2026-008',
      period: Q1,
      periodStatus: 'DA_KHOA',
      auditKind: 'DOT_XUAT',
      scope: 'HO_SO',
      scopeIds: ['ap-2026q1-vu-quoc-khanh'],
      scopeDetail: {
        items: [
          {
            profileId: 'ap-2026q1-vu-quoc-khanh',
            table: 'product.acceptance',
            fields: ['quality_level'],
          },
        ],
      },
      openUntil: day(8, '17:00'),
      reason:
        'Cảnh báo CB-2026-0112 kết luận có vi phạm: cùng một tệp minh chứng được nghiệm thu cho hai nhiệm vụ khác nhau, cần nghiệm thu lại mức chất lượng của nhiệm vụ thứ hai.',
      basis: { kind: 'CANH_BAO', id: 'cb-2026-0112', code: 'CB-2026-0112' },
      requestedBy: P.lam,
      approvalsRequired: 2,
      status: 'CHO_DUYET_1',
      approvers: [
        { round: 1, role: 'decider', assignee: P.tam, approval: null },
        { round: 2, role: 'second_approver', assignee: P.quan, approval: null },
      ],
      executedAt: null,
      relockedAt: null,
      createdAt: day(-1, '09:05'),
      rowVersion: 1,
    },
    {
      id: 'rr-2026-009',
      code: 'MLK-2026-009',
      period: Q2,
      periodStatus: 'DA_KHOA',
      auditKind: 'KE_HOACH',
      scope: 'HO_SO',
      scopeIds: ['ap-2026q2-nguyen-thi-mai'],
      scopeDetail: {
        items: [
          {
            profileId: 'ap-2026q2-nguyen-thi-mai',
            table: 'appraisal.self_assessment',
            fields: ['structured_data'],
          },
        ],
      },
      openUntil: day(5, '17:00'),
      reason:
        'Đợt hậu kiểm theo kế hoạch HK-2026-03 phát hiện phiếu tự đánh giá ghi nhầm mức của kỳ trước; cần mở lại để người được đánh giá chọn lại mức đúng.',
      basis: { kind: 'HAU_KIEM', id: 'hk-2026-03', code: 'HK-2026-03' },
      requestedBy: P.me,
      approvalsRequired: 2,
      status: 'CHO_DUYET_1',
      approvers: [
        { round: 1, role: 'decider', assignee: P.tam, approval: null },
        { round: 2, role: 'second_approver', assignee: P.quan, approval: null },
      ],
      executedAt: null,
      relockedAt: null,
      createdAt: day(0, '07:30'),
      rowVersion: 1,
    },
    {
      id: 'rr-2026-004',
      code: 'MLK-2026-004',
      period: Q2,
      periodStatus: 'DA_KHOA',
      auditKind: 'PHAN_ANH',
      scope: 'HO_SO',
      scopeIds: ['ap-2026q2-nguyen-thi-mai'],
      scopeDetail: {
        items: [
          {
            profileId: 'ap-2026q2-nguyen-thi-mai',
            table: 'product.acceptance',
            fields: ['c_factor'],
          },
        ],
      },
      openUntil: day(-40, '17:00'),
      reason:
        'Thi hành kết luận khiếu nại KN-2026-0017: nhiệm vụ thường xuyên 2 bị tính chậm hạn do lịch làm việc của đơn vị thiếu ngày nghỉ lễ, cần nghiệm thu lại hệ số tiến độ (C).',
      basis: { kind: 'KHIEU_NAI', id: 'kn-2026-0017', code: 'KN-2026-0017' },
      requestedBy: P.ha,
      approvalsRequired: 2,
      status: 'DA_THUC_HIEN',
      approvers: [
        {
          round: 1,
          role: 'decider',
          assignee: P.tam,
          approval: {
            approver: P.tam,
            approverRole: 'decider',
            decision: 'DONG_Y',
            mfaAcr: 'aal2',
            comment: 'Đồng ý, phạm vi một nhiệm vụ.',
            decidedAt: day(-45, '09:20'),
          },
        },
        {
          round: 2,
          role: 'second_approver',
          assignee: P.quan,
          approval: {
            approver: P.quan,
            approverRole: 'second_approver',
            decision: 'DONG_Y',
            mfaAcr: 'aal2',
            comment: 'Đồng ý mở lại ba ngày làm việc.',
            decidedAt: day(-44, '14:02'),
          },
        },
      ],
      executedAt: day(-44, '14:03'),
      relockedAt: day(-41, '16:30'),
      createdAt: day(-46, '10:00'),
      rowVersion: 6,
    },
    {
      id: 'rr-2026-002',
      code: 'MLK-2026-002',
      period: Q1,
      periodStatus: 'DA_KHOA',
      auditKind: 'DOT_XUAT',
      scope: 'DON_VI',
      scopeIds: ['ou-van-phong'],
      scopeDetail: {
        items: [{ unitId: 'ou-van-phong', table: 'product.acceptance', fields: ['c_factor'] }],
      },
      openUntil: day(-60, '17:00'),
      reason:
        'Đề nghị mở lại toàn bộ kết quả nghiệm thu của Văn phòng Đảng uỷ để rà soát hệ số tiến độ (C) sau phản ánh của một số đảng viên về cách tính hạn.',
      basis: { kind: 'QUYET_DINH', code: 'QĐ 08-QĐ/UBKT' },
      requestedBy: P.lam,
      approvalsRequired: 2,
      status: 'TU_CHOI',
      approvers: [
        {
          round: 1,
          role: 'decider',
          assignee: P.tam,
          approval: {
            approver: P.tam,
            approverRole: 'decider',
            decision: 'TU_CHOI',
            mfaAcr: 'aal2',
            comment:
              'Phạm vi cả đơn vị là quá rộng so với phản ánh; đề nghị lập yêu cầu theo từng hồ sơ có căn cứ cụ thể.',
            decidedAt: day(-62, '11:00'),
          },
        },
        { round: 2, role: 'second_approver', assignee: P.quan, approval: null },
      ],
      executedAt: null,
      relockedAt: null,
      createdAt: day(-63, '08:30'),
      rowVersion: 2,
    },
  ];
}

/** Ảnh hưởng dự kiến theo TL-02 Hình 11.7 (vùng 3). */
export const IMPACT_007: ReopenImpact = {
  policyVersion: 'khối Đảng, phiên bản 2.0',
  rows: [
    { profileId: PROFILE_HOANG, metric: 'Điểm', before: '91,2', after: '92,1' },
    {
      profileId: PROFILE_HOANG,
      metric: 'Mức xếp loại',
      before: 'Hoàn thành tốt nhiệm vụ',
      after: 'Hoàn thành tốt nhiệm vụ',
    },
  ],
  formsToRegenerate: 2,
};
