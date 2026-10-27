import type { components } from '../generated/org';

/**
 * Quản trị tài khoản người dùng ngay trong ứng dụng (QT-01, SCR-0108, SCR-0109, BS-04): bí danh của
 * lược đồ trong `contracts/openapi/modules/org.yaml`. Nguồn dữ liệu là bảng bổ sung
 * `org.user_account` cùng `org.person`, `org.assignment` và người dùng Keycloak.
 */
type S = components['schemas'];

export type UserAccount = S['UserAccount'];
export type UserAccountStatus = S['UserAccountStatus'];
export type UserAccountPage = S['UserAccountPage'];
export type UserAccountCreate = S['UserAccountCreate'];
export type UserAccountCreated = S['UserAccountCreated'];
export type UserAccountUpdate = S['UserAccountUpdate'];
export type PasswordResetResult = S['PasswordResetResult'];
export type JobTitle = S['JobTitle'];
export type PersonAssignment = S['Assignment'];
export type OrgAssignmentCreateInput = S['OrgAssignmentCreateInput'];
export type OrgAssignmentEndRequest = S['OrgAssignmentEndRequest'];

export const USER_ACCOUNT_STATUS_LABEL: Record<UserAccountStatus, string> = {
  HOAT_DONG: 'Đang hoạt động',
  KHOA: 'Đã khoá',
};

export const ASSIGNMENT_KIND_LABEL: Record<string, string> = {
  CHINH: 'Chức vụ chính',
  KIEM_NHIEM: 'Kiêm nhiệm',
  DIEU_DONG: 'Điều động',
  BIET_PHAI: 'Biệt phái',
  TAM_GIU: 'Tạm giữ',
};

/** Mã lỗi nghiệp vụ của quản trị tài khoản → văn bản hiển thị (BS-04). */
export const USER_ADMIN_MESSAGES: Record<string, string> = {
  'ORG-409-USERNAME-TAKEN': 'Tên đăng nhập này đã có người dùng. Vui lòng chọn tên khác.',
  'ORG-422-SELF-LOCK': 'Đồng chí không thể tự khoá tài khoản của chính mình.',
  'ORG-409-ALREADY-CREATED':
    'Tài khoản đã được tạo ở lần gửi trước. Mật khẩu tạm không hiện lại; hãy đặt lại mật khẩu nếu cần.',
  'ORG-409-ALREADY-RESET':
    'Mật khẩu đã được đặt lại ở lần gửi trước. Hãy đặt lại lần nữa để nhận mật khẩu tạm mới.',
  'ORG-409-ASSIGNMENT-OVERLAP': 'Người này đã có phân công trùng thời gian tại đơn vị đã chọn.',
};
