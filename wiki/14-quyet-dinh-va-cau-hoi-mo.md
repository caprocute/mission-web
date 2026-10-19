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
| Tên vai trò | TL-01 Phụ lục C dùng `staff`, `org_officer`, `policy_approver`; ví dụ Rego TL-01 dùng `LANH_DAO`; mục mở lại kỳ TL-01 nhắc `PERIOD_REOPEN_APPROVER`; baseline và mã: 11 vai trò như `member` | Theo baseline và realm dev; ánh xạ `staff` → `member`, `policy_approver` → `decider` theo SCR-1503, `org_officer` tách theo chức năng (xem [trang 15](15-phan-quyen.md)) |
| Vai trò thực hiện một số thao tác | TL-01 Phụ lục C: `org_officer` khởi động khoá kỳ, đề nghị mở lại kỳ, tạo hội nghị, đóng gói hồ sơ; `policy_approver` phát hành Policy Pack; `decider` cấp trên trả lời khiếu nại; `second_approver` là người duyệt duy nhất của mở lại kỳ. TL-02: AC-02 khởi chạy khoá kỳ (SCR-0801); AC-07, AC-10 tạo yêu cầu mở lại kỳ, AC-08 duyệt thứ nhất, AC-09 duyệt thứ hai (SCR-1301); AC-08 ký phê duyệt Policy Pack (SCR-1503); AC-07 xử lý, AC-08 ký trả lời khiếu nại (SCR-1202) | Ma trận [15a](15a-ma-tran-phan-quyen.md) theo TL-02 vì là đặc tả màn hình; cần chốt để OPA và giao diện khớp nhau |
| Vai trò bắt buộc AAL2 | BR-00-01: `appraiser`, `decider`, `second_approver`, `tenant_admin`, `platform_admin`; TL-01: thêm lãnh đạo, người phê duyệt, cán bộ tổ chức; TL-04 SEC-13: MFA với mọi người dùng | Giao diện không tự quyết; xử lý 401 step-up khi máy chủ yêu cầu; WEB-056 cấu hình theo BR-00-01 ở dev Realm dev (WEB-056) buộc OTP và cấp `acr=aal2` cho đúng năm vai trò của BR-00-01. |
| Cửa sổ step-up | BR-00-02: MFA trong vòng 15 phút trước thao tác; TL-01: `auth_time` không quá 5 phút | Không gắn cứng; xử lý 401 `insufficient_user_authentication` |
| Tên claim ngữ cảnh | TL-01: `tenant_id`, `cell`; TL-02 QT-00: `org`, `roles`; realm dev và `SessionService`: `tenant`, `unit_id`, vai trò ở `realm_access.roles` | WEB-056 gom các claim vào client scope `mission-context` nhưng **giữ tên** `tenant`, `unit_id` của mã; **chờ người phụ trách chốt** theo TL-01 hay TL-02, khi chốt chỉ cần đổi `claim.name` trong scope và `SessionService.readClaims()` |
| Đường dẫn quản lý tenant | TL-02 SCR-0101, SCR-0102: `/api/v1/config/tenants`; TL-01 Phụ lục C: `/registry/tenants` | Theo TL-02 tới khi có OpenAPI (WEB-007) |
| Mã SEC trong chú thích mã | `session.service.ts` ghi SEC-15, SEC-54 cho `state` và `nonce`; TL-04: đúng là SEC-17 | Sửa chú thích khi làm WEB-006 |
| Kiểm thuộc tính SDUI | TL-01 nêu Ajv; mã tự viết bộ lọc vì CSP không có `'unsafe-eval'` | Giữ bộ lọc tự viết hoặc Ajv biên dịch trước lúc build |
| Đỉnh người dùng task cell lớn | Baseline bản cũ, TL-02: 80 đến 110 lượt/giây; TL-01, baseline 3.1: 133 đến 150 lượt/giây | Dùng số lớn hơn khi định cỡ thử tải |
| Loại đề nghị điều chỉnh (`task.task_change_request.request_type`) | TL-02 SCR-0602: `GIA_HAN`, `DOI_SO_LUONG`, `DOI_SAN_PHAM`, `TAM_DUNG`, `HUY`; TL-03: `GIA_HAN`, `DIEU_CHINH_KHOI_LUONG`, `DIEU_CHINH_NOI_DUNG`, `HUY` (không có tạm dừng) | Theo TL-02 vì là đặc tả màn hình (`ChangeRequestType`, WEB-031); cần chốt khi có OpenAPI (WEB-007) |
| Trạng thái tenant và tổng tài khoản thiết kế (SCR-0101) | Mockup Hình 6.7 có nhãn "Thử nghiệm 1 quý" và thẻ "69% của 6 triệu"; Hình 6.6 và TL-03 `registry.tenant.status` không có trạng thái thử nghiệm, còn tổng tài khoản thiết kế đã chốt là 6,5 triệu | WEB-016 chỉ dùng tám trạng thái của TL-03 (`SAN_SANG` hiển thị "Chờ kích hoạt" như mockup) và lấy tổng tài khoản thiết kế từ API (`designAccounts`, fixture 6,5 triệu); nếu cần tenant thử nghiệm thì phải bổ sung cột hoặc trạng thái ở TL-03 |
| Mã nguồn nhiệm vụ ngoài kế hoạch | TL-02 SCR-0501 và cột `source_type` TL-03: `PHAT_SINH`; ràng buộc `ck_task__source` TL-03: `DOT_XUAT` | Giữ `DOT_XUAT` theo ràng buộc bảng (`TaskSource`), hiển thị "Phát sinh" (WEB-028) |
| Hạn của nhiệm vụ trích từ văn bản | TL-02 SCR-0502 trường 8: "trong kỳ hoặc kỳ sau"; BR-05-03 và SCR-0501: hạn trong kỳ | Kiểm theo BR-05-03 vì nhiệm vụ trích được giao qua cùng kiểm tra của SCR-0501 (WEB-029); cần chốt cách giao nhiệm vụ có hạn sang kỳ sau |
| Quyết định với gợi ý AI | TL-02 SCR-0502 trường 12: Chờ xác nhận, Đã xác nhận, Đã bỏ, Đã gộp (`decision`); TL-03 `ai.suggestion_log.user_action`: `CHAP_NHAN`, `SUA`, `BO_QUA` | Giao diện dùng `CHO_XAC_NHAN`, `DA_XAC_NHAN`, `DA_BO`, `DA_GOP` cho `decision` và gửi `BO_QUA` khi bỏ (WEB-029); cần chốt khi có OpenAPI |
| Điểm và sản phẩm của dòng kế hoạch | TL-02 SCR-0402 gọi là "điểm" (`score_weight`) và "sản phẩm" (`product_name`); TL-03 bảng dòng kế hoạch dùng `standard_score` và không có cột tên sản phẩm | Giao diện dùng `standardScore` và `productName` (WEB-026); cần chốt tên cột và bổ sung cột sản phẩm vào TL-03 |
| Tổng số tài khoản | NFR-01: 6 triệu; TL-04 định cỡ: 6,5 triệu | **Đã chốt 6,5 triệu** cho định cỡ (19/10/2026) |
| "Ba tầng" của cây OKR | TL-02 QT-02: Tầng 1 là O/KR của đơn vị, Tầng 2 là chương trình, đề án, Tầng 3 là nhiệm vụ (`okr.okr_link` tới `PROGRAM`, `TASK`); task WEB-021 và cột `okr.objective.tier`, `parent_objective_id` (TL-03): mục tiêu phân rã đơn vị → phòng → cá nhân | WEB-021 hiện cả hai: cây mục tiêu theo `parent_objective_id` (tầng 1–3 của `okr.objective`) và trong bảng KR là chương trình, nhiệm vụ liên kết ghi "Tầng 2–3" như mockup; **chờ chốt** nghĩa chính thức của "tầng" |

