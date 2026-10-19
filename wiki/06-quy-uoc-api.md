> Nguồn: Baseline mục 13; TL-01 mục "API và OpenAPI", mục 4 "Luồng một request", mục "Idempotency, giới hạn tốc độ, RFC 9457", mục bảo mật (step-up RFC 9470), mục định tuyến cell, Phụ lục C (danh mục API); TL-02 chương "Quy ước giao diện chung" (xử lý lỗi, phân trang); TL-03 (thời hạn giữ `idempotency_key`); TL-04 mục "Cổng vào: Apache APISIX", "Cắt tải và giới hạn tốc độ"; mã `@vt/api-client`.

# Quy ước API

Trang này gom mọi quy ước mà frontend phải tuân theo khi gọi API của nền tảng. Quy ước gốc nằm ở baseline mục 13 và chỉ dài một đoạn; TL-01, TL-02, TL-03, TL-04 chi tiết hoá thêm và ở vài chỗ chưa thống nhất với nhau. Trang ghi rõ chỗ nào đã thống nhất, chỗ nào còn lệch, và mã hiện tại đang chọn cách nào, để người làm không phải tự dò lại bốn tài liệu.

## Hình thức chung

API là REST JSON mô tả bằng OpenAPI 3.1, tiền tố `/api/v1/<module>`. Baseline liệt kê các module: `okr`, `tasks`, `plans`, `catalog`, `products`, `evidences`, `appraisals`, `policies`, `dossiers`, `oversight`, `config`, `periods`, `org`, `files`, `ledger`, `esign`, `notifications`, `analytics`, `ai`, `ui`. TL-01 Phụ lục C dùng thêm `registry` (phân giải cell), `workflow` (hộp việc, hoàn thành hàng loạt), `jobs` (theo dõi tác vụ nền) và `search`. Tên trường JSON viết camelCase. Theo ADR-32 (bổ sung BS-02), API theo hướng hợp đồng trước: bộ hợp đồng OpenAPI 3.1 ở `contracts/openapi/` của repo là nguồn sự thật, hai phía sinh mã từ đó, còn springdoc của backend chỉ dùng để CI đối chiếu ngược bằng openapi-diff.

Phiên bản nằm trong đường dẫn (`v1`). Khi có `v2`, bản cũ được duy trì ít nhất 12 tháng và trả header `Deprecation` (RFC 9745) và `Sunset` (RFC 8594). Client nên ghi cảnh báo vào telemetry khi thấy hai header này để đội biết còn màn hình nào dùng API sắp ngừng (suy luận về cách dùng).

Trình duyệt chỉ biết một gốc API của cell (khoá `apiBaseUrl` trong `/config.json`), vì mọi service đều đứng sau APISIX (SVC-01). APISIX kết thúc TLS, chạy WAF Coraza với OWASP CRS, kiểm JWT qua JWKS của Keycloak trong cell (lưu đệm khoảng 10 phút theo TL-01) và claim `cell`, giới hạn tốc độ, rồi gắn `X-Tenant-Id` cho upstream. Frontend không tự gửi `X-Tenant-Id`. Thời gian chờ đọc ở cổng là 10 giây cho mọi API đồng bộ (TL-04), nên API nào có thể chạy lâu hơn đều phải theo mẫu tác vụ nền.

## Sinh kiểu từ hợp đồng OpenAPI

Từ WEB-007, kiểu dữ liệu của `@vt/api-client` sinh từ tệp OpenAPI 3.1 của mission-core lúc build bằng `openapi-typescript`. Công cụ này được chọn vì chỉ sinh kiểu TypeScript, không sinh mã chạy và không cần đánh giá mã lúc chạy (hợp với CSP ở quy tắc 5), nên không tốn byte nào trong gói tải; client vẫn là lớp Angular viết tay dùng `HttpClient`, nhờ vậy giữ nguyên chuỗi interceptor. Hợp đồng nằm ở `frontend/tools/openapi/mission-core.openapi.yaml`; khi mission-core chưa phát hành bản thật, đây là tệp mẫu viết tay cho `/api/v1/tasks` và `/api/v1/jobs/{id}`. Chạy `npm run api:gen` để sinh lại `projects/api-client/src/lib/generated/mission-core.ts` (đã định dạng Prettier, có `eslint-disable` vì là mã sinh), còn `npm run api:check` báo lỗi khi tệp đã commit lệch với hợp đồng, dùng làm cổng trong CI. Không sửa tay tệp sinh. Kiểu nghiệp vụ như `TaskSummary`, `TaskDetail`, `ProblemDetails`, `Job` là bí danh của `components['schemas'][...]`, và hàm `apiUrl('/tasks/{id}', { id })` dựng đường dẫn `/api/v1/...` có kiểm tên đường dẫn và tham số lúc biên dịch.

