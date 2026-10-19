import type { components as ConfigComponents } from '../generated/config';
import type { components } from '../generated/oversight';

/**
 * Cảnh báo bất thường (`/api/v1/oversight/alerts`, QT-14, SCR-1401) và ngưỡng phát hiện
 * (`/api/v1/config/anomaly-thresholds`): bí danh của lược đồ trong `oversight.yaml`, `config.yaml` (WEB-044),
 * tên trường theo `oversight.anomaly_alert`, `oversight.alert_action` của TL-03. Chuỗi bằng chứng chỉ chứa
 * mã (BR-14-06); giao diện hiện nhãn nghiệp vụ, không hiện mã (YC-PT-10).
 */
type S = components['schemas'];
type C = ConfigComponents['schemas'];

export type AnomalySignalCode = S['AnomalySignalCode'];
export type AnomalySeverity = S['AnomalySeverity'];
export type AnomalyAlertStatus = S['AnomalyAlertStatus'];
export type AlertActionType = S['AlertActionType'];
export type AlertActionInput = S['AlertActionInput'];
export type AlertVerdict = NonNullable<AlertActionInput['verdict']>;
export type AlertSubject = S['AlertSubject'];
export type AnomalyAlertSummary = S['AnomalyAlertSummary'];
export type AnomalyAlertCounts = S['AnomalyAlertCounts'];
export type EvidenceChainNode = S['EvidenceChainNode'];
export type AlertSourceRow = S['AlertSourceRow'];
export type AlertAction = S['AlertAction'];
export type AnomalyAlert = S['AnomalyAlert'];
export type AnomalySignalThreshold = C['AnomalySignalThreshold'];
export type AnomalyThresholdSet = C['AnomalyThresholdSet'];
export type AnomalyThresholdUpdate = C['AnomalyThresholdUpdate'];

export interface AlertFilter {
  periodId?: string;
  unitId?: string;
  signal?: AnomalySignalCode[];
  severity?: AnomalySeverity[];
  status?: AnomalyAlertStatus[];
}

export interface AlertPage {
  items: AnomalyAlertSummary[];
  nextCursor: string | null;
  counts?: AnomalyAlertCounts;
}

/** Tám dấu hiệu theo thứ tự của bảng QT-14 (slide 31). */
export const ANOMALY_SIGNALS: readonly AnomalySignalCode[] = [
  'TU_CHAM_CAO',
  'CAO_BANG',
  'MUC_TIEU_DE',
  'MINH_CHUNG_TRUNG',
  'CAP_NHAT_DON',
  'SUA_SAU_KHOA',
  'CHE_NHO',
  'MIEN_TRU_LAP',
];

export const ANOMALY_SIGNAL_LABEL: Record<AnomalySignalCode, string> = {
  TU_CHAM_CAO: 'Tự chấm cao hơn thẩm định có hệ thống',
  CAO_BANG: 'Xếp loại đồng loạt, cào bằng',
  MUC_TIEU_DE: 'Mục tiêu đặt quá dễ',
  MINH_CHUNG_TRUNG: 'Minh chứng trùng hoặc dùng lại',
  CAP_NHAT_DON: 'Cập nhật dồn vào cuối kỳ',
  SUA_SAU_KHOA: 'Sửa dữ liệu khi đã biết kết quả',
  CHE_NHO: 'Chẻ nhỏ công việc',
  MIEN_TRU_LAP: 'Miễn trừ "khách quan" lặp lại',
};

/** Hành động đề xuất cho người xử lý (cột cuối bảng tám dấu hiệu của QT-14). */
export const ANOMALY_SIGNAL_SUGGESTION: Record<AnomalySignalCode, string> = {
  TU_CHAM_CAO: 'Nhắc trao đổi với cán bộ; ghi nhận vào nhận xét.',
  CAO_BANG: 'Yêu cầu phiên hiệu chỉnh; xem lại tiêu chí.',
  MUC_TIEU_DE: 'Xem lại chỉ tiêu ở kỳ sau; ghi nhận khi thẩm định.',
  MINH_CHUNG_TRUNG: 'Xác nhận hợp lệ hoặc giao kiểm tra.',
  CAP_NHAT_DON: 'Nhắc cập nhật đều; ghi nhận khi đánh giá.',
  SUA_SAU_KHOA: 'Giao kiểm tra; đối chiếu sổ cái.',
  CHE_NHO: 'Xem lại kế hoạch; gộp nhiệm vụ ở kỳ sau.',
  MIEN_TRU_LAP: 'Xem căn cứ miễn trừ; giao kiểm tra nếu cần.',
};

