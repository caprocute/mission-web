import { CursorPage } from '../paging';

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
  /** Giá trị đầu, chỉ tiêu: có thể trống ở mục tiêu `NHAP` chưa soạn xong. */
  baselineValue: number | null;
  targetValue: number | null;
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

/** Tần suất check-in `okr.key_result.checkin_frequency` (SCR-0202 trường 15). */
export type CheckinFrequency = 'TUAN' | 'HAI_TUAN' | 'THANG';

export const CHECKIN_FREQUENCY_LABEL: Record<CheckinFrequency, string> = {
  TUAN: 'Tuần',
  HAI_TUAN: 'Hai tuần',
  THANG: 'Tháng',
};

/** Nguồn nội dung `okr.key_result.content_source` (BR-02-11). */
export type ContentSource = 'HUMAN' | 'AI_ACCEPTED' | 'AI_EDITED';

/** Một KR trong bản nháp SCR-0202 (`okr.key_result`); trọng số 0–1 như TL-03. */
export interface KeyResultDraft {
  /** Không có với KR mới thêm, máy chủ cấp khi lưu. */
  id?: string;
  code: string;
  title: string;
  unit: string;
  baselineValue: number | null;
  targetValue: number | null;
  weight: number;
  dueDate: string | null;
  verifierId: string | null;
  checkinFrequency: CheckinFrequency;
  contentSource: ContentSource;
}

/** Bản nháp mục tiêu để soạn trên SCR-0202 (`okr.objective` cùng KR, căn cứ, liên kết cấp trên). */
export interface ObjectiveDraft {
  id?: string;
  periodId: string;
  ownerType: OkrOwnerType;
  basisIds: string[];
  parentObjectiveId: string | null;
  title: string;
  overLimitReason: string | null;
  keyResults: KeyResultDraft[];
}

/** Mục tiêu đã lưu: bản nháp kèm thông tin máy chủ cấp. */
export interface ObjectiveDetail extends ObjectiveDraft {
  id: string;
  code: string;
  status: ObjectiveStatus;
  versionNo: number;
  /** Giá trị ETag hiện tại, gửi lại trong `If-Match` khi cập nhật. */
  rowVersion: number;
  ownerUnitName: string;
  authorName: string;
  updatedAt: string;
  /** Lần lưu thứ mấy của bản nháp (hiện "bản nháp 3"). */
  draftNo: number;
}

/** Mức của một quy tắc soát OKR: chặn trình duyệt hay chỉ cảnh báo. */
export type OkrRuleSeverity = 'CHAN' | 'CANH_BAO';

/**
 * Quy tắc soát OKR viết bằng CEL trong Policy Pack (QT-02 bước 4): chạy trên trình duyệt để phản
 * hồi tức thì và chạy lại ở máy chủ khi trình. `scope` cho biết biểu thức chạy trên `objective`
 * hay trên từng `kr`. `message` có thể chứa chỗ trống `{tên}` lấy từ biến cùng tên của ngữ cảnh.
 */
export interface OkrRule {
  id: string;
  /** Mã quy tắc nghiệp vụ, ví dụ `BR-02-05`. */
  br: string;
  scope: 'objective' | 'kr';
  severity: OkrRuleSeverity;
  when: string;
  assert: string;
  message: string;
  /** Nhãn ngắn cho danh sách kiểm tra trước khi trình khi đạt, ví dụ "Tổng trọng số KR = 100%". */
  passLabel?: string;
  serverOnly?: boolean;
}

/** Tham số soát OKR của Policy Pack đang áp dụng cho tenant. */
export interface OkrPolicy {
  version: string;
  recommendedMax: number;
  /** Từ mơ hồ (BR-02-05), ví dụ "tăng cường", "đẩy mạnh". */
  vagueWords: string[];
  /** Động từ mở đầu KR giống nhiệm vụ (BR-02-06), ví dụ "tổ chức", "ban hành". */
  taskVerbs: string[];
  units: string[];
  rules: OkrRule[];
}

/** Mục tiêu cấp trên có thể chọn (SCR-0202 trường 2). */
export interface ParentObjectiveOption extends ObjectiveRef {
  periodId: string;
}

/** Người xác nhận KR có thể chọn (trường 14). */
export interface VerifierOption {
  id: string;
  name: string;
  title: string;
}

