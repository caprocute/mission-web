import type { FormlyJsonschema } from '@ngx-formly/core/json-schema';

/**
 * Kiểu JSON Schema (draft 7) mà Formly nhận, lấy từ chữ ký `FormlyJsonschema.toFieldConfig`.
 * Không import trực tiếp từ `json-schema`: đó chỉ là gói kiểu `@types/json-schema`, không có gói
 * chạy, nên Native Federation tìm gói này khi lọc thư viện dùng chung và in cảnh báo
 * "No meta data found for shared lib json-schema" (WEB-052).
 */
export type JSONSchema7 = Parameters<FormlyJsonschema['toFieldConfig']>[0];
