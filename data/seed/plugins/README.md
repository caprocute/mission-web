# Danh mục plugin chuyên ngành đã duyệt (CORE-08-15)

> Nguồn: TL-01 G1 "Lớp 4: Micro-frontend Plugins", "Cấu trúc UI Pack" (phần `plugins`), "Bảo mật: chống XSS và CSP"; TL-02b QT-18 BR-18-12; ADR-05 lớp 4, ADR-16; hợp đồng `contracts/openapi/modules/config.yaml` (`UiPackRuntime.plugins`, `listUiComponents`).

TL-03 không có bảng riêng cho plugin, nên danh mục plugin mà nền tảng đã duyệt được khai báo bằng tệp `catalog.json` có phiên bản (`catalogVersion`). mission-core đọc tệp khi đặt `mission.config.ui-pack.plugin-catalog` trỏ tới tệp này; không đặt thì danh mục rỗng và mọi plugin đều bị chặn.

Mỗi mục là một bản dựng plugin đã qua chuỗi cung ứng của đội ngành:

| Khoá | Ý nghĩa |
|---|---|
| `name`, `displayName`, `version` | Tên kỹ thuật, tên hiển thị cho quản trị tenant, phiên bản bản dựng |
| `remoteEntry` | Đường dẫn `remoteEntry.json` của Native Federation; đường dẫn tương đối cùng nguồn, hoặc `https` thuộc nguồn khai báo ở `mission.config.ui-pack.plugin-origins` (khớp CSP `script-src`) |
| `integrity` | Mã toàn vẹn SRI SHA-384 (`sha384-<base64>`) của bản dựng, shell kiểm khi nạp |
| `signature` | Chữ ký cosign (`cosign sign-blob`, ECDSA P-256 SHA-256, DER base64) trên chuỗi `integrity` |
| `publicKey` | Khoá công khai cosign dạng PEM |
| `scan.status` | Kết quả quét bảo mật như một service; chỉ `DAT` được dùng |
| `widgets[]` | Widget SDUI plugin cung cấp: `name`, `propsSchema`, `allowedClassification`, `since`, `mobile`, tuỳ chọn `registry` (phiên bản registry áp dụng; bỏ trống là mọi phiên bản) |

Tenant bật plugin bằng cấu hình tenant `ui.plugins` (lược đồ `json.string-array`), mỗi phần tử là `tên` hoặc `tên@phiên bản`. Khi gửi duyệt và khi phát hành UI Pack, máy chủ kiểm: plugin có trong danh mục, đã quét đạt, nguồn được phép, mã toàn vẹn đúng dạng và chữ ký kiểm được bằng khoá công khai. Plugin đạt được chốt vào manifest `plugins` của gói đã ký; widget của plugin đạt xuất hiện trong danh mục component của tenant.

Bản hiện tại là bản giả lập cho plugin giáo dục mẫu của WEB-051: mã toàn vẹn tính trên một chuỗi giữ chỗ, khoá ký sinh một lần và đã huỷ. Khi có bản dựng thật, đội ngành ký bằng khoá của mình trong CI rồi thay mục này.