/** Ngữ cảnh soạn mục tiêu của đơn vị trong kỳ (`GET /api/v1/okr/compose-context`). */
export interface OkrComposeContext {
  period: OkrPeriod;
  /** Kỳ chưa khoá có thể soạn (trường 5). */
  periods: OkrPeriod[];
  unit: { id: string; name: string };
  /** Đơn vị cao nhất của tenant: không bắt buộc mục tiêu cấp trên (BR-02-02). */
  isTopLevel: boolean;
  bases: OkrBasis[];
  parentOptions: ParentObjectiveOption[];
  /** Số mục tiêu đã có của chủ thể trong kỳ (không tính mục tiêu đang sửa). */
  existingCount: number;
  verifiers: VerifierOption[];
  /** Người dùng hiện tại, để mặc định người xác nhận khác chủ sở hữu (trường 14). */
  me: { id: string; name: string };
  aiEnabled: boolean;
  policy: OkrPolicy;
}

/** Một gợi ý của trợ lý AI cho KR (`/api/v1/ai/okr/review`, AC-12, chỉ đọc). */
export interface AiKrSuggestion {
  id: string;
  krCode: string;
  issue: string;
  rewrites: string[];
  sources: string[];
}

export interface AiOkrReview {
  suggestions: AiKrSuggestion[];
}

/** Kết quả trình phê duyệt (MSG-02-08). */
export interface OkrSubmitResult {
  status: ObjectiveStatus;
  reviewerName: string;
  dueDate: string;
}

/** Kết quả kiểm tra tự động một dòng của SCR-0203 (MOD-01 tính lại khi mở, không lưu). */
export type OkrCheckTone = 'DAT' | 'CANH_BAO' | 'LOI';

export interface OkrReviewCheck {
  tone: OkrCheckTone;
  /** Nhãn ngắn như "Có căn cứ", "Đo được", "Gần giống NV-2027-Q1-0012", "Vượt 3 O". */
  label: string;
}

/** Một KR trong bộ OKR chờ duyệt. */
export interface OkrReviewKr {
  id: string;
  code: string;
  title: string;
  /** Chỉ tiêu kèm đơn vị đo, ví dụ "100%", "3 ngày". */
  targetText: string;
  /** Trọng số 0–1. */
  weight: number;
  /** Liên kết cấp trên hoặc ngang của KR, ví dụ "Văn phòng · O1 (ngang)". */
  link: string | null;
  check: OkrReviewCheck;
}

/** Một mục tiêu trong bộ OKR chờ duyệt (`okr.objective` ở `CHO_DUYET`). */
export interface OkrReviewObjective {
  id: string;
  code: string;
  title: string;
  parentCode: string | null;
  versionNo: number;
  /** ETag gửi trong `If-Match` khi phê duyệt, trả lại. */
  rowVersion: number;
  check: OkrReviewCheck;
  keyResults: OkrReviewKr[];
}

/**
 * Một bộ OKR chờ duyệt: các mục tiêu `CHO_DUYET` của một đơn vị trong kỳ, tương ứng các user task
 * `duyet-okr` của người duyệt (SCR-0203 vùng 1, 2).
 */
export interface OkrReviewSet {
  id: string;
  unit: { id: string; name: string };
  periodId: string;
  submittedAt: string;
  submittedBy: string;
  versionNo: number;
  objectiveCount: number;
  krCount: number;
  /** Nhãn tổng hợp ở hàng chờ: "Đạt", "1 cảnh báo", "Vượt 3 O", "KR mơ hồ". */
  summary: OkrReviewCheck;
  blockingCount: number;
  warningCount: number;
  /** Người xem là người soạn bộ này: không được duyệt (BR-02-08, MSG-02-11). */
  viewerIsAuthor: boolean;
  objectives: OkrReviewObjective[];
}

/** Hàng chờ phê duyệt OKR của người dùng trong kỳ (SCR-0203). */
export interface OkrReviewQueue {
  period: OkrPeriod;
  periods: OkrPeriod[];
  /** Hạn duyệt theo lịch kỳ. */
  dueDate: string;
  reviewer: { name: string; title: string };
  /** Mẫu luồng và phiên bản đang áp dụng, ví dụ "duyet-okr" v2. */
  workflow: { code: string; version: number };
  items: OkrReviewSet[];
}

export type OkrHistoryAction =
  'TAO' | 'LUU_NHAP' | 'TRINH' | 'RUT_LAI' | 'DUYET' | 'TRA_LAI' | 'DONG';

/** Một mốc lịch sử mục tiêu (`GET /api/v1/okr/objectives/{id}/history`). */
export interface OkrHistoryEntry {
  at: string;
  action: OkrHistoryAction;
  versionNo: number;
  status?: ObjectiveStatus;
  actor: { id: string; name: string };
  comment?: string;
  aiAssisted?: boolean;
}

/** Ý kiến khi phê duyệt hoặc trả lại (`OkrApproveRequest`, `OkrReturnRequest`). */
export interface OkrReviewDecisionBody {
  comment?: string;
  notifyPeers: boolean;
}

