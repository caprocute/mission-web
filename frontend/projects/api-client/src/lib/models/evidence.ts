/**
 * Mô hình module `products` (MOD-03) và `files` (SVC-08) theo hợp đồng
 * `contracts/openapi/modules/products.yaml` (WEB-063): sản phẩm, minh chứng, phiên bản minh chứng,
 * phiên tải lên nhiều phần và kết quả quét (QT-07, SCR-0701, SCR-0703).
 */

/** Tham chiếu rút gọn kèm tên hiển thị (`Ref` của hợp đồng). */
export interface NamedRef {
  id: string;
  name: string;
}

/** Nhiệm vụ rút gọn (`ProductTaskRef`). */
export interface ProductTaskRef {
  id: string;
  code: string;
  name: string;
}

/** Sản phẩm của nhiệm vụ (`product.deliverable`). */
export interface Deliverable {
  id: string;
  taskId: string;
  name: string;
  deliverableType: string;
  unit: string;
  quantityPlanned: number;
  quantityAccepted: number;
  status: string;
  rowVersion: number;
}

/** Trạng thái nghiệm thu của phiên bản minh chứng (baseline 9.1, TL-02 Hình 8.12). */
export type EvidenceVersionStatus =
  'NHAP' | 'DA_NOP' | 'DANG_NGHIEM_THU' | 'DA_XAC_NHAN' | 'YEU_CAU_BO_SUNG' | 'TU_CHOI';

export const EVIDENCE_STATUS_LABEL: Record<EvidenceVersionStatus, string> = {
  NHAP: 'Nháp',
  DA_NOP: 'Đã nộp',
  DANG_NGHIEM_THU: 'Đang nghiệm thu',
  DA_XAC_NHAN: 'Đã xác nhận',
  YEU_CAU_BO_SUNG: 'Yêu cầu bổ sung',
  TU_CHOI: 'Từ chối',
};

/** Nguồn minh chứng (`product.evidence.source_type`, TL-03). */
export type EvidenceSourceType = 'TAI_LEN' | 'VAN_BAN' | 'HE_THONG';

/** Nguồn của phiên bản (`product.evidence_version.source`, TL-03). */
export type EvidenceVersionSource = 'TAI_LEN' | 'VAN_BAN_HE_THONG' | 'OCR_DI_DONG' | 'TICH_HOP';

/** Trạng thái quét mã độc (`evidence.object_version.scan_status`). */
export type FileScanStatus = 'CHO_QUET' | 'SACH' | 'NHIEM' | 'LOI';

export const FILE_SCAN_LABEL: Record<FileScanStatus, string> = {
  CHO_QUET: 'Đang quét',
  SACH: 'Sạch',
  NHIEM: 'Nhiễm',
  LOI: 'Lỗi quét',
};

/** Minh chứng khác trong tenant có cùng mã băm (`evidence.dedup_index`, MSG-07-01). */
export interface EvidenceDuplicateMatch {
  taskCode: string;
  ownerName?: string;
  submittedAt?: string;
}

/** Một dòng lịch sử phiên bản (vùng 4 SCR-0701). */
export interface EvidenceVersionSummary {
  versionNo: number;
  status: EvidenceVersionStatus;
  submittedAt?: string;
  decidedAt?: string;
  qualityLevel?: number;
  /** Tóm tắt nhận xét nghiệm thu (C2). */
  decisionNote?: string;
}

/** Minh chứng của một sản phẩm (`product.evidence`). */
export interface Evidence {
  id: string;
  deliverableId: string;
  task: ProductTaskRef;
  title: string;
  sourceType: EvidenceSourceType;
  externalDocRef?: string | null;
  currentVersionNo?: number | null;
  currentStatus?: EvidenceVersionStatus | null;
  isDuplicateSuspect?: boolean;
  submittedBy?: NamedRef;
  submittedAt?: string;
  /** Chỉ có ở `GET /evidences/{id}`. */
  versions?: EvidenceVersionSummary[];
  rowVersion: number;
}

export interface EvidenceCreate {
  deliverableId: string;
  title: string;
  sourceType: EvidenceSourceType;
  externalDocRef?: string;
}

