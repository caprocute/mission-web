/** Khai báo kiểu cho `rules.mjs`, để kiểm thử e2e TypeScript nhập được bộ quy tắc nội dung giao diện. */
export interface UiTextRule {
  id: string;
  re: RegExp;
  hint: string;
  /** Ngoài HTML, chỉ áp cho chuỗi có từ hai từ trở lên. */
  sentenceOnly?: boolean;
}

export declare const RULES: UiTextRule[];

/** Tiền tố mã thiết kế nội bộ, dùng chung với `INTERNAL_CODE_PREFIXES` của `@vt/api-client`. */
export declare const DESIGN_CODE_PREFIXES: string[];