## Bộ hợp đồng OpenAPI dùng chung (WEB-063, ADR-32)

Từ WEB-063, mọi đường dẫn dưới `/api/v1` có hợp đồng ở `contracts/openapi/`: tệp `common/common.yaml` chứa thành phần dùng chung (Problem Details, phân trang con trỏ, `Idempotency-Key`, `If-Match`, job 202, bảo mật Keycloak), còn mỗi module hoặc service có một tệp trong `modules/` (tasks, org, jobs, okr, products, appraisals, dossiers, oversight, policies, config, platform). Mỗi thao tác mang phần mở rộng `x-mission` ghi QT, SCR, MOD/SVC sở hữu, EVT, vai trò, phạm vi, AAL và nguồn TL, nên truy vết được ngay trên hợp đồng. Chạy `cd contracts && npm ci && npm run lint` để kiểm tra (Redocly) và `npm run bundle` để gộp từng tệp thành bản không còn `$ref` ngoài. Khi muốn thêm hoặc đổi API, sửa hợp đồng trước trong một PR riêng, rồi mới sửa `@vt/api-client` và mã giả lập. Quy ước viết hợp đồng và bảng điểm đã chốt nằm ở `contracts/openapi/README.md`; danh sách đầy đủ các điểm nguồn ghi khác nhau nằm ở BS-02 và [trang 14](14-quyet-dinh-va-cau-hoi-mo.md).

Các model trong `api-client/src/lib/models/*.ts` hiện vẫn viết tay và chưa sinh từ bộ hợp đồng này; chuyển `frontend/tools/openapi/generate.mjs` sang đọc `contracts/openapi/` (sinh một tệp kiểu cho mỗi tệp hợp đồng, vì tên tham số cục bộ như `VersionNo` có thể trùng giữa các tệp) là bước tiếp theo của thread điều phối. Hai thay đổi đường dẫn ảnh hưởng mã hiện có: mở nháp để giao đổi từ `GET /tasks/{id}?view=assign` sang `GET /tasks/{id}/draft`, và dòng kế hoạch dùng trường `standardScore` (cột `standard_score` của TL-03) thay cho `scoreWeight`.

## Header của mỗi request

| Header | Khi nào | Giá trị | Ai gắn trong mã |
|---|---|---|---|
| `Authorization` | Mọi request tới `/api/` | `Bearer <access token>` | `authInterceptor` của `@vt/auth` |
| `traceparent` | Mọi request tới `/api/` | W3C Trace Context, sinh ở trình duyệt | `traceparentInterceptor` của `@vt/telemetry` |
| `Idempotency-Key` | Mọi request ghi (`POST`, `PUT`, `PATCH`, `DELETE`) | UUID duy nhất cho một ý định ghi | `idempotencyInterceptor` của `@vt/api-client` |
| `If-Match` | Mọi cập nhật một đối tượng đã có | ETag = `row_version` của đối tượng | Client API gắn theo từng lời gọi |
| `If-None-Match` | Đọc tài nguyên có ETag (UI Pack, dữ liệu ít đổi) | ETag lần trước | Theo thiết kế; UI Pack hiện dùng so sánh phiên bản |

Thứ tự interceptor trong `fe-shell/src/app/app.config.ts` là `apiBaseUrlInterceptor`, `authInterceptor`, `idempotencyInterceptor`, `traceparentInterceptor`, `mockApiInterceptor`; interceptor giả lập đứng cuối để thấy request đúng như khi đi ra mạng. `apiBaseUrlInterceptor` chỉ ghép gốc API vào đường dẫn bắt đầu bằng `/api/v1/` khi `apiBaseUrl` khác `/api`; vì vậy component và client luôn viết đường dẫn tương đối dạng `/api/v1/...`, không tự ghép tên miền.

## Chống ghi trùng: Idempotency-Key

