> Nguồn: TL-01 mục bảo mật (CSP, Trusted Types, năm lớp chống XSS, header HTTP, CORS, E2EE C3, PDF.js, lỗ hổng S01 đến S13 của hệ cũ); Baseline mục 4 (ADR-27), mục 10 (C0 đến C3); TL-03 (lưu cục bộ ở web); TL-04 mục 9.2 (SEC-06, SEC-07, SEC-08, SEC-19, SEC-24, SEC-34, SEC-39, SEC-40, SEC-47, SEC-48, SEC-53); `AGENTS.md` quy tắc an toàn giao diện; mã `deploy/web/nginx.conf`, `@vt/design-system/theme.service.ts`, `@vt/sdui-renderer/props-validator.ts`.

# Bảo mật frontend

Trang này tập hợp các yêu cầu bảo mật mà mã giao diện phải tuân theo, giải thích vì sao từng yêu cầu tồn tại và đối chiếu với hiện trạng mã. Hệ thống cũ có các lỗ hổng như JWT nằm trong URL (S11) và XSS (S13); phân hệ web mới được thiết kế để các lớp lỗi đó không thể tái diễn ngay cả khi một lớp phòng thủ bị bỏ sót. Phần xác thực và token nằm ở [Xác thực và phân quyền](07-xac-thuc-phan-quyen.md).

## Chính sách bảo mật nội dung (CSP)

TL-01 quy định CSP nghiêm (SEC-19) cho mọi trang của shell. Bảng dưới chép các chỉ thị; tên miền cụ thể được thay bằng chỗ giữ chỗ trong ngoặc nhọn vì wiki không ghi địa chỉ máy chủ.

| Chỉ thị | Giá trị |
|---|---|
| `default-src` | `'self'` |
| `script-src` | `'self'` {CDN tĩnh} `'strict-dynamic'` `'nonce-{per-request}'` |
| `style-src` | `'self'` {CDN tĩnh} `'nonce-{per-request}'` |
| `img-src` | `'self'` {CDN tĩnh} `data:` `blob:` |
| `connect-src` | `'self'` {API của nền tảng} {IdP của nền tảng} |
| `font-src` | `'self'` {CDN tĩnh} |
| `frame-ancestors` | `'self'` {vOffice} {tenant-portal-origins} |
| `object-src` | `'none'` |
| `base-uri` | `'self'` |
| `form-action` | `'self'` |
| `require-trusted-types-for` | `'script'` |
| `trusted-types` | `angular angular#bundler vt-federation` |

Ba hệ quả trực tiếp cho người viết mã. Thứ nhất, không có `'unsafe-eval'` nên mọi thư viện sinh mã lúc chạy bị chặn; đây là lý do `@vt/sdui-renderer` không dùng Ajv ở chế độ mặc định mà tự viết bộ lọc thuộc tính trong `props-validator.ts`, và `cel-js` được chọn vì thông dịch chứ không biên dịch thành hàm. Thứ hai, không có `'unsafe-inline'` cho style nên CSS động phải mang nonce; `ThemeService` tạo thẻ `style#vt-tenant-tokens` có thuộc tính nonce lấy từ token `CSP_NONCE` và gán nội dung bằng `textContent`, không bằng `innerHTML`. Thứ ba, Trusted Types chỉ cho phép ba chính sách có tên `angular`, `angular#bundler` và `vt-federation`; mã nạp remote của Native Federation phải đi qua chính sách `vt-federation` (suy luận về vai trò của tên chính sách này, TL-01 chỉ liệt kê tên).

## Năm lớp chống XSS

TL-01 mô tả chống XSS (SEC-19) bằng năm lớp chồng nhau. Lớp một là cơ chế tự làm sạch của Angular khi gắn dữ liệu vào template. Lớp hai là cấm `bypassSecurityTrust*` trong mã ứng dụng, chỉ cho phép trong thư viện đã duyệt, được kiểm bằng luật Semgrep chặn merge (SEC-39). Lớp ba là CSP nghiêm như trên. Lớp bốn là Trusted Types, khiến mọi phép gán vào điểm nguy hiểm của DOM mà không qua chính sách đều lỗi. Lớp năm là làm sạch phía máy chủ đối với nội dung giàu định dạng trước khi lưu.

