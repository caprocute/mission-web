/** Khai báo kiểu cho `rules.mjs`, để kiểm thử e2e TypeScript nhập được bộ quy tắc nội dung giao diện. */
export interface UiTextRule {
  id: string;
  re: RegExp;
  hint: string;
  /** Ngoài HTML, chỉ áp cho chuỗi có từ hai từ trở lên. */
  sentenceOnly?: boolean;
}

export declare const RULES: UiTextRule[];