Baseline yêu cầu mọi lệnh ghi có `Idempotency-Key`. TL-01 mô tả chi tiết: khoá là UUIDv7, máy chủ lưu kết quả lần đầu và trả lại nguyên kết quả đó khi nhận lại cùng khoá; nếu cùng khoá nhưng băm nội dung khác thì trả 422. Mục đích là để người dùng bấm lại hoặc mạng di động tự gửi lại không tạo hai bản ghi. Thời hạn giữ khoá chưa thống nhất: TL-01 ghi 24 giờ, còn TL-03 thiết kế bảng bổ sung `<schema>.idempotency_key` (ví dụ `task.idempotency_key`) giữ 48 giờ "đủ cho ứng dụng di động gửi lại sau khi mất mạng". Theo TL-03, khoá chỉ có nghĩa trong phạm vi một người gọi (`actor_id` là `sub` của JWT), băm yêu cầu tính trên phương thức, đường dẫn và thân đã chuẩn hoá, và phản hồi lưu lại không chứa trường C2.

Có hai điểm người làm cần chú ý. Thứ nhất, một khoá phải gắn với một ý định ghi chứ không phải một lần gọi HTTP: khi người dùng bấm "Gửi" rồi mạng lỗi và ứng dụng tự thử lại, lần thử lại phải dùng lại khoá cũ. `idempotencyInterceptor` hiện sinh khoá mới cho mỗi request chưa có header, nên logic thử lại phải tự gắn khoá trước khi gửi lần đầu (ví dụ SCR-0302 lưu khoá UUIDv7 cùng bản nháp ngoại tuyến). Thứ hai, mã hiện dùng `crypto.randomUUID()` sinh UUID phiên bản 4, trong khi TL-01 ghi UUIDv7; máy chủ chỉ cần khoá duy nhất nên đây không phải lỗi chức năng, nhưng cần chốt để thống nhất (xem trang 14).

## Khoá lạc quan: If-Match và xung đột

Mọi bảng nghiệp vụ có cột `row_version` (khoá lạc quan, optimistic lock). API trả ETag bằng `row_version`; khi cập nhật, client gửi `If-Match` với giá trị đó. Nếu đối tượng đã bị người khác sửa, máy chủ từ chối. Mã trạng thái của lỗi này đang lệch giữa các tài liệu: baseline mục 13 và TL-02 (MSG-09-07 "API trả 409") ghi 409, còn TL-01 ghi 412 với `type` là `concurrent-modification`. Mã hiện tại (`TaskApi.updateProgress`, `toProblem`) xử lý 409. Cho tới khi chốt, client nên coi cả 409 có mã xung đột và 412 là cùng một tình huống (khuyến nghị của wiki).

TL-02 quy định cách giao diện phản ứng: giữ nguyên dữ liệu người dùng đang nhập, hiện khác biệt giữa bản của họ và bản mới trên máy chủ, để họ chọn tải lại hay ghi đè có chủ ý. Không được tự động tải lại làm mất dữ liệu đã nhập.

Cần phân biệt xung đột ghi với các lỗi 409 mang nghĩa nghiệp vụ khác, ví dụ ghi vào kỳ đã khoá (MSG-08-06, MSG-13-04), gói hồ sơ đã nộp (BR-11-10) hay phân nhóm triển vọng ở kỳ quý (BR-10-07). Các lỗi này phân biệt bằng trường `code` của Problem Details, không phải bằng mã HTTP.

## Lỗi theo RFC 9457

Mọi lỗi trả về dạng Problem Details (RFC 9457). TL-01 mở rộng thêm trường `code` dạng `<MODULE>-<HTTP>-<TÊN>` và `traceId`, cùng mảng `errors[]` cho lỗi theo trường. Ví dụ trong TL-01 có `status` 409, `code` `TASK-409-PERIOD-LOCKED` cho trường hợp ghi vào kỳ đã khoá. TL-02 ví dụ `type` trỏ tới mã quy tắc nghiệp vụ dạng `.../errors/BR-04-03` và quy định giao diện ánh xạ lỗi sang thông báo MSG.

Kiểu `ProblemDetails` trong `@vt/api-client` có `type`, `title`, `status`, `detail`, `instance`, `code`, `errors` (mỗi phần tử có `field`, `message`) và `traceId`. Hàm `toProblem()` chuyển mọi lỗi HTTP về kiểu này, kể cả khi máy chủ không trả body đúng dạng (body là chuỗi JSON, trang HTML của cổng, mất mạng với `status` 0); khi đó tiêu đề tiếng Việt được dựng theo mã HTTP. Quy tắc hiển thị dựa trên TL-02: lỗi có `errors[]` hiện ở cạnh từng trường; lỗi có `code` ánh xạ được sang MSG thì hiện văn bản của MSG; các lỗi còn lại hiện `title` kèm `traceId` để người dùng báo cho bộ phận hỗ trợ.

