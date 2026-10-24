/** Thông báo của QT-00 (TL-02 SCR-0001, SCR-0002) mà giao diện web hiển thị. */
export const MSG_00_05 = (role: string) =>
  `Vai trò ${role} yêu cầu xác thực nhiều lớp. Vui lòng nhập mã từ ứng dụng SmartOTP.`;
export const MSG_00_06 =
  'Tài khoản chưa có phân công công tác còn hiệu lực. Vui lòng liên hệ quản trị của đơn vị.';
export const MSG_00_07 = (tenant: string) => `Tổ chức ${tenant} đang tạm ngừng sử dụng nền tảng.`;

/**
 * Lỗi 403 do tenant tạm ngừng (MSG-00-07): theo mã lỗi, hoặc theo `tenantStatus` mà bộ lọc định tuyến của
 * dịch vụ gắn vào lỗi 403 chung khi chặn lệnh ghi của tenant `TAM_NGUNG` (TL-04 mục 11.4).
 */
export function isTenantSuspended(p: { code?: string | null; [k: string]: unknown }): boolean {
  return /TENANT.*SUSPENDED|TAM_NGUNG/.test(p.code ?? '') || p['tenantStatus'] === 'TAM_NGUNG';
}

/** Văn bản không có mã MSG riêng trong TL-02 (đặt theo quy ước giao diện chung). */
export const TEXT_SESSION_EXPIRED =
  'Phiên làm việc đã hết hạn. Đồng chí đăng nhập lại để tiếp tục; dữ liệu đang nhập trên màn hình được giữ.';
export const TEXT_FORBIDDEN = 'Đồng chí không có quyền thực hiện thao tác này.';
export const TEXT_IDP_UNAVAILABLE =
  'Không kết nối được máy chủ đăng nhập. Vui lòng thử lại sau ít phút.';

/** Tên vai trò tiếng Việt để hiển thị (baseline mục 10, AC-01 đến AC-12). */
export const ROLE_LABEL: Record<string, string> = {
  platform_admin: 'Quản trị nền tảng',
  tenant_admin: 'Quản trị đơn vị',
  policy_author: 'Quản trị nghiệp vụ',
  leader: 'Lãnh đạo đơn vị',
  manager: 'Cán bộ quản lý trực tiếp',
  member: 'Cán bộ, công chức, viên chức',
  appraiser: 'Cán bộ thẩm định',
  decider: 'Người có thẩm quyền quyết định',
  second_approver: 'Người duyệt thứ hai',
  inspector: 'Cán bộ kiểm tra, giám sát',
  ai_readonly: 'Trợ lý AI',
};