/** Văn bản đã ban hành lấy từ vOffice qua SVC-13 (BR-07-13). */
export interface EvidenceExternalDoc {
  ref: string;
  docNumber: string;
  docDate: string;
  summary: string;
  signerName?: string;
  signatureValid?: boolean;
}

/** Tiêu chuẩn chất lượng của mục danh mục, để đối chiếu ở SCR-0703. */
export interface EvidenceQualityCriterion {
  code: string;
  text: string;
}

/** Phiên bản minh chứng (`product.evidence_version`); rời `NHAP` thì tệp và mã băm đóng băng (BR-07-01). */
export interface EvidenceVersion {
  id: string;
  evidenceId: string;
  taskId: string;
  versionNo: number;
  status: EvidenceVersionStatus;
  source: EvidenceVersionSource;
  sourceDocRef?: string | null;
  objectId?: string | null;
  objectVersionId?: string | null;
  contentSha256?: string | null;
  sizeBytes?: number | null;
  mediaType?: string | null;
  /** Tên tệp gốc (C2). */
  fileName?: string | null;
  scanStatus: FileScanStatus;
  isDuplicateSuspect: boolean;
  duplicateOf?: EvidenceDuplicateMatch | null;
  reuseJustification?: string | null;
  hasE2ee: boolean;
  aiPrefill: boolean;
  docNumber?: string | null;
  docDate?: string | null;
  docSummary?: string | null;
  note?: string | null;
  submittedBy?: NamedRef;
  submittedAt?: string;
  attestedAt?: string;
  acceptedAt?: string;
  integrity?: { verified: boolean; checkedAt: string };
  qualityCriteria?: EvidenceQualityCriterion[];
  hasPriorRevisionRequest?: boolean;
  rowVersion: number;
}

/** Nội dung phiên bản khi lưu nháp (`EvidenceVersionDraft`). */
export interface EvidenceVersionDraft {
  source: EvidenceVersionSource;
  sourceDocRef?: string;
  objectId?: string;
  reuseJustification?: string;
  docNumber?: string;
  docDate?: string;
  docSummary?: string;
  note?: string;
}

export interface EvidenceSubmitResult {
  version: EvidenceVersion;
  reviewer?: NamedRef;
  /** Trạng thái mới của nhiệm vụ nếu đổi (EVT-06), ví dụ `CHO_NGHIEM_THU` (MSG-07-05). */
  taskStatusChanged?: string | null;
}

/** Kiểu tệp được phép (SEC-48) và giới hạn mặc định 50 MB (SCR-0701 trường 5). */
export const EVIDENCE_MEDIA_TYPES: Record<string, string> = {
  pdf: 'application/pdf',
  docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  png: 'image/png',
};
export const EVIDENCE_MAX_BYTES = 50 * 1024 * 1024;

/** Phiên tải lên nhiều phần (`evidence.upload_session`). */
export interface FileUploadPart {
  partNo: number;
  size?: number;
  etag: string;
}

export interface FileUploadSession {
  uploadId: string;
  status: 'MO' | 'HOAN_TAT' | 'HUY';
  partSize: number;
  partCount?: number;
  partsDone: number;
  receivedParts?: FileUploadPart[];
  expiresAt: string;
}

export interface FileUploadCreate {
  fileName: string;
  mediaType: string;
  declaredSize: number;
  declaredSha256: string;
  ownerModule: 'product' | 'dossier' | 'oversight';
  ownerRefId?: string;
  classification: 'C1' | 'C2' | 'C3';
  e2ee?: boolean;
}

/** Đối tượng đã lưu ở SVC-08 sau khi hoàn tất tải lên. */
export interface FileObject {
  objectId: string;
  objectVersionId: string;
  versionNo: number;
  fileName?: string;
  mediaType: string;
  sizeBytes: number;
  sha256: string;
  classification: string;
  e2ee: boolean;
  scanStatus: FileScanStatus;
  dedup?: { duplicate: boolean; matches?: EvidenceDuplicateMatch[] };
  createdAt: string;
}

/** Kết quả quét (`GET /files/{objectId}/scan`). */
export interface FileScanResult {
  objectId: string;
  objectVersionId: string;
  scanStatus: FileScanStatus;
  verdict?: 'SACH' | 'NHIEM' | 'NGHI_NGO' | 'LOI';
  scannedAt?: string;
}
