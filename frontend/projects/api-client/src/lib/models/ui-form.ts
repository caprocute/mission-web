import type { components } from '../generated/config';

/**
 * Phiên bản biểu mẫu động (`config.form_schema`, MOD-08): JSON Schema dữ liệu của Policy Pack
 * (`schemaJson`), phần trình bày của UI Pack (`uiJson`) và quy tắc CEL (`celRules`) để `<vt-form>` dựng
 * phiếu, ví dụ Mẫu 1B của phiếu tự đánh giá.
 */
export type UiFormVersion = components['schemas']['UiFormVersion'];

/** Một quy tắc CEL của biểu mẫu; `serverOnly` chỉ chạy ở máy chủ. */
export type UiFormCelRule = NonNullable<UiFormVersion['celRules']>[number];
