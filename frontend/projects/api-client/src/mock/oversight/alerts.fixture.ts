import type {
  AlertAction,
  AnomalyAlert,
  AnomalyThresholdSet,
  EvidenceChainNode,
} from '../../lib/models/alert';
import { MOCK_ME } from '../mock-session';

/**
 * Dữ liệu giả lập SCR-1401 (WEB-044): tám cảnh báo còn mở phủ đủ tám dấu hiệu của QT-14 và ba cảnh báo đã
 * đóng cho thẻ phân bố 90 ngày. Kịch bản chính theo Hình 11.11 (dời sang Quý III/2026 cho khớp ngày giả lập): minh chứng trùng của
 * Đảng bộ cơ sở số 3, ba báo cáo tháng dùng cùng một tệp. Cảnh báo sửa dữ liệu sau khoá kỳ trỏ tới bản ghi sổ cái 1042 bị sửa
 * của WEB-043 và đang chờ cơ quan kiểm tra kết luận.
 */

const P = {
  hoang: { id: 'u-tran-minh-hoang', name: 'Trần Minh Hoàng' },
  binh: { id: 'u-nguyen-van-binh', name: 'Nguyễn Văn Bình' },
  lan: { id: 'u-pham-thi-lan', name: 'Phạm Thị Lan' },
  khanh: { id: 'u-vu-quoc-khanh', name: 'Vũ Quốc Khánh' },
  ha: { id: 'u-le-thu-ha', name: 'Lê Thu Hà' },
  tam: { id: 'u-ngo-thanh-tam', name: 'Ngô Thanh Tâm' },
  hung: { id: 'p-hung', name: 'Vũ Quang Hùng' },
  lanVp: { id: 'p-lan', name: 'Ngô Thị Lan' },
  an: { id: 'p-an', name: 'Nguyễn Văn An' },
  me: { id: MOCK_ME.id, name: MOCK_ME.name },
};

/** Đơn vị của người dùng thử nghiệm: cảnh báo về đơn vị này kích hoạt lỗi tách vai trò (BR-14-08). */
export const MY_UNIT = { id: 'u-btc', name: 'Ban Tổ chức' };

const Q = {
  q1: { id: 'p-2026q1', label: 'Quý I/2026', startDate: '2026-01-01', endDate: '2026-03-31' },
  q2: { id: 'p-2026q2', label: 'Quý II/2026', startDate: '2026-04-01', endDate: '2026-06-30' },
  q3: { id: 'p-2026q3', label: 'Quý III/2026', startDate: '2026-07-01', endDate: '2026-09-30' },
  q4: { id: 'p-2026q4', label: 'Quý IV/2026', startDate: '2026-10-01', endDate: '2026-12-31' },
};

const at = (daysAgo: number, time = '02:00') => {
  const d = new Date();
  d.setDate(d.getDate() - daysAgo);
  const [h, m] = time.split(':').map(Number);
  d.setHours(h, m, 0, 0);
  return d.toISOString();
};

const node = (
  kind: EvidenceChainNode['kind'],
  id: string,
  label: string,
  children?: EvidenceChainNode[],
): EvidenceChainNode => (children ? { kind, id, label, children } : { kind, id, label });

const auto = (id: string, note: string, daysAgo: number, active = true): AlertAction => ({
  id,
  action: 'TU_DONG',
  note,
  active,
  actedAt: at(daysAgo, '02:01'),
});

const SHA_BC = '5e88489a6d4c1b2f0e7a9c3d5b6f8e1a2c4d6f8b0a1c3e5f7a9b1d3f5e7a1f4';

const base = {
  sources: [],
  ledgerEntryIds: [],
  modelVersion: 'luat-v3',
  actions: [],
  rowVersion: 1,
};

