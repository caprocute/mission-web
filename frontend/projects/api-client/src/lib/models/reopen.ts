import type { components } from '../generated/oversight';

/**
 * Yêu cầu mở lại kỳ hai người duyệt (`/api/v1/oversight/reopen-requests`, QT-13, SCR-1301): bí danh của
 * lược đồ trong `contracts/openapi/modules/oversight.yaml` (WEB-043), tên trường theo
 * `oversight.reopen_request`, `oversight.reopen_approval` của TL-03.
 */
type S = components['schemas'];

export type ReopenRequestStatus = S['ReopenRequestStatus'];
export type ReopenScope = S['ReopenScope'];
export type ReopenAuditKind = S['ReopenAuditKind'];
export type ReopenScopeItem = S['ReopenScopeItem'];
export type ReopenBasis = S['ReopenBasis'];
export type ReopenBasisKind = ReopenBasis['kind'];
export type ReopenRequestInput = S['ReopenRequestInput'];
export type ReopenApproval = S['ReopenApproval'];
export type ReopenApproverSlot = S['ReopenApproverSlot'];
export type ReopenRequest = S['ReopenRequest'];
export type ReopenImpact = S['ReopenImpact'];
export type ComplaintAdjustment = S['ComplaintAdjustment'];

export interface ReopenRequestFilter {
  periodId?: string;
  status?: ReopenRequestStatus[];
  awaitingMyApproval?: boolean;
}

/** Trạng thái yêu cầu (TL-02 Hình 11.6, TL-03 thêm `DA_THUC_HIEN`, `DA_DONG`). */
export const REOPEN_STATUS_LABEL: Record<ReopenRequestStatus, string> = {
  CHO_DUYET_1: 'Chờ người duyệt thứ nhất',
  CHO_DUYET_2: 'Chờ người duyệt thứ hai',
  DA_DUYET: 'Đã đủ hai phê duyệt',
  TU_CHOI: 'Bị từ chối',
  DA_THUC_HIEN: 'Đã mở lại kỳ',
  DA_DONG: 'Đã đóng',
};

/** Yêu cầu còn chờ duyệt, người tạo còn huỷ được. */
export const REOPEN_PENDING_STATUSES: readonly ReopenRequestStatus[] = [
  'CHO_DUYET_1',
  'CHO_DUYET_2',
];

/** Loại hậu kiểm làm căn cứ (SCR-1301 trường 3). */
export const REOPEN_AUDIT_KIND_LABEL: Record<ReopenAuditKind, string> = {
  KE_HOACH: 'Theo kế hoạch',
  DOT_XUAT: 'Đột xuất',
  PHAN_ANH: 'Theo khiếu nại, phản ánh',
};

/** Phạm vi mở (SCR-1301 trường 4). */
export const REOPEN_SCOPE_LABEL: Record<ReopenScope, string> = {
  HO_SO: 'Danh sách hồ sơ cụ thể',
  DON_VI: 'Đơn vị',
  TOAN_KY: 'Toàn kỳ (cần quyết định hậu kiểm toàn diện)',
};

export const REOPEN_BASIS_LABEL: Record<ReopenBasisKind, string> = {
  KHIEU_NAI: 'Kết luận khiếu nại',
  CANH_BAO: 'Cảnh báo bất thường',
  HAU_KIEM: 'Đợt hậu kiểm',
  QUYET_DINH: 'Quyết định hậu kiểm',
};

/** Vai trò của từng lượt duyệt (BR-13-02). */
export const REOPEN_APPROVER_ROLE_LABEL: Record<ReopenApproverSlot['role'], string> = {
  decider: 'Người duyệt thứ nhất',
  second_approver: 'Người duyệt thứ hai',
};

/** Ràng buộc độ dài của SCR-1301: lý do 50–4.000 (trường 6), ý kiến 10–2.000 (trường 10), thời hạn 1–10. */
export const REOPEN_LIMITS = {
  reasonMin: 50,
  reasonMax: 4000,
  commentMin: 10,
  commentMax: 2000,
  daysMin: 1,
  daysMax: 10,
  daysDefault: 3,
} as const;