### Ghi chú và đề xuất từ WEB-056 (realm Keycloak dev)

Realm dev không tạo composite role vì baseline và TL-02 không định nghĩa vai trò gộp. Có một đề xuất để người phụ trách cân nhắc: ở production nên có vai trò mặc định của realm (`default-roles-<realm>`) gồm `member`, vì hầu hết cán bộ ở mọi vai trò nghiệp vụ khác đều đồng thời là AC-06; hiện tài khoản thử được gán `member` tường minh.

Cấu hình mức xác thực của Keycloak có hai ràng buộc kỹ thuật đã quyết định cách dựng luồng ở dev. Keycloak không cho hai điều kiện "Level of Authentication" cùng mức trong một luồng, nên mỗi nhánh OTP theo vai trò AAL2 dùng một mức nội bộ riêng (3 đến 7) và chỉ `aal1` = 1, `aal2` = 2 được đặt tên trong `acr.loa.map`; mọi mức từ 2 trở lên đều hiện thành `acr=aal2` trên token. Để token của vai trò AAL2 mang `acr=aal2`, bước mật khẩu nằm ngoài nhánh mức 1, vì vậy khi step-up Keycloak hỏi lại mật khẩu rồi mới hỏi OTP. Cần người phụ trách chốt hai điểm khi dựng realm production theo cell: có chấp nhận hỏi lại mật khẩu khi step-up hay không (nếu không thì phải viết authenticator riêng hoặc buộc MFA cho mọi người dùng như TL-04 SEC-13, khi đó luồng đơn giản hơn nhiều), và cửa sổ step-up là 15 phút (BR-00-02, đang dùng ở dev) hay 5 phút (TL-01).

### Chờ quyết định cho quản trị vai trò và uỷ quyền (WEB-058)

Bộ TL có bảng `config.role_binding` và quy định uỷ quyền vắng mặt nhưng chưa có màn hình, API, BR, MSG. Đề xuất bổ sung thiết kế nằm ở `docs/phan-quyen/de-xuat-quan-tri-vai-tro.md` và đang chờ người phụ trách chốt năm điểm: nguồn sự thật của vai trò giữa `config.role_binding` và Keycloak (đề xuất `role_binding` là nguồn, đồng bộ sang Keycloak), cách biểu diễn trạng thái chờ duyệt, ai là người duyệt thứ hai khi cấp vai trò nhạy cảm, có thêm bảng `config.delegation` cho uỷ quyền cá nhân và check-in thay hay không, và cấp mã chính thức cho ba màn hình (gợi ý SCR-0105 đến SCR-0107), BR, MSG, sự kiện và API.

