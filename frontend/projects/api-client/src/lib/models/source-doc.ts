import { AssigneeRef, SimilarTask } from '@vt/api-client';
import { TaskStatus } from '@vt/api-client';

/** Hệ nguồn của văn bản (`task.task_source_doc.source_system`, TL-03). */
export type SourceSystem = 'VOFFICE' | 'IOFFICE' | 'EDXML' | 'LGSP' | 'THU_CONG';

export const SOURCE_SYSTEM_LABEL: Record<SourceSystem, string> = {
  VOFFICE: 'vOffice',
  IOFFICE: 'iOffice',
  EDXML: 'EdXML',
  LGSP: 'LGSP',
  THU_CONG: 'Nhập tay',
};

/** Tình trạng xử lý văn bản đến ở đơn vị nhận (suy luận từ SCR-0502 "Bỏ qua văn bản"). */
export type SourceDocStatus = 'CHO_XU_LY' | 'DA_XU_LY' | 'DA_BO_QUA';

export const SOURCE_DOC_STATUS_LABEL: Record<SourceDocStatus, string> = {
  CHO_XU_LY: 'Chờ xử lý',
  DA_XU_LY: 'Đã xử lý',
  DA_BO_QUA: 'Đã bỏ qua',
};

/** Tình trạng trích nhiệm vụ của SVC-14 (QT-05 bước 2, ≤ 1 phút). */
export type ExtractionStatus = 'DANG_TRICH' | 'XONG' | 'KHONG_TRICH_DUOC';

/** Một dòng "Văn bản đến" (SVC-13 → EVT-24 → `task.task_source_doc`, `integ.inbox_message`). */
export interface SourceDocSummary {
  id: string;
  sourceSystem: SourceSystem;
  /** Số, ký hiệu theo NĐ 30/2020 (`doc_number`). */
  docNumber: string;
  docDate: string;
  /** Cơ quan ban hành. */
  issuer: string;
  /** Trích yếu (`excerpt`). */
  excerpt: string;
  /** Thời điểm nhận (`integ.inbox_message.received_at`). */
  receivedAt: string;
  /** Định dạng gói tin nhận, ví dụ `EdXML`. */
  format: string;
  status: SourceDocStatus;
  extraction: ExtractionStatus;
  /** Số nhiệm vụ AI trích còn chờ xác nhận (MSG-05-06). */
  pendingCount: number;
  suggestionCount: number;
}

/** Một đoạn của toàn văn; `assignment` là đoạn giao việc cho đơn vị nhận do AI xác định. */
export interface SourceDocParagraph {
  no: number;
  text: string;
  assignment: boolean;
}

/** Toàn văn văn bản (tệp gốc trong evidence-service, dựng từ EdXML). */
export interface SourceDoc extends SourceDocSummary {
  issuerHeader: string;
  title: string;
  preamble: string;
  paragraphs: SourceDocParagraph[];
  recipients: string;
  /** Lý do khi văn bản đã bỏ qua. */
  dismissReason?: string;
}

/** Quyết định của người giao với một nhiệm vụ AI trích (SCR-0502 trường 12, `ai.suggestion_log.decision`). */
export type SuggestionDecision = 'CHO_XAC_NHAN' | 'DA_XAC_NHAN' | 'DA_BO' | 'DA_GOP';

export const SUGGESTION_DECISION_LABEL: Record<SuggestionDecision, string> = {
  CHO_XAC_NHAN: 'Chờ xác nhận',
  DA_XAC_NHAN: 'Đã xác nhận',
  DA_BO: 'Đã bỏ',
  DA_GOP: 'Đã gộp',
};

/** Các ô AI điền trên thẻ nhiệm vụ (SCR-0502 trường 5–10), tên theo `task.task`. */
export interface TaskSuggestionFields {
  title: string;
  leadUnitId: string;
  leadUnitName: string;
  coUnits: AssigneeRef[];
  dueDate: string;
  productName: string;
  catalogItemId?: string;
  catalogItemCode?: string;
  keyResultId?: string;
  keyResultCode?: string;
}

/** Tên ô ghi vào `edited_fields` khi người giao sửa (SCR-0502 trường 13). */
export type SuggestionField =
  'title' | 'leadUnitId' | 'coUnits' | 'dueDate' | 'productName' | 'catalogItemId' | 'keyResultId';

/** Một nhiệm vụ do AI trích (`ai.suggestion_log`, use_case `GOI_Y_NHIEM_VU`). */
export interface TaskSuggestion {
  id: string;
  paragraphNo: number;
  /** Độ tin cậy 0–1 (`confidence`). */
  confidence: number;
  decision: SuggestionDecision;
  /** Giá trị hiện tại: bằng gợi ý của AI cho tới khi người giao sửa. */
  fields: TaskSuggestionFields;
  /** Gợi ý gốc của AI, để biết ô nào đã sửa (mất viền tím). */
  aiFields: TaskSuggestionFields;
  editedFields: SuggestionField[];
  /** Nhiệm vụ gần giống cùng đơn vị và kỳ (BR-05-06). */
  similar: SimilarTask[];
  /** Nhiệm vụ đã tạo khi xác nhận (`NHAP`) hoặc nhiệm vụ cũ khi gộp. */
  taskId?: string;
  taskCode?: string;
  taskStatus?: TaskStatus;
  reason?: string;
}

/** Kết quả trích nhiệm vụ của SVC-14 cho một văn bản. */
export interface DocExtraction {
  sourceDocId: string;
  status: ExtractionStatus;
  model: string;
  extractedAt: string;
  suggestions: TaskSuggestion[];
}

/** `POST /api/v1/tasks/from-document`: tạo nhiệm vụ `NHAP` từ gợi ý đã xác nhận. */
export interface FromDocumentRequest {
  sourceDocId: string;
  suggestionId: string;
  paragraphNo: number;
  fields: TaskSuggestionFields;
  editedFields: SuggestionField[];
  /** Lý do vẫn tạo mới khi có nhiệm vụ gần giống (BR-05-06). */
  similarReason?: string;
}

/** Kết quả từng nhiệm vụ của `POST /api/v1/tasks/assign-batch`. */
export interface AssignBatchResult {
  taskId: string;
  code: string;
  assigned: boolean;
  status: TaskStatus;
  message?: string;
}
