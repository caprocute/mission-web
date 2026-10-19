/**
 * Mô hình dữ liệu module okr (MOD-01, `/api/v1/okr`), tên trường theo TL-03 schema `okr`
 * (`okr.basis`, `okr.objective`, `okr.key_result`, `okr.okr_link`, `okr.kr_checkin`) đổi sang camelCase.
 */

/** Kết quả then chốt dùng để liên kết nhiệm vụ (`okr.key_result`). */
export interface KeyResultRef {
  id: string;
  code: string;
  title: string;
}

/** Trạng thái mục tiêu `okr.objective.status` (baseline, TL-02 QT-02 Hình 7.2); KR theo trạng thái mục tiêu. */
export type ObjectiveStatus = 'NHAP' | 'CHO_DUYET' | 'DA_DUYET' | 'DONG';

export const OBJECTIVE_STATUS_LABEL: Record<ObjectiveStatus, string> = {
  NHAP: 'Nháp',
  CHO_DUYET: 'Chờ duyệt',
  DA_DUYET: 'Đã duyệt',
  DONG: 'Đã đóng',
};

/** Mức tự tin `okr.kr_checkin.confidence` (QT-03 bước 5). */
export type Confidence = 'CAO' | 'TRUNG_BINH' | 'THAP';

export const CONFIDENCE_LABEL: Record<Confidence, string> = {
  CAO: 'Tự tin cao',
  TRUNG_BINH: 'Trung bình',
  THAP: 'Tự tin thấp',
};

/** Cờ rủi ro KR `okr.key_result.risk_flag` (QT-03 Hình 7.7). */
export type KrRiskFlag = 'BINH_THUONG' | 'THEO_DOI' | 'RUI_RO';

/** Trạng thái kỳ `config.period.status` (baseline). */
export type OkrPeriodStatus = 'MO' | 'DANG_KHOA' | 'DA_KHOA' | 'MO_LAI_HAU_KIEM';

/** Chủ thể sở hữu `okr.objective.owner_type`. */
export type OkrOwnerType = 'TAP_THE' | 'CA_NHAN';

/** Loại căn cứ `okr.basis.basis_kind`. */
export type BasisKind =
  'NGHI_QUYET' | 'KET_LUAN' | 'CHI_THI' | 'CHUONG_TRINH' | 'KE_HOACH' | 'CHI_TIEU_KTXH';

/** Kỳ đánh giá để chọn trên SCR-0201 (trường 1, `config.period`). */
export interface OkrPeriod {
  id: string;
  label: string;
  startDate: string;
  endDate: string;
  status: OkrPeriodStatus;
  /** Tuần hiện tại và tổng số tuần của kỳ, ví dụ "Tuần 4/13"; không có với kỳ chưa bắt đầu. */
  weekNo?: number;
  weekCount?: number;
}

/** Căn cứ (`okr.basis`), SCR-0201 trường 2. */
export interface OkrBasis {
  id: string;
  basisKind: BasisKind;
  docNumber: string | null;
  title: string;
}

/** Mục tiêu cấp trên (`okr.okr_link` kiểu phân rã), SCR-0201 trường 3. */
export interface ObjectiveRef {
  id: string;
  code: string;
  title: string;
  unitName: string;
  status: ObjectiveStatus;
}

/** Chương trình (Tầng 2) hoặc nhiệm vụ (Tầng 3) liên kết với KR (`okr.okr_link` tới schema `task`), trường 13. */
export interface KrLinkedWork {
  id: string;
  kind: 'PROGRAM' | 'TASK';
  code: string;
  title: string;
  /** Tiến độ 0–100 của nhiệm vụ; không cộng vào giá trị KR (BR-03-07). */
  percent: number;
}

/** Kết quả then chốt trên cây (`okr.key_result` cùng check-in mới nhất). */
export interface KeyResult {
  id: string;
  objectiveId: string;
  code: string;
  title: string;
  /** Đơn vị đo, ví dụ `%`, `hồ sơ`, `mốc`. */
  unit: string;
  baselineValue: number;
  targetValue: number;
  currentValue: number | null;
  /** Trọng số trong mục tiêu, 0–1. */
  weight: number;
  /** Tỷ lệ đạt do máy chủ tính, 0–1 (`okr.key_result.progress`). */
  progress: number;
  dueDate: string;
  verifierName: string;
  /** Nguồn số liệu, `null` khi nhập tay. */
  dataSource: string | null;
  /** Diễn giải giá trị, ví dụ "18/25 hồ sơ". */
  valueNote: string | null;
  confidence: Confidence | null;
  riskFlag: KrRiskFlag;
  lastCheckinAt: string | null;
  ownerName: string;
  /** Người đang xem là chủ sở hữu KR hoặc được giao check-in thay (máy chủ tính theo token). */
  viewerIsOwner: boolean;
  latestBlocker: string | null;
  linkedWork: KrLinkedWork[];
  /** Tổng số nhiệm vụ liên kết (có thể nhiều hơn `linkedWork`). */
  linkedTaskCount: number;
}

/** Mục tiêu trên cây (`okr.objective`); tầng 1 của đơn vị, tầng 2 của phòng, tầng 3 của cá nhân. */
export interface Objective {
  id: string;
  code: string;
  title: string;
  tier: 1 | 2 | 3;
  ownerType: OkrOwnerType;
  ownerUnitName: string;
  ownerPersonName: string | null;
  parentObjectiveId: string | null;
  status: ObjectiveStatus;
  /** Tiến độ 0–100 (`okr.objective.progress_pct`). */
  progressPct: number;
  approvedAt: string | null;
  versionNo: number;
  overLimitReason: string | null;
  keyResults: KeyResult[];
}

/** Liên kết ngang giữa đơn vị (`okr.okr_link`, `link_kind = NGANG`), SCR-0201 trường 12. */
export interface OkrPeerLink {
  id: string;
  /** Đầu mình, ví dụ "O2 · KR 2.1". */
  fromLabel: string;
  fromKeyResultId: string | null;
  peerUnitName: string;
  peerCode: string;
  peerTitle: string;
}

/** Dữ liệu cây mục tiêu của một đơn vị trong kỳ (`GET /api/v1/okr/objectives?view=tree`). */
export interface OkrTree {
  period: OkrPeriod;
  periods: OkrPeriod[];
  unit: { id: string; name: string };
  /** Đơn vị đang xem là đơn vị của ngữ cảnh làm việc của người dùng (máy chủ tính theo token). */
  viewerOwnUnit: boolean;
  bases: OkrBasis[];
  parent: ObjectiveRef | null;
  /** Số mục tiêu khuyến nghị tối đa mỗi kỳ (BR-02-01, tham số Policy Pack). */
  recommendedMax: number;
  /** Mọi mục tiêu của cây, phẳng, nối bằng `parentObjectiveId`. */
  objectives: Objective[];
  peerLinks: OkrPeerLink[];
}

/** Một lần check-in (`okr.kr_checkin`, chỉ ghi thêm). */
export interface KrCheckin {
  id: string;
  checkinAt: string;
  /** Nhãn tuần do máy chủ tính theo lịch kỳ, ví dụ "Tuần 4". */
  weekLabel: string;
  value: number | null;
  confidence: Confidence;
  blockerNote: string | null;
  highlights: string | null;
  channel: 'WEB' | 'MOBILE' | 'TICH_HOP';
  status: 'NHAP' | 'DA_GUI';
}

/** Chế độ hiển thị SCR-0201 (nút "Cây, Bảng, Bản đồ liên kết"). */
export type OkrTreeView = 'tree' | 'table' | 'map';