## Khoảng trống trong mã so với thiết kế

Các điểm sau không phải mâu thuẫn mà là phần thiết kế chưa được hiện thực, cần biết để không tưởng nhầm là đã có. `UiPackLoader` chưa kiểm chữ ký UI Pack (`TODO(ADR-05)`, task WEB-009) và chưa đọc manifest plugin; chưa gọi API phân giải registry phía máy chủ. Chưa có Faro và OTel web (WEB-011), chưa có chế độ nhúng `<vt-okr-app>` (WEB-013). Chưa có CSP, HSTS, Trusted Types trong `nginx.conf` (WEB-014). Ngân sách trong `angular.json` còn mặc định 500 kB và 1 MB thay vì 250 KB và 180 KB gzip (WEB-003). Chưa có luồng chọn phân công, xử lý 401 step-up (WEB-006). Tuỳ chọn chế độ người cao tuổi lưu trong `localStorage` thay vì cấu hình theo người dùng ở máy chủ. `angular-oauth2-oidc` có thể đang lưu token vào `sessionStorage` (xem [trang 07](07-xac-thuc-phan-quyen.md)). Chặn tuyến hiện tại theo tuyến cha quá chặt so với ma trận phân quyền: `/admin` chặn AC-04, AC-07, AC-09, AC-10 dù SCR-0103, SCR-0801 cho họ xem hoặc duyệt, `/config-studio` chặn AC-08 dù SCR-1503 cho ký phê duyệt, `/dashboard` chặn AC-07 dù SCR-1603 cho xem (WEB-055).

## Câu hỏi mở cần người phụ trách trả lời

Câu hỏi một là biểu mẫu MRC: baseline mục 2 ghi giả định rằng không tìm thấy mẫu MRC trong tài liệu đầu vào, nên nội dung và vị trí của biểu mẫu này trong các quy trình chưa được đặc tả; cần cung cấp mẫu hoặc xác nhận bỏ.

**Đã chốt (19/10/2026):** căn cứ pháp lý là QĐ 366-QĐ/TW cùng các quy định đánh giá cán bộ Nhà nước (NĐ 335/2025, NĐ 233/2026) như bộ TL ghi; cụm "Thông tư 22" trong mô tả phạm vi được hiểu theo cách này, không cần sửa TL.

**Tạm chốt (19/10/2026):** chấp nhận cell thí điểm `CELL-NB` theo đề xuất của TL-04 (tenant nội bộ dùng thử, phát hành trước `CELL-G4`); cần bổ sung mã này vào baseline ở lần cập nhật tới.

**Đã chốt (19/10/2026):** tổng số tài khoản dùng cho định cỡ là 6,5 triệu.

Câu hỏi năm là chốt các điểm lệch kỹ thuật trong bảng trên, ưu tiên mã lỗi xung đột, thời hạn token, đường dẫn việc của tôi và cách giữ `Idempotency-Key` khi thử lại, vì chúng ảnh hưởng trực tiếp tới `@vt/api-client` và `@vt/auth` ở GĐ0.

Câu hỏi sáu là bổ sung thiết kế cho quản trị phân quyền: bộ TL có bảng `config.role_binding` (vai trò có phạm vi, thời hạn, người duyệt thứ hai cho vai trò nhạy cảm) và có quy định duyệt thay, nghiệm thu thay, check-in thay, nhưng không có SCR, API, BR hay MSG cho màn hình cấp, thu hồi vai trò và uỷ quyền. Cần người phụ trách cấp mã trước khi làm pha 2 của WEB-058. Cùng nhóm này còn ba điểm nhỏ: nhiều ràng buộc tách vai trò chưa có MSG riêng (BR-04-08, BR-06-08, BR-07-06, BR-08-01, BR-15-09, BR-14-08, danh sách ở bảng ràng buộc của trang 15a), nguồn không nêu vai trò của "thư ký tổng hợp" ở SCR-0904 và của chủ trì hội nghị ở SCR-1101, và nguồn không nêu ai là người duyệt UI Pack ở SCR-1801 (ma trận tạm ghi `tenant_admin` khác người soạn, đánh dấu suy luận).

## Liên quan

- [README của wiki](README.md)
- [Tổng quan hệ thống](01-tong-quan-he-thong.md)
- [Quy ước API](06-quy-uoc-api.md)
- [Xác thực và phân quyền](07-xac-thuc-phan-quyen.md)
- [Phân quyền](15-phan-quyen.md) và [Ma trận phân quyền](15a-ma-tran-phan-quyen.md)
- [Bảo mật frontend](08-bao-mat-frontend.md)
- [Hạ tầng dev và triển khai](12-ha-tang-dev-va-trien-khai.md)