`AGENTS.md` biến các lớp này thành quy tắc cho người và AI agent viết mã: không dùng `innerHTML`, `bypassSecurityTrust*`, `eval`, `new Function` hay Ajv chế độ mặc định. Thuộc tính của widget SDUI lấy từ UI Pack cũng là dữ liệu không tin cậy: bộ lọc loại bỏ trường không khai báo trong `propsSchema` và chặn giá trị bắt đầu bằng `javascript:` hoặc `data:` (xem [Giao diện theo tenant](03-giao-dien-theo-tenant.md)).

## Header bảo mật HTTP và CORS

SEC-24 yêu cầu header bảo mật HTTP. TL-01 liệt kê `Strict-Transport-Security`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`, `Permissions-Policy` và `Cross-Origin-Opener-Policy: same-origin`, cùng CSP ở trên. SEC-06 yêu cầu CORS theo danh sách cho phép; TL-01 cho phép nguồn theo từng tenant thay vì ký tự đại diện. Vì token nằm trong bộ nhớ và không dùng cookie cho API, CSRF không áp dụng cho API; SEC-08 vẫn yêu cầu cookie (của Keycloak) đặt thuộc tính an toàn.

Hiện trạng `deploy/web/nginx.conf` mới đặt `X-Content-Type-Options: nosniff`, `Referrer-Policy` và `server_tokens off`, chưa có CSP, HSTS, `Permissions-Policy` và COOP. Tệp tĩnh (js, css, phông, ảnh) đang trả `Access-Control-Allow-Origin: *` để shell ở nguồn khác nạp được remote; tài nguyên tĩnh không chứa dữ liệu nên điều này chấp nhận được, nhưng API tuyệt đối không được dùng ký tự đại diện. Có thể header bảo mật sẽ đặt ở APISIX hoặc CDN thay vì nginx (suy luận, nguồn chưa chỉ định nơi đặt); dù đặt ở đâu, nonce theo từng request đòi hỏi `index.html` được sinh hoặc thay nonce mỗi lần trả về, việc mà cấu hình nginx tĩnh hiện tại chưa làm.

## Phân lớp dữ liệu và lưu trữ cục bộ

Baseline mục 10 chia dữ liệu thành bốn lớp: C0 công khai, C1 nội bộ, C2 nhạy cảm (thông tin cá nhân, kết quả đánh giá chưa công bố), C3 mật. Quy tắc cho giao diện web theo TL-03 là chỉ lưu UI Pack và bản dịch trong IndexedDB theo ETag, không lưu dữ liệu C2 hay C3 ở trình duyệt. Mã hiện tại tuân theo: `PackCache` chỉ lưu UI Pack, `ThemeService` chỉ lưu tuỳ chọn hiển thị vào `localStorage`. Không được thêm bộ nhớ đệm cho danh sách nhiệm vụ, hồ sơ đánh giá hay kết quả xếp loại vào IndexedDB, `localStorage` hay `sessionStorage`.

SEC-07 và SEC-53 yêu cầu không ghi token và che thông tin cá nhân trong log. Ở frontend, điều này có nghĩa là không đưa token, nội dung biểu mẫu hay dữ liệu C2 vào `console`, vào thuộc tính span hay sự kiện Faro; TL-01 cũng quy định Faro không thu nội dung người dùng nhập (xem [Hiệu năng và trải nghiệm](09-hieu-nang-va-trai-nghiem.md)).

Bản nháp khi cắt tải (WEB-073): TL-04 yêu cầu "giữ bản nháp cục bộ", trong khi AGENTS.md cấm lưu dữ liệu C2 dạng rõ ở trình duyệt. Hai yêu cầu này được dung hoà bằng `DraftKeeper` của `@vt/api-client`. Dịch vụ này giữ nội dung đang soạn trong bộ nhớ của phiên, theo khoá của từng biểu mẫu, và không ghi xuống `localStorage` hay IndexedDB. Hệ quả là bản nháp mất khi người dùng tải lại trang. Việc lưu IndexedDB cho các trường C0 và C1 theo `x-classification` chưa làm; đây là phương án suy luận, chờ chốt.

## Mã hoá đầu cuối cho dữ liệu C3 (ADR-27)

ADR-27 quy định dữ liệu C3 được mã hoá đầu cuối (E2EE) ngay trong trình duyệt, máy chủ chỉ giữ bản mã. TL-01 mô tả cơ chế: mỗi tài liệu có khoá nội dung (CEK) AES-256-GCM; CEK được bọc cho từng người nhận bằng ECDH-ES trên đường cong P-256 kết hợp HKDF-SHA256 và AES-KW; khoá riêng của người dùng là `CryptoKey` tạo với `extractable: false` và lưu trong IndexedDB, nên mã JavaScript cũng không đọc được giá trị khoá. Khôi phục khoá dùng chia sẻ bí mật Shamir 3 trên 5. Tài liệu C3 được giải mã và hiển thị bằng PDF.js trong `iframe` có `sandbox`, không tải về dạng rõ. TL-01 ước tính C3 chiếm dưới 1% nhiệm vụ.

Thư viện dự kiến là `@vt/e2ee`, chưa có trong mã và chưa có trong danh sách thư viện của baseline mục 5.2. Đây là ngoại lệ duy nhất cho phép lưu khoá ở IndexedDB, và chỉ lưu khoá không trích xuất được, không lưu nội dung giải mã.

## Tải tệp và minh chứng

Tệp minh chứng được tải lên qua URL ký trước (presigned) tới kho đối tượng, không đi qua mission-core. Trình duyệt tính SHA-256 của tệp trước khi tải để máy chủ đối chiếu toàn vẹn (xem QT-07 ở [trang 05a](05a-quy-trinh-va-man-hinh-qt00-08.md)). SEC-47 yêu cầu quét mã độc bằng ClamAV và SEC-48 giới hạn kích thước, kiểu tệp; giao diện kiểm sớm để báo lỗi nhanh nhưng máy chủ vẫn là nơi quyết định.

## Plugin và chuỗi cung ứng

Plugin của tenant (lớp bốn của ADR-05) là remote Native Federation do bên thứ ba viết, nên phải được ký, quét và duyệt trước khi khai trong UI Pack; `UiPackLoader` hiện chưa kiểm chữ ký (`TODO(ADR-05)` trong mã), vì vậy chưa được bật plugin ở môi trường có dữ liệu thật (suy luận từ TODO). Chuỗi cung ứng của chính frontend theo SEC-37, SEC-38 và SEC-55: ảnh container ký bằng cosign, đạt SLSA mức 3, tham chiếu theo digest; ảnh nền distroless chạy không quyền root (SEC-49), ảnh web hiện dùng `nginx-unprivileged` với `USER 101`. Kiểm thử bảo mật động bằng ZAP thuộc SEC-40, tường lửa ứng dụng Coraza thuộc SEC-34.

## Bảng kiểm khi viết mã giao diện

Trước khi mở PR, người viết tự hỏi: mã có gắn HTML thô hay gọi `bypassSecurityTrust*` không; có thư viện nào cần `eval` không; có ghi token, PII hay nội dung biểu mẫu ra log, URL hay bộ nhớ trình duyệt không; mọi request API có đi qua `@vt/api-client` và các interceptor không; thuộc tính đến từ UI Pack có được lọc qua `propsSchema` không; quyết định quyền có được để máy chủ thực hiện không. Mỗi câu trả lời "có" cho các câu đầu là lý do chặn merge.

## Liên quan

- [Xác thực và phân quyền](07-xac-thuc-phan-quyen.md)
- [Giao diện theo tenant](03-giao-dien-theo-tenant.md)
- [Quy ước API](06-quy-uoc-api.md)
- [Kiểm thử và chất lượng](11-kiem-thu-va-chat-luong.md)
- [Hạ tầng dev và triển khai](12-ha-tang-dev-va-trien-khai.md)
- [Quyết định và câu hỏi mở](14-quyet-dinh-va-cau-hoi-mo.md)