// ---- Check-in KR (QT-03, SCR-0301; WEB-024). Tên trường theo `contracts/openapi/modules/okr.yaml`. ----

/** Check-in `DA_GUI` gần nhất của KR (`OkrLastCheckin`). */
export interface OkrLastCheckin {
  value: number;
  confidence: Confidence;
  checkinAt: string;
}

/** KR rút gọn trong danh sách "KR của tôi cần check-in" (`OkrKeyResultListItem`). */
export interface OkrKeyResultListItem {
  id: string;
  code: string;
  title: string;
  objectiveId?: string;
  objectiveCode?: string;
  unit?: string;
  targetValue?: number;
  currentValue?: number | null;
  progress?: number;
  confidence?: Confidence;
  riskFlag?: KrRiskFlag;
  dueDate?: string;
  /** Hạn check-in của chu kỳ hiện tại. */
  nextCheckinDue?: string;
  lastCheckin?: OkrLastCheckin;
}

/**
 * Trang KR (`OkrKeyResultPage`). `period` là phần mở rộng của dữ liệu giả lập cho tiêu đề SCR-0301
 * (tuần, kỳ), hợp đồng chưa có (wiki/14).
 */
export interface OkrKeyResultPage extends CursorPage<OkrKeyResultListItem> {
  counts?: { needCheckin: number };
  period?: OkrPeriod;
}

export type OkrCheckinStatus = 'NHAP' | 'DA_GUI';
export type OkrCheckinChannel = 'WEB' | 'MOBILE' | 'TICH_HOP';

/** Một lần check-in (`OkrCheckin`, `okr.kr_checkin`), chỉ ghi thêm. */
export interface OkrCheckin {
  id: string;
  keyResultId: string;
  periodId?: string;
  checkinAt: string;
  value: number;
  confidence: Confidence;
  highlights?: string;
  blockerNote?: string;
  channel: OkrCheckinChannel;
  status: OkrCheckinStatus;
  sourceValue?: number | null;
  sourceFetchedAt?: string;
  overrideReason?: string;
  onBehalf?: boolean;
}

/** Chi tiết KR cho SCR-0301 vùng 2 (`GET /api/v1/okr/key-results/{id}`, `OkrKeyResult`). */
export interface OkrKeyResultDetail {
  id: string;
  objectiveId: string;
  periodId?: string;
  code: string;
  title: string;
  unit: string;
  baselineValue: number | null;
  targetValue: number;
  currentValue: number | null;
  direction?: 'TANG' | 'GIAM' | 'DAT';
  weight: number;
  dueDate: string;
  verifier?: { id: string; name: string };
  owner?: { id: string; name: string };
  checkinDelegate?: { id: string; name: string };
  dataSource?: string;
  formulaText?: string;
  confidence?: Confidence;
  progress: number;
  status: ObjectiveStatus;
  riskFlag: KrRiskFlag;
  checkinFrequency?: CheckinFrequency;
  lastCheckin?: OkrLastCheckin;
  myDraftCheckin?: OkrCheckin;
  nextCheckinDue?: string;
  rowVersion: number;
  /** Mở rộng chờ hợp đồng (wiki/14): mục tiêu chứa KR và nhiệm vụ liên kết cho vùng 2, vùng 5. */
  objective?: { id: string; code: string; title: string };
  linkedWork?: KrLinkedWork[];
  linkedTaskCount?: number;
}

/** Thân yêu cầu lưu nháp hoặc gửi check-in (`OkrCheckinCreate`). */
export interface OkrCheckinCreate {
  status: OkrCheckinStatus;
  value: number;
  confidence: Confidence;
  sourceValue?: number;
  sourceFetchedAt?: string;
  overrideReason?: string;
  highlights?: string;
  blockerNote?: string;
  channel?: OkrCheckinChannel;
  /** Đã xác nhận cảnh báo chênh lệch lớn (BR-03-05, MSG-03-02). */
  confirmLargeChange?: boolean;
}

/** Kết quả ghi check-in kèm tiến độ đã tính lại (`OkrCheckinResult`, MSG-03-01). */
export interface OkrCheckinResult {
  checkin: OkrCheckin;
  keyResult: {
    id: string;
    code: string;
    currentValue?: number | null;
    progress: number;
    confidence?: Confidence;
    riskFlag: KrRiskFlag;
  };
  objective: { id: string; code: string; progress: number };
}

/** Số liệu nguồn qua SVC-13 (`OkrSourceValue`). */
export interface OkrSourceValue {
  value: number;
  fetchedAt: string;
  dataSource?: string;
}
