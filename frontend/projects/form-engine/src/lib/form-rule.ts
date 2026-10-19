/** Quy tắc kiểm tra liên trường viết bằng CEL trong Policy Pack (TL-01 "Biểu mẫu động"). */
export interface FormRule {
  id: string;
  /** Điều kiện áp dụng (CEL, trả bool); `"true"` khi luôn áp dụng. */
  when: string;
  /** Điều kiện phải đúng (CEL, trả bool). */
  assert: string;
  message: string;
  /**
   * Trường nhận thông báo khi vi phạm (đường dẫn theo khoá, ví dụ `giaiTrinh` hoặc
   * `tieuChiChung.tc1`). Không có thì thông báo chỉ hiện ở danh sách lỗi chung của biểu mẫu.
   */
  field?: string;
  /** Quy tắc dùng hàm chưa có trên trình duyệt: chỉ máy chủ kiểm tra khi gửi. */
  serverOnly?: boolean;
}

/** Tên widget của UI Pack (TL-01 "Cấu trúc UI Pack", phần `forms.*.ui.widgets`). */
export type VtWidget =
  | 'vt.input.text'
  | 'vt.input.score'
  | 'vt.input.textarea'
  | 'vt.input.date'
  | 'vt.input.catalog'
  | 'vt.input.checkbox';

/** Phần `ui` của một biểu mẫu trong UI Pack: nhóm trường và widget theo tên trường. */
export interface FormUi {
  sections?: { title: string; fields: string[] }[];
  widgets?: Record<string, VtWidget | string>;
}

export interface RuleViolation {
  ruleId: string;
  message: string;
  field?: string;
}

/** Lỗi theo trường máy chủ trả trong Problem Details (`errors[]`, RFC 9457 mở rộng). */
export interface ServerFieldError {
  field: string;
  message: string;
}