WEB-007 hiện thực quy tắc này bằng `ProblemPresenter`. Hàm `describe(err)` trả `message`, `fields` (bảng trường sang lỗi), `conflict` và `traceId`; hàm `present(err)` làm thêm việc hiện toast qua `MessageService` của PrimeNG. Toast mặc định không bật cho lỗi theo trường và xung đột ghi, vì hai loại này hiện ngay trên màn hình. Mỗi remote khai bảng mã lỗi sang văn bản MSG của mình bằng `provideProblemMessages({ 'TASK-409-PERIOD-LOCKED': '...' })`. Lỗi theo trường hiện bằng `<vt-field-error [problem]="p" field="percent" />`. Toast chỉ hiện khi shell đã cung cấp `MessageService` và đặt `<p-toast>`, việc này thuộc WEB-012; trước đó `present()` vẫn trả kết quả để màn hình tự hiện.

Hàm `isConcurrencyConflict()` coi là xung đột ghi khi gặp 412, 409 có `type` kết thúc bằng `concurrent-modification`, hoặc 409 không có `code` (hay `code` chứa CONFLICT, CONCURRENT, VERSION). 409 mang `code` nghiệp vụ như `TASK-409-PERIOD-LOCKED` không phải xung đột ghi. Khi có xung đột, màn hình giữ nguyên bản người dùng đang sửa, tự tải bản mới rồi đặt `<vt-conflict-notice [mine] [theirs] [fields]>`: component này hiện bảng khác biệt (chỉ các trường đã đổi) cùng hai nút "Tải lại bản mới" và "Giữ bản của tôi và lưu lại" (gửi lại với `rowVersion` mới), đúng yêu cầu của TL-02 là không bao giờ tự tải lại làm mất dữ liệu.

## Phân trang

Danh sách phân trang theo con trỏ (cursor), `limit` tối đa 200; TL-02 đặt mặc định 20. Kiểu `CursorPage<T>` gồm `items` và `nextCursor` (null khi hết). Hằng `MAX_PAGE_LIMIT = 200` trong mã chặn mọi giá trị lớn hơn. Không dùng phân trang theo số trang và tổng số bản ghi, vì đếm tổng trên bảng lớn tốn kém ở quy mô 6 triệu người dùng (suy luận). TL-02 yêu cầu nhớ bộ lọc theo người dùng.

## Tác vụ dài và theo dõi tiến độ

Tác vụ nặng (khoá kỳ, ký lô, xuất Excel, sinh biểu mẫu, chạy test vàng, kiểm tra toàn vẹn) trả `202 Accepted` kèm `Location: /api/v1/jobs/{id}` trong vòng 200 ms (NFR-03, SLO-ASYNC). Giao diện theo dõi bằng `GET /api/v1/jobs/{id}` hoặc nhận cập nhật qua SSE `GET /api/v1/notifications/stream` của notification-service (SVC-12). SCR-0904 và SCR-0801 là ví dụ điển hình: tiến độ ký lô và saga khoá kỳ cập nhật qua SSE, người dùng có thể đóng màn hình mà tác vụ vẫn chạy (MSG-09-15). TL-04 PERF-22 kiểm tra 60.000 kết nối SSE với độ trễ phân phát p95 ≤ 2 giây và nối lại ≤ 10 giây.

Trong mã, `jobLocation(res)` đọc `Location` của phản hồi 202 (gọi API với `observe: 'response'`), rồi `JobTracker.track(location)` thăm dò `GET /api/v1/jobs/{id}` với giãn cách 1 s, 2 s, 4 s tới tối đa 10 s, theo `Retry-After` khi máy chủ gửi, thử lại tối đa ba lần khi mất mạng, 429 hoặc 5xx, báo lỗi ngay với 4xx khác, chỉ phát khi trạng thái hoặc tiến độ đổi và hoàn tất sau trạng thái kết thúc. Tuỳ chọn `events$` nhận luồng đẩy (SSE của SVC-12 khi WEB-012 dựng xong) để trộn với thăm dò, thăm dò vẫn chạy làm dự phòng. `<vt-job-progress [job]>` hiển thị thanh tiến trình có tên truy cập, trạng thái bằng chữ và lời nhắc có thể đóng màn hình. Lược đồ `Job` (`id`, `type`, `status`, `progress`, `message`, `resultLocation`, `problem`) và các trạng thái `CHO`, `DANG_CHAY`, `HOAN_TAT`, `THAT_BAI`, `DA_HUY` là giả định của WEB-007, dùng lại giá trị đã có trong TL-03, vì TL chưa đặc tả lược đồ job; cần đối chiếu khi mission-core có hợp đồng thật.