export function alertFixtures(): AnomalyAlert[] {
  return [
    {
      ...base,
      id: 'cb-2026-0118',
      severity: 'CAO',
      signalCode: 'MINH_CHUNG_TRUNG',
      subject: { type: 'DON_VI', id: 'u-cs-03', name: 'Đảng bộ cơ sở số 3' },
      period: Q.q3,
      status: 'MOI',
      detectedAt: at(0, '02:00'),
      evidenceChain: [
        node('DIEM', 'sc-cs03-q4', 'Điểm nhóm nhiệm vụ thường xuyên của Đảng bộ cơ sở số 3', [
          node('CAU_PHAN', 'cp-a-3nv', 'Cấu phần A của 3 nhiệm vụ', [
            node('SAN_PHAM', 'sp-bc-t07', 'Báo cáo tháng 7', [
              node('MINH_CHUNG', 'mc-881', 'BC-thang.pdf, nộp ngày 05/07'),
            ]),
            node('SAN_PHAM', 'sp-bc-t08', 'Báo cáo tháng 8', [
              node('MINH_CHUNG', 'mc-902', 'BC-thang.pdf, nộp lại ngày 05/08'),
            ]),
            node('SAN_PHAM', 'sp-bc-t09', 'Báo cáo tháng 9', [
              node('MINH_CHUNG', 'mc-933', 'BC-thang.pdf, nộp lại ngày 05/09'),
            ]),
          ]),
        ]),
        node('BAM_TRUNG', SHA_BC, 'Ba minh chứng có cùng một tệp nội dung'),
        node('BAN_GHI_SO_CAI', 'le-17-1033', 'Nộp minh chứng báo cáo tháng 7'),
        node('BAN_GHI_SO_CAI', 'le-17-1035', 'Nộp minh chứng báo cáo tháng 8'),
      ],
      sources: [
        {
          submitter: P.hung,
          task: { id: 't-bc-t07', name: 'Báo cáo tháng 7' },
          submittedAt: '2026-07-05T09:12:00+07:00',
          sha256: SHA_BC,
        },
        {
          submitter: P.hung,
          task: { id: 't-bc-t08', name: 'Báo cáo tháng 8' },
          submittedAt: '2026-08-05T08:40:00+07:00',
          sha256: SHA_BC,
        },
        {
          submitter: P.lanVp,
          task: { id: 't-bc-t09', name: 'Báo cáo tháng 9' },
          submittedAt: '2026-09-05T10:05:00+07:00',
          sha256: SHA_BC,
        },
      ],
      ledgerEntryIds: ['le-17-1033', 'le-17-1035'],
      actions: [
        auto('aa-118-1', 'Tạm chưa tính điểm 3 minh chứng liên quan cho tới khi có xác nhận.', 0),
      ],
    },
    {
      ...base,
      id: 'cb-2026-0117',
      severity: 'CAO',
      signalCode: 'SUA_SAU_KHOA',
      subject: { type: 'CA_NHAN', id: P.hoang.id, name: P.hoang.name },
      period: Q.q2,
      status: 'DA_GIAO_KIEM_TRA',
      detectedAt: at(3, '02:00'),
      evidenceChain: [
        node('DIEM', 'ap-2026q2-tran-minh-hoang', 'Điểm xếp loại Quý II/2026 của Trần Minh Hoàng', [
          node('NHIEM_VU', 't-tc-3', 'Nhiệm vụ then chốt 3', [
            node('SAN_PHAM', 'sp-tc-3', 'Kết quả nghiệm thu: hệ số C từ 0,85 thành 0,95', [
              node('BAN_GHI_SO_CAI', 'le-17-1042', 'Nghiệm thu sản phẩm sau khi khoá kỳ'),
            ]),
          ]),
        ]),
      ],
      ledgerEntryIds: ['le-17-1042'],
      actions: [
        auto('aa-117-1', 'Đã tạo cảnh báo mức Cao và gửi cơ quan tổ chức.', 3),
        {
          id: 'aa-117-2',
          action: 'GIAO_KIEM_TRA',
          actor: P.ha,
          assignedTo: P.me,
          note: 'Thay đổi nghiệm thu sau khoá kỳ không có yêu cầu mở lại kỳ đi kèm; đề nghị Uỷ ban Kiểm tra xác minh.',
          actedAt: at(2, '10:30'),
        },
      ],
      rowVersion: 3,
    },
    {
      ...base,
      id: 'cb-2026-0116',
      severity: 'VUA',
      signalCode: 'CAO_BANG',
      subject: { type: 'DON_VI', id: 'u-cs-01', name: 'Đảng bộ cơ sở số 1' },
      period: Q.q3,
      status: 'DANG_XEM',
      detectedAt: at(5, '02:00'),
      evidenceChain: [
        node('DIEM', 'xl-cs01-q3', '94% cán bộ cùng mức Hoàn thành tốt nhiệm vụ (ngưỡng 90%)', [
          node('KPI', 'kpi-ty-le', 'Độ lệch chuẩn điểm của đơn vị 1,2 (thấp bất thường)'),
        ]),
      ],
      actions: [auto('aa-116-1', 'Đã hiện cảnh báo khi ký lô xếp loại của đơn vị.', 5)],
      rowVersion: 2,
    },
    {
      ...base,
      id: 'cb-2026-0115',
      severity: 'VUA',
      signalCode: 'TU_CHAM_CAO',
      subject: { type: 'CA_NHAN', id: P.binh.id, name: P.binh.name },
      period: Q.q3,
      status: 'MOI',
      detectedAt: at(1, '02:00'),
      evidenceChain: [
        node(
          'DIEM',
          'ap-2026q3-nguyen-van-binh',
          'Tự đề xuất mức cao hơn mức thẩm định 3 quý liên tiếp',
          [
            node('CAU_PHAN', 'q1', 'Quý I/2026: tự đề xuất Xuất sắc, thẩm định Tốt'),
            node('CAU_PHAN', 'q2', 'Quý II/2026: tự đề xuất Xuất sắc, thẩm định Tốt'),
            node('CAU_PHAN', 'q3', 'Quý III/2026: tự đề xuất Tốt, thẩm định Hoàn thành'),
          ],
        ),
      ],
      actions: [auto('aa-115-1', 'Đã gợi ý trên hàng đợi thẩm định.', 1)],
    },
    {
      ...base,
      id: 'cb-2026-0114',
      severity: 'THAP',
      signalCode: 'MUC_TIEU_DE',
      subject: { type: 'CA_NHAN', id: P.lan.id, name: P.lan.name },
      period: Q.q4,
      status: 'MOI',
      detectedAt: at(2, '02:00'),
      evidenceChain: [
        node('KPI', 'kr-lan-2', 'Kết quả then chốt "Số bài viết tuyên truyền" đạt 100% sau 9 ngày'),
      ],
      actions: [auto('aa-114-1', 'Đã gắn nhãn trên cây mục tiêu cho người duyệt.', 2)],
    },
    {
      ...base,
      id: 'cb-2026-0113',
      severity: 'VUA',
      signalCode: 'CAP_NHAT_DON',
      subject: { type: 'DON_VI', id: MY_UNIT.id, name: MY_UNIT.name },
      period: Q.q3,
      status: 'DANG_XEM',
      detectedAt: at(8, '02:00'),
      evidenceChain: [
        node(
          'NHIEM_VU',
          'u-btc-q3',
          '68% lượt cập nhật tiến độ dồn vào 3 ngày cuối kỳ (ngưỡng 60%)',
        ),
      ],
      actions: [auto('aa-113-1', 'Đã báo quản lý trực tiếp.', 8)],
      rowVersion: 2,
    },
    {
      ...base,
      id: 'cb-2026-0119',
      severity: 'THAP',
      signalCode: 'CHE_NHO',
      subject: { type: 'CA_NHAN', id: P.khanh.id, name: P.khanh.name },
      period: Q.q4,
      status: 'MOI',
      detectedAt: at(0, '02:00'),
      evidenceChain: [
        node(
          'NHIEM_VU',
          'kh-khanh-q4',
          'Số nhiệm vụ tăng 60% so với kỳ trước, tổng khối lượng không đổi',
          [
            node(
              'NHIEM_VU',
              't-k-1',
              '4 nhiệm vụ cùng sản phẩm "Báo cáo chuyên đề" và cùng hạn 30/11',
            ),
          ],
        ),
      ],
      actions: [auto('aa-119-1', 'Đã báo người duyệt kế hoạch.', 0)],
    },
    {
      ...base,
      id: 'cb-2026-0120',
      severity: 'VUA',
      signalCode: 'MIEN_TRU_LAP',
      subject: { type: 'NGUOI_DUYET', id: P.ha.id, name: P.ha.name },
      period: Q.q3,
      status: 'MOI',
      detectedAt: at(1, '02:00'),
      evidenceChain: [
        node('NHIEM_VU', 'mt-ha', 'Áp miễn trừ chậm do khách quan 4 lần trong 2 quý (ngưỡng 3)'),
      ],
      actions: [auto('aa-120-1', 'Đã báo cơ quan tổ chức.', 1)],
    },
    // đã đóng, cho thẻ phân bố 90 ngày
    {
      ...base,
      id: 'cb-2026-0112',
      severity: 'CAO',
      signalCode: 'MINH_CHUNG_TRUNG',
      subject: { type: 'CA_NHAN', id: P.khanh.id, name: P.khanh.name },
      period: Q.q1,
      status: 'DONG',
      detectedAt: at(40, '02:00'),
      evidenceChain: [node('BAM_TRUNG', 'h-0112', 'Hai minh chứng có cùng một tệp nội dung')],
      actions: [
        auto('aa-112-1', 'Tạm chưa tính điểm 2 minh chứng liên quan.', 40, false),
        {
          id: 'aa-112-2',
          action: 'GIAO_KIEM_TRA',
          actor: P.ha,
          assignedTo: P.tam,
          note: 'Hai nhiệm vụ khác nhau dùng chung một minh chứng; đề nghị kiểm tra.',
          actedAt: at(38, '09:00'),
        },
        {
          id: 'aa-112-3',
          action: 'KET_LUAN',
          actor: P.tam,
          verdict: 'CO_VI_PHAM',
          note: 'Minh chứng thứ hai không phải sản phẩm của nhiệm vụ; cần nghiệm thu lại mức chất lượng.',
          actedAt: at(20, '15:00'),
        },
      ],
      rowVersion: 5,
    },
    {
      ...base,
      id: 'cb-2026-0105',
      severity: 'VUA',
      signalCode: 'CAP_NHAT_DON',
      subject: { type: 'DON_VI', id: 'u-cs-02', name: 'Đảng bộ cơ sở số 2' },
      period: Q.q3,
      status: 'DONG',
      detectedAt: at(30, '02:00'),
      evidenceChain: [node('NHIEM_VU', 'cs02-q3', '62% lượt cập nhật dồn vào cuối kỳ')],
      actions: [
        {
          id: 'aa-105-1',
          action: 'XAC_NHAN_HOP_LE',
          actor: P.ha,
          note: 'Đơn vị tổ chức đại hội trong tháng 9 nên cập nhật dồn là có lý do; đã nhắc cập nhật đều.',
          actedAt: at(28, '14:00'),
        },
      ],
      rowVersion: 3,
    },
    {
      ...base,
      id: 'cb-2026-0101',
      severity: 'THAP',
      signalCode: 'MUC_TIEU_DE',
      subject: { type: 'CA_NHAN', id: P.an.id, name: P.an.name },
      period: Q.q3,
      status: 'TU_DONG_DONG',
      detectedAt: at(60, '02:00'),
      evidenceChain: [node('KPI', 'kr-an', 'Chỉ tiêu đã được điều chỉnh, hết dấu hiệu')],
      actions: [],
      rowVersion: 2,
    },
  ];
}

