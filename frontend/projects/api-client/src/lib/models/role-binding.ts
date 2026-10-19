import type { components } from '../generated/config';

/**
 * Quản trị vai trò và uỷ quyền (QT-01, SCR-0105 đến SCR-0107, WEB-058): bí danh của lược đồ trong
 * `contracts/openapi/modules/config.yaml`. Nguồn dữ liệu là `config.role_binding` (TL-03, kèm các cột
 * bổ sung của WEB-058) và bảng bổ sung `config.delegation`.
 */
type S = components['schemas'];

export type RoleCode = S['RoleCode'];
export type RoleBindingScope = S['RoleBindingScope'];
export type RoleBindingStatus = S['RoleBindingStatus'];
export type AccessEndKind = S['AccessEndKind'];
export type RoleBinding = S['RoleBinding'];
export type RoleBindingCreate = S['RoleBindingCreate'];
export type RoleBindingApprove = S['RoleBindingApprove'];
export type AccessEndInput = S['AccessEndInput'];
export type RoleBindingCounts = S['RoleBindingCounts'];
export type RoleBindingPage = S['RoleBindingPage'];
export type AccessEventKind = S['AccessEventKind'];
export type AccessEvent = S['AccessEvent'];
export type AccessEventPage = S['AccessEventPage'];
export type DelegationKind = S['DelegationKind'];
export type DelegationStatus = S['DelegationStatus'];
export type Delegation = S['Delegation'];
export type DelegationCreate = S['DelegationCreate'];
export type DelegationPage = S['DelegationPage'];

/** Ba vai trò cần người duyệt thứ hai khác người cấp (TL-03 `approved_by`, BR-01-14). */
export const SENSITIVE_ROLES: readonly RoleCode[] = ['decider', 'second_approver', 'policy_author'];

/** Tên vai trò theo baseline mục 10 (AC-02 đến AC-10). */
export const ROLE_CODE_LABEL: Record<RoleCode, string> = {
  tenant_admin: 'Quản trị tenant',
  policy_author: 'Quản trị nghiệp vụ, BA cấu hình',
  leader: 'Lãnh đạo, người đứng đầu',
  manager: 'Cán bộ quản lý trực tiếp',
  member: 'Cán bộ, công chức, viên chức',
  appraiser: 'Cơ quan tổ chức cán bộ (thẩm định)',
  decider: 'Cấp có thẩm quyền quyết định',
  second_approver: 'Người duyệt thứ hai',
  inspector: 'Cơ quan kiểm tra, giám sát',
};

export const ROLE_SCOPE_LABEL: Record<RoleBindingScope, string> = {
  DON_VI: 'Đơn vị',
  DON_VI_VA_CAP_DUOI: 'Đơn vị và cấp dưới',
};

export const ROLE_BINDING_STATUS_LABEL: Record<RoleBindingStatus, string> = {
  CHO_DUYET: 'Chờ duyệt thứ hai',
  SAP_HIEU_LUC: 'Sắp hiệu lực',
  HIEU_LUC: 'Đang hiệu lực',
  HET_HAN: 'Hết hạn',
  DA_THU_HOI: 'Đã thu hồi',
  TU_CHOI: 'Bị từ chối',
};

export const ACCESS_EVENT_KIND_LABEL: Record<AccessEventKind, string> = {
  CAP_VAI_TRO: 'Cấp vai trò',
  DUYET_VAI_TRO: 'Duyệt thứ hai',
  TU_CHOI_VAI_TRO: 'Từ chối cấp vai trò',
  THU_HOI_VAI_TRO: 'Thu hồi vai trò',
  HET_HAN_VAI_TRO: 'Vai trò hết hạn',
  TAO_UY_QUYEN: 'Uỷ quyền',
  THU_HOI_UY_QUYEN: 'Thu hồi uỷ quyền',
  HET_HAN_UY_QUYEN: 'Uỷ quyền hết hạn',
};

export const DELEGATION_KIND_LABEL: Record<DelegationKind, string> = {
  DUYET_THAY: 'Duyệt thay',
  NGHIEM_THU_THAY: 'Nghiệm thu thay',
  CHECK_IN_THAY: 'Check-in KR thay',
};

export const DELEGATION_STATUS_LABEL: Record<DelegationStatus, string> = {
  SAP_HIEU_LUC: 'Sắp hiệu lực',
  HIEU_LUC: 'Đang hiệu lực',
  HET_HAN: 'Hết hạn',
  DA_THU_HOI: 'Đã thu hồi',
};

/** Mã lỗi nghiệp vụ của hợp đồng → văn bản MSG (WEB-058, chờ bổ sung vào TL-02a QT-01). */
export const ROLE_ADMIN_MESSAGES: Record<string, string> = {
  /** MSG-01-16 (BR-01-13) */
  'CONFIG-403-SELF-GRANT':
    'Đồng chí không thể tự cấp, tự duyệt hoặc tự thu hồi vai trò, uỷ quyền của chính mình.',
  /** MSG-01-18 (BR-01-17) */
  'CONFIG-422-REDELEGATION':
    'Người này đang nhận uỷ quyền của người khác nên không được uỷ quyền lại.',
  'CONFIG-422-VALIDITY': 'Vai trò và uỷ quyền phải có thời hạn, ngày kết thúc sau ngày bắt đầu.',
  'CONFIG-409-DUPLICATE-BINDING':
    'Người này đã có vai trò này tại đơn vị và phạm vi đã chọn trong thời gian còn hiệu lực.',
  'CONFIG-409-NOT-PENDING': 'Đề nghị này không còn chờ duyệt. Vui lòng tải lại.',
  'CONFIG-409-ALREADY-ENDED': 'Bản ghi này đã kết thúc. Vui lòng tải lại.',
};

/** MSG-01-17 (BR-01-14): vai trò nhạy cảm vừa cấp đang chờ duyệt thứ hai. */
export const MSG_01_17 = (role: string, person: string) =>
  `Đã gửi đề nghị cấp vai trò ${role} cho đồng chí ${person}. Vai trò chỉ có hiệu lực sau khi người duyệt thứ hai phê duyệt.`;