## Giới hạn và mã trạng thái đặc biệt

Bảng dưới gom các mã trạng thái có hành vi riêng mà giao diện phải xử lý.

| Mã | Nghĩa | Cách xử lý ở giao diện |
|---|---|---|
| 202 | Tác vụ nền đã nhận | Theo dõi `Location`, hiển thị tiến độ |
| 401 kèm `WWW-Authenticate: ... insufficient_user_authentication` | Cần xác thực bước lên (RFC 9470): thao tác yêu cầu `acr=aal2` và `auth_time` ≤ 5 phút | Shell chuyển người dùng sang xác thực lại với MFA rồi gửi lại thao tác (xem trang 07) |
| 403 | OPA từ chối | Hiện MSG phân quyền tương ứng (ví dụ MSG-09-08, MSG-12-05, MSG-13-01) |
| 409 hoặc 412 | Xung đột ghi đồng thời, hoặc vi phạm trạng thái nghiệp vụ (phân biệt bằng `code`) | Giữ dữ liệu, hiện khác biệt; hoặc hiện MSG nghiệp vụ |
| 421 | Tenant đã chuyển sang cell khác | Shell tự chuyển hướng tới địa chỉ mới do `registry/resolve` trả |
| 422 | Dữ liệu không hợp lệ, hoặc `Idempotency-Key` dùng lại với nội dung khác, hoặc thiếu lý do bắt buộc (BR-09-12) | Hiện lỗi theo trường |
| 429 kèm `Retry-After` | Vượt giới hạn tốc độ | Chờ đúng `Retry-After` rồi thử lại, không thử dồn |
| 503 | Cắt tải khi cell gần bão hoà | Hiện thông báo tạm thời, thử lại có giãn cách |

TL-01 đặt giới hạn mỗi người dùng 20 request/giây (cho phép đột biến 40), thân JSON tối đa 1 MB và xuất tối đa 100.000 dòng; cắt tải có ba lớp ưu tiên. TL-04 hiện thực giới hạn ở APISIX bằng plugin `limit-count`, `limit-req`, `limit-conn` với khoá theo tenant và người dùng lấy từ JWT qua plugin nền tảng `vt-claims`. `POST /api/v1/workflow/tasks/batch-complete` nhận tối đa 200 mục mỗi lần.

## Định tuyến cell

Theo TL-01, shell gọi `GET /api/v1/registry/resolve?tenant=` để biết tenant đang ở cell nào. Khi tenant đã chuyển cell (trạng thái `DANG_CHUYEN_CELL` của `registry.tenant` theo TL-03), máy chủ trả 421 kèm địa chỉ mới và shell tự chuyển hướng. Vùng toàn cục lưu đệm ánh xạ tenant sang cell 5 phút và dùng cookie có HMAC. Mã hiện tại chưa gọi `registry/resolve`; cell lấy từ `/config.json`.

## Dữ liệu giả lập

Trong giai đoạn đầu, frontend phát triển với mock API (quyết định đã chốt, xem trang 14). Từ WEB-008, client API (như `TaskApi`) luôn gọi HTTP thật, không còn rẽ nhánh theo `useMockApi`. Khi `useMockApi` bật trong `/config.json`, `mockApiInterceptor` của `@vt/api-client` (đứng cuối chuỗi interceptor) chặn request có đường dẫn `/api/v1/...` và trả dữ liệu giả lập thay vì gọi mạng; request ngoài `/api/v1/` như `config.json`, UI Pack hay `remoteEntry.json` đi qua bình thường. Nhờ vậy `Idempotency-Key`, `If-Match` và lỗi RFC 9457 chạy đúng đường như với máy chủ thật. Mã giả lập nằm trong chunk nạp lười `mock-registry` nên không vào gói tải đầu; dữ liệu sống trong bộ nhớ, tải lại trang là về dữ liệu ban đầu. Chưa giả lập SSE và WebSocket.

