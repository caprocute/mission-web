import type { components as AppraisalComponents } from '../generated/appraisals';
import type { components, operations } from '../generated/oversight';

/**
 * Khiếu nại, giải trình, bảo lưu ý kiến (`/api/v1/oversight/complaints`, QT-12, SCR-1201, SCR-1202): bí
 * danh của lược đồ trong `contracts/openapi/modules/oversight.yaml` (WEB-042), tên trường theo
 * `oversight.complaint`, `oversight.complaint_response` của TL-03. Nội dung là dữ liệu C2: máy chủ mã hoá
 * trường và ghi sổ cái mỗi lần giải mã (ADR-06, BR-12-10); trình duyệt không lưu cục bộ.
 */
type S = components['schemas'];
type Body<K extends keyof operations> = operations[K] extends {
  requestBody?: { content: { 'application/json': infer B } };
}
  ? B
  : never;

export type ComplaintKind = S['ComplaintKind'];
export type ComplaintTargetStage = S['ComplaintTargetStage'];
export type ComplaintStatus = S['ComplaintStatus'];
export type ComplaintOutcome = S['ComplaintOutcome'];
export type ComplaintResolution = S['ComplaintResolution'];
export type ComplaintRecordKind = S['ComplaintRecordKind'];
export type ComplaintTarget = S['ComplaintTarget'];
export type ComplainableProfile = S['ComplainableProfile'];
export type ComplaintInput = S['ComplaintInput'];
export type ComplaintSummary = S['ComplaintSummary'];
export type ComplaintTimelineItem = S['ComplaintTimelineItem'];
export type Complaint = S['Complaint'];
export type ExplanationParty = S['ExplanationParty'];
export type ComplaintConclusionInput = S['ComplaintConclusionInput'];
export type ComplaintConclusion = S['ComplaintConclusion'];
export type ComplaintResponseRecord = S['ComplaintResponseRecord'];
export type ComplaintRejectInput = Body<'rejectComplaint'>;
export type ComplaintRejectReasonCode = ComplaintRejectInput['reasonCode'];
export type ComplaintExplanationRequestInput = Body<'requestComplaintExplanations'>;
export type ComplaintExplanationInput = Body<'submitComplaintExplanation'>;
/** Tính thử của SVC-07 (`POST /appraisals/profiles/{id}/simulate`), gọi từ SCR-1202. */
export type AppraisalSimulationInput = AppraisalComponents['schemas']['AppraisalSimulationInput'];
export type AppraisalSimulationResult = AppraisalComponents['schemas']['AppraisalSimulationResult'];

export interface ComplaintFilter {
  periodId?: string;
  status?: ComplaintStatus[];
  kind?: ComplaintKind;
  assignedToMe?: boolean;
  q?: string;
}

export const COMPLAINT_KIND_LABEL: Record<ComplaintKind, string> = {
  KHIEU_NAI: 'Khiếu nại',
  GIAI_TRINH: 'Giải trình bổ sung',
  BAO_LUU: 'Bảo lưu ý kiến',
};

export const COMPLAINT_KIND_HINT: Record<ComplaintKind, string> = {
  KHIEU_NAI: 'Đề nghị xem xét lại kết quả.',
  GIAI_TRINH: 'Cung cấp thêm thông tin, không đề nghị thay đổi kết quả.',
  BAO_LUU: 'Không đồng ý nhưng không đề nghị xử lý, chỉ đề nghị lưu ý kiến vào hồ sơ.',
};

export const COMPLAINT_STAGE_LABEL: Record<ComplaintTargetStage, string> = {
  THAM_DINH: 'Thẩm định',
  DE_XUAT: 'Đề xuất',
  QUYET_DINH: 'Quyết định',
};

/** Trạng thái khiếu nại (TL-02 Hình 11.2, thêm `RUT` của TL-02b); nháp có `status` là `null`. */
export const COMPLAINT_STATUS_LABEL: Record<ComplaintStatus, string> = {
  MOI: 'Mới, chờ tiếp nhận',
  DA_TIEP_NHAN: 'Đã tiếp nhận',
  DANG_GIAI_TRINH: 'Đang giải trình',
  CHO_KET_LUAN: 'Chờ kết luận',
  DA_TRA_LOI: 'Đã trả lời',
  DANG_THI_HANH: 'Đang thi hành',
  KHONG_THU_LY: 'Không thụ lý',
  RUT: 'Đã rút',
  DONG: 'Đã đóng',
};

export const COMPLAINT_RESOLUTION_LABEL: Record<ComplaintResolution, string> = {
  GIU_NGUYEN: 'Giữ nguyên kết quả',
  CHAP_NHAN_MOT_PHAN: 'Chấp nhận một phần',
  CHAP_NHAN: 'Chấp nhận toàn bộ',
};

export const COMPLAINT_RECORD_KIND_LABEL: Record<ComplaintRecordKind, string> = {
  YEU_CAU: 'Yêu cầu giải trình',
  GIAI_TRINH: 'Giải trình',
  KET_LUAN: 'Kết luận',
  BAO_LUU: 'Ý kiến bảo lưu',
};

export const COMPLAINT_REJECT_REASON_LABEL: Record<ComplaintRejectReasonCode, string> = {
  QUA_HAN: 'Quá thời hạn khiếu nại',
  SAI_THAM_QUYEN: 'Không thuộc thẩm quyền',
  TRUNG_NOI_DUNG: 'Trùng nội dung đã được trả lời',
};

/** Trước khi có kết luận: người khiếu nại còn rút được. */
export const COMPLAINT_OPEN_STATUSES: readonly ComplaintStatus[] = [
  'MOI',
  'DA_TIEP_NHAN',
  'DANG_GIAI_TRINH',
  'CHO_KET_LUAN',
];

/** Độ dài theo bảng trường SCR-1201, SCR-1202 và hợp đồng. */
export const COMPLAINT_LIMITS = {
  contentMin: 50,
  contentMax: 8000,
  requestMax: 500,
  rejectReasonMin: 20,
  responseTextMax: 16000,
  withdrawReasonMin: 10,
  attachmentMaxBytes: 20 * 1024 * 1024,
} as const;
