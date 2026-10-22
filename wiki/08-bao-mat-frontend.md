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
| `trusted-types` | `angular angular#bundler vt-federation` (mã hiện thực thêm `native-federation es-module-shims`, xem dưới) |

Ba hệ quả trực tiếp cho người viết mã. Thứ nhất, không có `'unsafe-eval'` nên mọi thư viện sinh mã lúc chạy bị chặn; đây là lý do `@vt/sdui-renderer` không dùng Ajv ở chế độ mặc định mà tự viết bộ lọc thuộc tính trong `props-validator.ts`, và `cel-js` được chọn vì thông dịch chứ không biên dịch thành hàm. Thứ hai, không có `'unsafe-inline'` cho style nên CSS động phải mang nonce; `ThemeService` tạo thẻ `style#vt-tenant-tokens` có thuộc tính nonce lấy từ token `CSP_NONCE` và gán nội dung bằng `textContent`, không bằng `innerHTML`. Thứ ba, Trusted Types chỉ cho phép ba chính sách có tên `angular`, `angular#bundler` và `vt-federation`; mã nạp remote của Native Federation phải đi qua chính sách `vt-federation` (suy luận về vai trò của tên chính sách này, TL-01 chỉ liệt kê tên).

## Năm lớp chống XSS

TL-01 mô tả chống XSS (SEC-19) bằng năm lớp chồng nhau. Lớp một là cơ chế tự làm sạch của Angular khi gắn dữ liệu vào template. Lớp hai là cấm `bypassSecurityTrust*` trong mã ứng dụng, chỉ cho phép trong thư viện đã duyệt, được kiểm bằng luật Semgrep chặn merge (SEC-39). Lớp ba là CSP nghiêm như trên. Lớp bốn là Trusted Types, khiến mọi phép gán vào điểm nguy hiểm của DOM mà không qua chính sách đều lỗi. Lớp năm là làm sạch phía máy chủ đối với nội dung giàu định dạng trước khi lưu.

`AGENTS.md` biến các lớp này thành quy tắc cho người và AI agent viết mã: không dùng `innerHTML`, `bypassSecurityTrust*`, `eval`, `new Function` hay Ajv chế độ mặc định. Thuộc tính của widget SDUI lấy từ UI Pack cũng là dữ liệu không tin cậy: bộ lọc loại bỏ trường không khai báo trong `propsSchema` và chặn giá trị bắt đầu bằng `javascript:` hoặc `data:` (xem [Giao diện theo tenant](03-giao-dien-theo-tenant.md)).

## Header bảo mật HTTP và CORS

SEC-24 yêu cầu header bảo mật HTTP. TL-01 liệt kê `Strict-Transport-Security`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`, `Permissions-Policy` và `Cross-Origin-Opener-Policy: same-origin`, cùng CSP ở trên. SEC-06 yêu cầu CORS theo danh sách cho phép; TL-01 cho phép nguồn theo từng tenant thay vì ký tự đại diện. Vì token nằm trong bộ nhớ và không dùng cookie cho API, CSRF không áp dụng cho API; SEC-08 vẫn yêu cầu cookie (của Keycloak) đặt thuộc tính an toàn.

Hiện thực của WEB-014 (22/10/2026) đặt toàn bộ header ở nginx của SVC-17. Tệp `deploy/web/security-headers.conf` chứa `X-Content-Type-Options`, `Referrer-Policy`, `Permissions-Policy` (camera, vị trí, thanh toán, USB bị tắt; micro chỉ cùng nguồn) và `Strict-Transport-Security`; tệp này được `include` ở mức server và lại ở từng `location` có `add_header` riêng, vì nginx bỏ header kế thừa khi `location` tự đặt header. HSTS chỉ gửi khi yêu cầu đến bằng HTTPS (trực tiếp hoặc qua `X-Forwarded-Proto: https`) để môi trường dev chạy HTTP không bị khoá. CSP và `Cross-Origin-Opener-Policy: same-origin` đặt ở `location = /index.html`; mọi tuyến của ứng dụng đều rơi về tệp này qua `try_files`, và `index.html` trả `no-store` để nonce không bị lưu đệm.

Nonce theo từng yêu cầu dùng `$request_id` của nginx. `index.html` mang `<vt-root ngCspNonce="__CSP_NONCE__">`; Angular CLI thấy thuộc tính này nên gắn `nonce="__CSP_NONCE__"` vào mọi thẻ script và style do nó sinh ra, và `sub_filter` của nginx thay chuỗi giữ chỗ bằng nonce thật. Angular đọc thuộc tính `ngCspNonce` để cấp token `CSP_NONCE` (dùng bởi `ThemeService`), còn `provideDesignSystem` đọc cùng thuộc tính để truyền `csp.nonce` cho PrimeNG, vì PrimeNG tự chèn thẻ `<style>` cho từng component. `'strict-dynamic'` cho phép các module mà script có nonce nạp tiếp (shell, remote, chunk lười) mà không phải liệt kê từng tệp.

Nguồn ngoài của CSP sinh lúc container khởi động. `40-runtime-config.sh` lấy origin của `API_BASE_URL`, `AUTH_ISSUER`, `UI_PACK_BASE_URL` và URL từng remote, cộng thêm `CSP_CONNECT_SRC` và `CSP_FRAME_ANCESTORS` (danh sách cách nhau bằng dấu cách), rồi ghi bốn biến nginx vào `$CSP_DIR/csp-nguon.conf` (mặc định `/tmp/runtime`). Chỉ origin dạng `http(s)://` hoặc `ws(s)://` gồm chữ, số, dấu chấm, gạch và cổng được chấp nhận, nên giá trị biến môi trường không thể chèn chỉ thị CSP khác. Khi không có tệp, chính sách chỉ cho phép cùng nguồn. Nguồn IdP được thêm vào `frame-src` và `form-action` cho luồng làm mới phiên ẩn và đăng xuất.