Dữ liệu tổ chức theo module API trong `src/mock/<module>/`: tệp `<module>.json` chứa fixture, tệp `<module>.mock.ts` khai các tuyến (phương thức, mẫu đường dẫn như `/api/v1/tasks/:id/progress`) và hành vi; module mới phải được thêm vào mảng `MOCK_MODULES` trong `mock-registry.ts`. Ngày trong fixture viết dạng tương đối `@today+2` hay `@today-3T08:30:00Z` và được quy đổi theo ngày chạy, để các trạng thái như "quá hạn" hay "còn N ngày" luôn đúng ngữ cảnh.

Fixture phải mô phỏng các hành vi quan trọng của máy chủ: `If-Match` khác `rowVersion` hiện tại trả 409; dữ liệu sai trả 422 kèm `errors[]` theo trường; cùng `Idempotency-Key` và cùng nội dung trả lại nguyên kết quả lần đầu, cùng khoá mà khác nội dung trả 422; tăng `rowVersion` sau khi ghi và chuyển trạng thái đúng bảng chuyển của baseline mục 9.1 (ví dụ `DA_GIAO` sang `DANG_THUC_HIEN` khi có cập nhật tiến độ đầu tiên). Mock và thật dùng chung kiểu dữ liệu.

Để thử giao diện ở trạng thái lỗi và chậm, thêm vào URL trang `?mock-error=409`, `?mock-error=422` hoặc `?mock-error=500` (cũng nhận tham số `mock-error` trên chính request). Mã 401, 409, 412 và 422 chỉ áp cho lệnh ghi (`POST`, `PUT`, `PATCH`, `DELETE`), còn 500, 503 và các mã khác áp cho mọi request `/api/v1/`; lỗi ép trả Problem Details có `code` dạng `MOCK-409`. Mã 401 giả lập yêu cầu bước lên RFC 9470 (`WWW-Authenticate` mang `insufficient_user_authentication`, `acr_values="aal2"`) và chỉ đòi một lần cho mỗi `Idempotency-Key`, nên lần gửi lại sau khi xác thực sẽ qua (WEB-006). Module `org` có thêm biến thể `?mock-org=kiem-nhiem` (ba phân công) và `?mock-org=khong-phan-cong` cho luồng chọn phân công của QT-00. Module `okr` có `?mock-source=loi` để số liệu nguồn của KR trả 502 `OKR-502-SOURCE-UNAVAILABLE` (MSG-03-03, WEB-024). Độ trễ mặc định 100 đến 200 ms, đổi bằng `?mock-delay=<ms>` trên URL trang hoặc token `MOCK_API_OPTIONS` (kiểm thử đặt 0).

## Các điểm lệch cần chốt

Danh sách dưới chỉ liệt kê; phân tích và đề xuất nằm ở [Quyết định và câu hỏi mở](14-quyet-dinh-va-cau-hoi-mo.md).

| Chủ đề | Lệch giữa |
|---|---|
| Mã lỗi xung đột `If-Match` | Baseline, TL-02: 409; TL-01: 412 |
| Thời hạn giữ `Idempotency-Key` | TL-01: 24 giờ; TL-03: 48 giờ |
| Phiên bản UUID của `Idempotency-Key` | TL-01: UUIDv7; mã: UUID v4 |
| Phương thức cập nhật tiến độ | TL-01 mục 4: `PATCH /api/v1/tasks/{id}/progress`; TL-02 và mã: `POST` |
| Danh sách "việc của tôi" | TL-02 SCR-0503: `/api/v1/tasks?assignee=me`; ma trận TL-02: `/api/v1/tasks/my`; mã: `/api/v1/tasks/mine` |
| Lược đồ `/api/v1/jobs/{id}` | TL chưa đặc tả; hợp đồng mẫu WEB-007 tự đặt trạng thái `CHO`, `DANG_CHAY`, `HOAN_TAT`, `THAT_BAI`, `DA_HUY` |

## Liên quan

- [Kiến trúc frontend](02-kien-truc-frontend.md)
- [Mã định danh và truy vết](04-ma-dinh-danh-va-truy-vet.md)
- [Xác thực và phân quyền](07-xac-thuc-phan-quyen.md)
- [Bảo mật frontend](08-bao-mat-frontend.md)
- [Quy ước mã nguồn](10-quy-uoc-ma-nguon.md)
- [Quyết định và câu hỏi mở](14-quyet-dinh-va-cau-hoi-mo.md)
