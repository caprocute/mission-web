> Nguồn: Baseline mục 1 (căn cứ), mục 2 (giả định), mục 3 (NFR-01), mục 4 (ADR-01 đến ADR-31), mục 5.2 (thư viện), mục 9.1 (trạng thái), mục 13 (quy ước API); TL-01 mục bảo mật, mục API, Phụ lục A, B, C; TL-02 QT-00, QT-06, ma trận API; TL-03 (bảng `config.*`, `idempotency_key`); TL-04 mục 9.2 (SEC-16), mục cell, OPS-02; `_huong-dan-viet.md`; mã `frontend/` và `deploy/web/`; quyết định do người phụ trách dự án truyền đạt khi giao việc viết wiki.

# Quyết định và câu hỏi mở

Trang này ghi lại các quyết định kiến trúc ảnh hưởng tới phân hệ web, các quyết định riêng của dự án web đã chốt, và các điểm mà nguồn còn lệch nhau hoặc chưa trả lời. Mục đích là để người và AI agent không phải tự phát hiện lại những mâu thuẫn này, và biết cách xử lý tạm cho tới khi người phụ trách chốt. Nguyên tắc chung: khi wiki lệch với tài liệu thiết kế (TL), TL thắng; khi hai TL lệch nhau, giữ hành vi mà mã hiện tại đang có, ghi giả định vào báo cáo task và nêu câu hỏi cho người phụ trách.

## Quyết định kiến trúc liên quan tới web

Bảng dưới liệt kê các ADR của baseline có tác động trực tiếp tới mã giao diện, kèm hệ quả cho frontend. Nội dung đầy đủ của ADR nằm trong baseline mục 4 và TL-01 Phụ lục A.

| ADR | Nội dung | Hệ quả cho frontend |
|---|---|---|
| ADR-01 | Kiến trúc lai: Spring Modulith, một số microservice, triển khai theo cell | API gom dưới `/api/v1/<module>` của mission-core; shell biết cell của người dùng |
| ADR-04 | Ba tầng luồng: đồng bộ, sự kiện, saga | Thao tác dài trả 202 và theo dõi tác vụ; tiến độ qua SSE |
| ADR-05 | Bốn lớp tuỳ biến UI: token, SDUI, biểu mẫu động, plugin | Cấu trúc `@vt/design-system`, `@vt/sdui-renderer`, `@vt/form-engine`; không dùng WebAssembly |
| ADR-06 | Phân lớp dữ liệu C0 đến C3 | Không lưu C2, C3 ở trình duyệt; C3 đi luồng E2EE |
| ADR-08 | Keycloak 26 theo cell, Organizations, OIDC Code + PKCE, `state`, `nonce` | `@vt/auth` trên `angular-oauth2-oidc` |
| ADR-10 | Angular 21, Native Federation, PrimeNG | Toàn bộ workspace `frontend/`; React bị loại vì chi phí đào tạo lại |
| ADR-12 | OpenTelemetry và Grafana Faro | `@vt/telemetry`, `traceparent` mọi request |
| ADR-13 | Ceph RGW với Object Lock cho minh chứng | Tải tệp qua URL ký trước; dev dùng MinIO |
| ADR-16 | Jenkins, Argo CD, cosign, Kyverno, SBOM | Pipeline và ảnh web đã ký |
| ADR-18 | Valkey làm cache, không cache dữ liệu C2, C3 dạng rõ | Không dựa vào cache phía máy chủ cho dữ liệu nhạy cảm |
| ADR-19 | CEL và DMN cho luật nghiệp vụ | `cel-js` ở trình duyệt, cùng bộ test với `cel-java` |
| ADR-20 | Thay dần hệ cũ qua APISIX | Một số tuyến còn ở hệ cũ trong thời gian chuyển đổi |
| ADR-21 | OPA cùng pod, đánh giá từng phần, lỗi thì từ chối | Ẩn hiện theo vai trò ở client chỉ là trải nghiệm |
| ADR-25 | UI Pack khai dải Policy Pack tương thích | `UiPack` có tham chiếu Policy Pack; kiểm khi phát hành |
| ADR-27 | E2EE cho C3: ECDH P-256, khoá không trích xuất, Shamir 3/5 | Thư viện `@vt/e2ee` dự kiến |
| ADR-31 | Ký lô và niêm phong sổ cái | Màn hình ký lô, tra cứu sổ cái |