Ba bẫy đã gặp khi bật Trusted Types. Native Federation 21 tạo chính sách tên `native-federation`, không phải `vt-federation` như TL-01 ghi, nên danh sách `trusted-types` giữ tên của TL-01 và thêm tên thật (ghi ở trang 14). es-module-shims 1.10 không hỗ trợ Trusted Types (gán `src` blob trực tiếp và dựng iframe `srcdoc`), khiến trang trắng; bản 2.8 tạo chính sách `es-module-shims`, nên phụ thuộc này được nâng lên `^2.8.4`. PrimeNG vi phạm `style-src` nếu không được cấp nonce như trên. Đã kiểm bằng nginx thật và Playwright trên 12 tuyến của shell: không vi phạm CSP, không lỗi console. Tệp tĩnh (js, css, phông, ảnh) vẫn trả `Access-Control-Allow-Origin: *` để shell ở nguồn khác nạp được remote; tài nguyên tĩnh không chứa dữ liệu nên điều này chấp nhận được, nhưng API tuyệt đối không được dùng ký tự đại diện.

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

## Bảo vệ của renderer SDUI (WEB-009)

Renderer SDUI là lớp hai của ADR-05 và có bốn chốt chặn ở trình duyệt. Chốt một là chữ ký và phiên bản của UI Pack, mô tả ở [trang 03](03-giao-dien-theo-tenant.md). Chốt hai là registry: nút có `component` không đăng ký không bao giờ được dựng. Chốt ba là `PropsValidator`: thuộc tính không có trong `propsSchema`, sai kiểu, ngoài giới hạn hoặc chứa URL `javascript:`, `data:`, `vbscript:` (kể cả khi chèn khoảng trắng, ký tự điều khiển, hoặc lồng trong mảng và đối tượng) bị loại trước khi gán vào component. Chốt bốn là giới hạn chi phí CEL: biểu thức `visibleWhen` dài quá 512 ký tự, lồng ngoặc quá 12 mức hoặc có quá 2 macro lặp (`all`, `exists`, `exists_one`, `map`, `filter`) bị coi là ẩn mà không phân tích, để một gói lỗi không làm treo trang.

Mỗi lần chốt chặn hoạt động, `SduiTelemetry` ghi một sự kiện (`sdui.component.unknown`, `sdui.prop.dropped`, `sdui.cel.invalid`, `sdui.cel.error`, `sdui.cel.limit`, `ui-pack.signature.missing`, `ui-pack.signature.invalid`, `ui-pack.registry.incompatible`) chỉ kèm tên component, tên thuộc tính, lý do và phiên bản, không kèm giá trị. Sự kiện trùng chỉ ghi một lần và tối đa 200 sự kiện khác nhau mỗi phiên. Sự kiện ra console và tới token `SDUI_TELEMETRY_SINK` nếu shell cung cấp; khi WEB-011 đưa Grafana Faro vào, shell chỉ cần cung cấp token này.

## Liên quan

- [Xác thực và phân quyền](07-xac-thuc-phan-quyen.md)
- [Giao diện theo tenant](03-giao-dien-theo-tenant.md)
- [Quy ước API](06-quy-uoc-api.md)
- [Kiểm thử và chất lượng](11-kiem-thu-va-chat-luong.md)
- [Hạ tầng dev và triển khai](12-ha-tang-dev-va-trien-khai.md)
- [Quyết định và câu hỏi mở](14-quyet-dinh-va-cau-hoi-mo.md)