export const ANOMALY_SEVERITY_LABEL: Record<AnomalySeverity, string> = {
  CAO: 'Cao',
  VUA: 'Vừa',
  THAP: 'Thấp',
};

export const ANOMALY_ALERT_STATUS_LABEL: Record<AnomalyAlertStatus, string> = {
  MOI: 'Mới',
  DANG_XEM: 'Đang xem xét',
  HOP_LE: 'Đã xác nhận hợp lệ',
  DA_GIAO_KIEM_TRA: 'Đã giao kiểm tra',
  CO_VI_PHAM: 'Có vi phạm',
  KHONG_VI_PHAM: 'Không vi phạm',
  TU_DONG_DONG: 'Tự đóng do hết dấu hiệu',
  DONG: 'Đã đóng',
};

/** Trạng thái còn cần xử lý (lọc mặc định "Đang mở"). */
export const ANOMALY_OPEN_STATUSES: readonly AnomalyAlertStatus[] = [
  'MOI',
  'DANG_XEM',
  'DA_GIAO_KIEM_TRA',
];

export const ALERT_ACTION_LABEL: Record<AlertActionType, string> = {
  XAC_NHAN_HOP_LE: 'Xác nhận hợp lệ',
  GIAO_KIEM_TRA: 'Giao kiểm tra',
  KET_LUAN: 'Kết luận kiểm tra',
  GHI_CHU: 'Ghi chú',
  DONG: 'Đóng cảnh báo',
  TU_DONG: 'Hệ thống tự động',
};

export const ALERT_VERDICT_LABEL: Record<AlertVerdict, string> = {
  CO_VI_PHAM: 'Có vi phạm',
  KHONG_VI_PHAM: 'Không vi phạm',
};

export const ALERT_SUBJECT_TYPE_LABEL: Record<AlertSubject['type'], string> = {
  CA_NHAN: 'Cá nhân',
  DON_VI: 'Đơn vị',
  NGUOI_DUYET: 'Người duyệt',
};

/** Loại nút của chuỗi truy vết (SCR-1401 trường 4). */
export const EVIDENCE_CHAIN_KIND_LABEL: Record<EvidenceChainNode['kind'], string> = {
  DIEM: 'Điểm',
  CAU_PHAN: 'Cấu phần',
  KPI: 'Chỉ tiêu KPI',
  NHIEM_VU: 'Nhiệm vụ',
  SAN_PHAM: 'Sản phẩm',
  MINH_CHUNG: 'Minh chứng',
  BAN_GHI_SO_CAI: 'Bản ghi sổ cái',
  BAM_TRUNG: 'Tệp trùng nội dung',
};

/** Ghi chú xử lý 20–2.000 ký tự (SCR-1401 trường 7, BR-14-05). */
export const ALERT_NOTE_LIMITS = { min: 20, max: 2000 } as const;

/** Tên, đơn vị của từng tham số ngưỡng (ví dụ của `config.yaml`; danh sách là suy luận). */
export const THRESHOLD_PARAM_LABEL: Record<string, { label: string; unit: string }> = {
  consecutiveQuarters: { label: 'Số quý liên tiếp tự chấm cao hơn', unit: 'quý' },
  scoreGap: { label: 'Chênh lệch điểm tự tính và sau thẩm định', unit: 'điểm' },
  sameLevelPercent: { label: 'Tỷ lệ cán bộ cùng một mức', unit: '%' },
  earlyWeeks: { label: 'Đạt 100% kết quả then chốt trong', unit: 'tuần đầu kỳ' },
  belowPreviousPercent: { label: 'Chỉ tiêu thấp hơn kết quả kỳ trước', unit: '%' },
  ocrSimilarityPercent: { label: 'Độ tương đồng nội dung', unit: '%' },
  lastDaysPercent: { label: 'Tỷ lệ lượt cập nhật dồn cuối kỳ', unit: '%' },
  lastDays: { label: 'Số ngày cuối kỳ', unit: 'ngày' },
  taskIncreasePercent: { label: 'Số nhiệm vụ tăng so với kỳ trước', unit: '%' },
  exemptionsIn2Quarters: { label: 'Số lần miễn trừ trong 2 quý', unit: 'lần' },
};