export function thresholdFixture(): AnomalyThresholdSet {
  return {
    version: 3,
    reason: 'Giảm ngưỡng dồn cập nhật cuối kỳ từ 70% xuống 60% theo kết quả quý III.',
    updatedAt: at(14, '14:20'),
    updatedBy: { id: 'p-chau', name: 'Lê Minh Châu' },
    signals: [
      { signalCode: 'TU_CHAM_CAO', enabled: true, params: { consecutiveQuarters: 3, scoreGap: 5 } },
      { signalCode: 'CAO_BANG', enabled: true, params: { sameLevelPercent: 90 } },
      {
        signalCode: 'MUC_TIEU_DE',
        enabled: true,
        params: { earlyWeeks: 2, belowPreviousPercent: 20 },
      },
      { signalCode: 'MINH_CHUNG_TRUNG', enabled: true, params: { ocrSimilarityPercent: 95 } },
      { signalCode: 'CAP_NHAT_DON', enabled: true, params: { lastDaysPercent: 60, lastDays: 3 } },
      { signalCode: 'SUA_SAU_KHOA', enabled: true, params: {} },
      { signalCode: 'CHE_NHO', enabled: true, params: { taskIncreasePercent: 50 } },
      { signalCode: 'MIEN_TRU_LAP', enabled: true, params: { exemptionsIn2Quarters: 3 } },
    ],
    rowVersion: 3,
  };
}
