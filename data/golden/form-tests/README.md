# Bộ ca kiểm thử biểu mẫu động dùng chung (CORE-08-14)

> Nguồn: TL-01 G1 "Biểu mẫu động: Formly, JSON Schema và CEL"; TL-02b QT-18 BR-18-04, BR-18-05, BR-18-11, MSG-18-08; hợp đồng `contracts/openapi/modules/config.yaml` (`runUiFormTests`).

Mỗi tệp YAML trong thư mục này mô tả một biểu mẫu mẫu (lược đồ JSON Schema có `x-cel`, `x-classification` và danh sách `celRules` đúng dạng `UiFormVersionUpdate`) cùng các ca dữ liệu và kết quả mong đợi. Máy chủ chạy bộ ca bằng cel-java trong `FormGoldenSuiteTest` của mission-core; CI của `@vt/form-engine` chạy cùng tệp bằng cel-js. Hai bên phải cho cùng kết quả (BR-18-05), nên khi sửa một ca thì cả hai bộ chạy đều phải xanh.

## Định dạng

| Khoá | Ý nghĩa |
|---|---|
| `form` | Khoá biểu mẫu (chỉ để đọc) |
| `schema` | JSON Schema của dữ liệu phiếu |
| `celRules` | Quy tắc CEL theo trường: `field`, `expr` (biến `form`), `message` |
| `c2` | Các khoá dữ liệu lớp C2 mà máy chủ phải trả để mã hoá (BR-18-11) |
| `cases[].id` | Mã ca |
| `cases[].input` | Dữ liệu phiếu (dữ liệu giả, không dữ liệu thật) |
| `cases[].valid` | Phiếu hợp lệ hay không |
| `cases[].errors` | Đường dẫn chấm của các trường lỗi, không xét thứ tự |

Quy ước ngữ nghĩa mà cả hai engine phải theo: biến `form` là bảng phẳng khoá dữ liệu → giá trị; trường kiểu `number` luôn là số thực, `integer` là số nguyên; `x-cel` và quy tắc chỉ chạy khi trường có giá trị; biểu thức lỗi khi chạy coi như không đạt; chuỗi rỗng ở trường bắt buộc coi như thiếu; `pattern` so khớp một phần như JSON Schema.