## Quyết định riêng của dự án web đã chốt

Ba quyết định sau đã được người phụ trách chốt và không mở lại trong các task. Thứ nhất, frontend dùng Angular 21 theo ADR-10, không dùng React; mọi đề xuất đổi framework hay thêm thư viện giao diện lớn đều trái quy tắc 1 của `AGENTS.md`. Thứ hai, giai đoạn đầu phát triển giao diện trên mock API (`useMockApi: true`), để giao diện không bị chặn bởi tiến độ backend; dữ liệu giả lập sẽ được tách thành interceptor và fixture theo module ở WEB-008, và lớp API phải giữ nguyên chữ ký khi chuyển sang API thật. Thứ ba, môi trường dev dùng MinIO thay cho Ceph RGW dù TL-01 loại MinIO khỏi production vì giấy phép AGPL; MinIO chỉ chạy ở dev nội bộ, không phân phối.

## Điểm lệch giữa các nguồn

Bảng dưới liệt kê các điểm mà hai hay nhiều nguồn ghi khác nhau, kèm cách xử lý tạm. Cột "Mã hiện tại" cho biết hành vi đang có trong `frontend/` để người làm task không vô tình đổi khi chưa có quyết định.

| Chủ đề | Nguồn ghi khác nhau | Mã hiện tại và cách xử lý tạm |
|---|---|---|
| Mã lỗi xung đột `If-Match` | Baseline mục 13, TL-02 và `AGENTS.md`: 409; TL-01: 412 | `toProblem` xử lý 409; nên xử lý cả 412 như xung đột |
| Thời hạn token | TL-01: access 15 phút, refresh 8 giờ, SSO 10 giờ; TL-02: 5 phút, refresh 30 phút trượt; TL-04 SEC-16: 5 phút, 8 giờ | Không gắn cứng con số; làm mới theo `expires_in` |
| Phương thức cập nhật tiến độ | TL-01: `PATCH`; TL-02 và mã: `POST /api/v1/tasks/{id}/progress` | Giữ `POST` |
| Đường dẫn việc của tôi | TL-02 SCR-0503: `/tasks?assignee=me`; ma trận TL-02: `/tasks/my`; mã: `/tasks/mine` | Giữ `/tasks/mine` tới khi có OpenAPI (WEB-007) |
| Thời gian giữ `Idempotency-Key` | TL-01: 24 giờ; TL-03: 48 giờ | Theo TL-03 vì là thiết kế bảng |
| Định dạng `Idempotency-Key` | TL-01: UUIDv7; mã: `crypto.randomUUID()` (UUID v4) | Cả hai đều duy nhất; cần chốt nếu máy chủ dựa vào thứ tự thời gian |
| Giữ khoá khi gửi lại | TL-01 yêu cầu gửi lại cùng khoá; `idempotencyInterceptor` sinh khoá mới mỗi request | Cần sửa để thao tác thử lại giữ khoá (WEB-007) |
| Phiên bản Formly | TL-01 Phụ lục B: ngx-formly 6.x; mã và `AGENTS.md`: Formly 10 | Giữ Formly 10 (bản tương thích Angular 21) |
| Thư viện trong baseline 5.2 | Baseline không liệt kê `@vt/api-client`, `@vt/e2ee` | Đã dùng `@vt/api-client`; cần bổ sung vào baseline |
| Phiên bản registry | TL-01 và mã: semver (`2.4.0`, `^2.4.0`); TL-02, TL-03: dạng lịch (`v2026.3`) | Giữ semver trong mã; cần chốt cách ánh xạ |
| Tên cột `config.ui_pack_version`, `config.form_schema` | TL-03 giữ hai bộ tên (`tokens` và `tokens_json`, `layouts` và `layout_json`, `form_code` và `form_key`, `json_schema` và `schema_json`) | Frontend không đọc bảng trực tiếp; theo hợp đồng API |
| Tên vai trò | TL-01 Phụ lục C dùng tên khác (ví dụ `staff`, `org_officer`, `policy_approver`); baseline và mã: 11 vai trò như `member` | Theo baseline và realm dev |
| Mã SEC trong chú thích mã | `session.service.ts` ghi SEC-15, SEC-54 cho `state` và `nonce`; TL-04: đúng là SEC-17 | Sửa chú thích khi làm WEB-006 |
| Kiểm thuộc tính SDUI | TL-01 nêu Ajv; mã tự viết bộ lọc vì CSP không có `'unsafe-eval'` | Giữ bộ lọc tự viết hoặc Ajv biên dịch trước lúc build |
| Đỉnh người dùng task cell lớn | Baseline bản cũ, TL-02: 80 đến 110 lượt/giây; TL-01, baseline 3.1: 133 đến 150 lượt/giây | Dùng số lớn hơn khi định cỡ thử tải |
| Tổng số tài khoản | NFR-01: 6 triệu; TL-04 định cỡ: 6,5 triệu; con số 6,1 triệu được nêu khi giao việc nhưng không thấy trong nguồn | Dùng 6,5 triệu cho định cỡ, 6 triệu khi trích NFR |

