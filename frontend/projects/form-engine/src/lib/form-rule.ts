/** Quy tắc kiểm tra liên trường viết bằng CEL trong Policy Pack (TL-01 "Biểu mẫu động"). */
export interface FormRule {
  id: string;
  when: string;
  assert: string;
  message: string;
  /** Quy tắc dùng hàm chưa có trên trình duyệt: chỉ máy chủ kiểm tra khi gửi. */
  serverOnly?: boolean;
}

export interface FormUi {
  sections?: { title: string; fields: string[] }[];
  widgets?: Record<string, string>;
}

export interface RuleViolation {
  ruleId: string;
  message: string;
}
