import type { components } from '../generated/okr';

type Schemas = components['schemas'];

/**
 * Mô hình dữ liệu module okr (MOD-01, `/api/v1/okr`), tên trường theo TL-03 schema `okr`
 * (`okr.basis`, `okr.objective`, `okr.key_result`, `okr.okr_link`, `okr.kr_checkin`) đổi sang camelCase.
 * Kiểu có cùng nghĩa trong `contracts/openapi/modules/okr.yaml` là bí danh của lược đồ `Okr*` sinh ra
 * (cây mục tiêu, hàng chờ duyệt, ngữ cảnh soạn theo WEB-063). Tiến độ (`progress`) là tỉ lệ 0..1,
 * người là `Ref` `{id, name}`. Kiểu viết tay còn lại (bản nháp soạn SCR-0202, gợi ý AI, kết quả trình)
 * chưa có lược đồ cùng hình dạng, ghi ở wiki/14.
 */

/** Kết quả then chốt dùng để liên kết nhiệm vụ (`okr.key_result`). */
export interface KeyResultRef {
  id: string;
  code: string;
  title: string;
}

/** Trạng thái mục tiêu `okr.objective.status` (baseline, TL-02 QT-02 Hình 7.2); KR theo trạng thái mục tiêu. */
export type ObjectiveStatus = Schemas['OkrObjectiveStatus'];

export const OBJECTIVE_STATUS_LABEL: Record<ObjectiveStatus, string> = {
  NHAP: 'Nháp',
  CHO_DUYET: 'Chờ duyệt',
  DA_DUYET: 'Đã duyệt',
  DONG: 'Đã đóng',
};

/** Mức tự tin `okr.kr_checkin.confidence` (QT-03 bước 5). */
export type Confidence = Schemas['OkrConfidence'];

export const CONFIDENCE_LABEL: Record<Confidence, string> = {
  CAO: 'Tự tin cao',
  TRUNG_BINH: 'Trung bình',
  THAP: 'Tự tin thấp',
};

/** Cờ rủi ro KR `okr.key_result.risk_flag` (QT-03 Hình 7.7). */
export type KrRiskFlag = Schemas['OkrRiskFlag'];

/** Trạng thái kỳ `config.period.status` (baseline). */
export type OkrPeriodStatus = Schemas['PeriodStatus'];

/** Chủ thể sở hữu `okr.objective.owner_type`. */
export type OkrOwnerType = Schemas['OkrOwnerType'];

/** Loại căn cứ `okr.basis.basis_kind`. */
export type BasisKind = Schemas['OkrBasisKind'];

/** Kỳ đánh giá để chọn trên SCR-0201 (trường 1, `config.period`). */
export type OkrPeriod = Schemas['OkrPeriodInfo'];

/** Căn cứ (`okr.basis`), SCR-0201 trường 2. */
export type OkrBasis = Schemas['OkrBasisRef'];

/** Mục tiêu cấp trên (`okr.okr_link` kiểu phân rã), SCR-0201 trường 3. */
export type ObjectiveRef = Schemas['OkrObjectiveRef'];

/** Chương trình (Tầng 2) hoặc nhiệm vụ (Tầng 3) liên kết với KR (`okr.okr_link` tới schema `task`), trường 13. */
export type KrLinkedWork = Schemas['OkrLinkedWork'];

/** Kết quả then chốt trên cây (`okr.key_result` cùng check-in mới nhất). */
export type KeyResult = Schemas['OkrKeyResult'];

/** Mục tiêu trên cây (`okr.objective`); tầng 1 của đơn vị, tầng 2 của phòng, tầng 3 của cá nhân. */
export type Objective = Schemas['OkrObjective'];

/** Liên kết ngang giữa đơn vị (`okr.okr_link`, `link_kind = NGANG`), SCR-0201 trường 12. */
export type OkrPeerLink = Schemas['OkrPeerLink'];

/** Dữ liệu cây mục tiêu của một đơn vị trong kỳ (`GET /api/v1/okr/tree`). */
export type OkrTree = Schemas['OkrTree'];

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
  channel: OkrCheckinChannel;
  status: OkrCheckinStatus;
}

/** Chế độ hiển thị SCR-0201 (nút "Cây, Bảng, Bản đồ liên kết"). */
export type OkrTreeView = 'tree' | 'table' | 'map';

/** Tần suất check-in `okr.key_result.checkin_frequency` (SCR-0202 trường 15). */
export type CheckinFrequency = Schemas['OkrCheckinFrequency'];

export const CHECKIN_FREQUENCY_LABEL: Record<CheckinFrequency, string> = {
  TUAN: 'Tuần',
  HAI_TUAN: 'Hai tuần',
  THANG: 'Tháng',
};