## Khoảng trống trong mã so với thiết kế

Các điểm sau không phải mâu thuẫn mà là phần thiết kế chưa được hiện thực, cần biết để không tưởng nhầm là đã có. `UiPackLoader` chưa kiểm chữ ký UI Pack (`TODO(ADR-05)`, task WEB-009) và chưa đọc manifest plugin; chưa gọi API phân giải registry phía máy chủ. Chưa có Faro và OTel web (WEB-011), chưa có chế độ nhúng `<vt-okr-app>` (WEB-013). Chưa có CSP, HSTS, Trusted Types trong `nginx.conf` (WEB-014). Ngân sách trong `angular.json` còn mặc định 500 kB và 1 MB thay vì 250 KB và 180 KB gzip (WEB-003). Chưa có luồng chọn phân công, xử lý 401 step-up (WEB-006). Tuỳ chọn chế độ người cao tuổi lưu trong `localStorage` thay vì cấu hình theo người dùng ở máy chủ. `angular-oauth2-oidc` có thể đang lưu token vào `sessionStorage` (xem [trang 07](07-xac-thuc-phan-quyen.md)).

## Câu hỏi mở cần người phụ trách trả lời

Câu hỏi một là biểu mẫu MRC: baseline mục 2 ghi giả định rằng không tìm thấy mẫu MRC trong tài liệu đầu vào, nên nội dung và vị trí của biểu mẫu này trong các quy trình chưa được đặc tả; cần cung cấp mẫu hoặc xác nhận bỏ.

Câu hỏi hai là căn cứ pháp lý "Thông tư 22": phạm vi dự án được mô tả là "Thông tư 22 và QĐ 366", nhưng hướng dẫn viết của bộ TL ghi căn cứ thực là QĐ 366-QĐ/TW, Hướng dẫn 02, dự thảo Quy định mới, Nghị định 335/2025 và Nghị định 233/2026; nguồn không có văn bản nào tên "Thông tư 22". Cần xác nhận "Thông tư 22" chỉ văn bản nào, hay chỉ là cách gọi tắt.

Câu hỏi ba là cell thí điểm: TL-04 đề xuất `CELL-NB` (tenant là các đơn vị nội bộ dùng thử) phát hành trước `CELL-G4`, nhưng mã này chưa có trong baseline. Cần quyết định có thêm cell này không, vì nó ảnh hưởng tới danh sách giá trị `cell` trong cấu hình chạy và thứ tự phát hành.

Câu hỏi bốn là tổng số tài khoản dùng cho định cỡ: 6 triệu, 6,1 triệu hay 6,5 triệu (xem bảng trên).

Câu hỏi năm là chốt các điểm lệch kỹ thuật trong bảng trên, ưu tiên mã lỗi xung đột, thời hạn token, đường dẫn việc của tôi và cách giữ `Idempotency-Key` khi thử lại, vì chúng ảnh hưởng trực tiếp tới `@vt/api-client` và `@vt/auth` ở GĐ0.

## Liên quan

- [README của wiki](README.md)
- [Tổng quan hệ thống](01-tong-quan-he-thong.md)
- [Quy ước API](06-quy-uoc-api.md)
- [Xác thực và phân quyền](07-xac-thuc-phan-quyen.md)
- [Bảo mật frontend](08-bao-mat-frontend.md)
- [Hạ tầng dev và triển khai](12-ha-tang-dev-va-trien-khai.md)