/** Nguồn nội dung `okr.key_result.content_source` (BR-02-11). */
export type ContentSource = Schemas['OkrContentSource'];

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
export type OkrRuleSeverity = OkrRule['severity'];

/**
 * Quy tắc soát OKR viết bằng CEL trong Policy Pack (QT-02 bước 4): chạy trên trình duyệt để phản
 * hồi tức thì và chạy lại ở máy chủ khi trình. `scope` cho biết biểu thức chạy trên `objective`
 * hay trên từng `kr`. `message` có thể chứa chỗ trống `{tên}` lấy từ biến cùng tên của ngữ cảnh.
 */
export type OkrRule = Schemas['OkrRule'];

/** Tham số soát OKR của Policy Pack đang áp dụng cho tenant. */
export type OkrPolicy = Schemas['OkrPolicy'];

/** Mục tiêu cấp trên có thể chọn (SCR-0202 trường 2). */
export type ParentObjectiveOption = Schemas['OkrParentOption'];

/** Người xác nhận KR có thể chọn (trường 14). */
export type VerifierOption = Schemas['OkrPersonOption'];

/** Ngữ cảnh soạn mục tiêu của đơn vị trong kỳ (`GET /api/v1/okr/compose-context`). */
export type OkrComposeContext = Schemas['OkrComposeContext'];

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
export type OkrCheckTone = Schemas['OkrCheckLevel'];

export type OkrReviewCheck = Schemas['OkrReviewCheck'];

/** Một KR trong bộ OKR chờ duyệt. */
export type OkrReviewKr = Schemas['OkrReviewKr'];

/** Một mục tiêu trong bộ OKR chờ duyệt (`okr.objective` ở `CHO_DUYET`). */
export type OkrReviewObjective = Schemas['OkrReviewObjective'];

/**
 * Một bộ OKR chờ duyệt: các mục tiêu `CHO_DUYET` của một đơn vị trong kỳ, tương ứng các user task
 * `duyet-okr` của người duyệt (SCR-0203 vùng 1, 2).
 */
export type OkrReviewSet = Schemas['OkrReviewSet'];

/** Hàng chờ phê duyệt OKR của người dùng trong kỳ (SCR-0203). */
export type OkrReviewQueue = Schemas['OkrReviewQueue'];

export type OkrHistoryAction = Schemas['OkrObjectiveHistoryEntry']['action'];

/** Một mốc lịch sử mục tiêu (`GET /api/v1/okr/objectives/{id}/history`). */
export type OkrHistoryEntry = Schemas['OkrObjectiveHistoryEntry'];

/** Ý kiến khi phê duyệt hoặc trả lại (`OkrApproveRequest`, `OkrReturnRequest`). */
export type OkrReviewDecisionBody = Schemas['OkrApproveRequest'];

// ---- Check-in KR (QT-03, SCR-0301; WEB-024). Tên trường theo `contracts/openapi/modules/okr.yaml`. ----

/** Check-in `DA_GUI` gần nhất của KR (`OkrLastCheckin`). */
export type OkrLastCheckin = Schemas['OkrLastCheckin'];

/** KR rút gọn trong danh sách "KR của tôi cần check-in" (`OkrKeyResultListItem`). */
export type OkrKeyResultListItem = Schemas['OkrKeyResultListItem'];

/** Trang KR (`OkrKeyResultPage`), `period` để dựng tiêu đề SCR-0301 (tuần, kỳ). */
export type OkrKeyResultPage = Schemas['OkrKeyResultPage'];

export type OkrCheckinStatus = Schemas['OkrCheckinStatus'];
export type OkrCheckinChannel = Schemas['OkrCheckinChannel'];

/** Một lần check-in (`OkrCheckin`, `okr.kr_checkin`), chỉ ghi thêm. */
export type OkrCheckin = Schemas['OkrCheckin'];

/** Chi tiết KR cho SCR-0301 vùng 2 (`GET /api/v1/okr/key-results/{id}`, `OkrKeyResult`). */
export type OkrKeyResultDetail = KeyResult;

/**
 * Thân yêu cầu lưu nháp hoặc gửi check-in (`OkrCheckinCreate`). `confirmLargeChange` có `default: false`
 * nên kiểu sinh coi là bắt buộc; ở thân yêu cầu trường này được phép vắng.
 */
export type OkrCheckinCreate = Omit<Schemas['OkrCheckinCreate'], 'confirmLargeChange'> & {
  confirmLargeChange?: boolean;
};

/** Kết quả ghi check-in kèm tiến độ đã tính lại (`OkrCheckinResult`, MSG-03-01). */
export type OkrCheckinResult = Schemas['OkrCheckinResult'];

/** Số liệu nguồn qua SVC-13 (`OkrSourceValue`). */
export type OkrSourceValue = Schemas['OkrSourceValue'];
