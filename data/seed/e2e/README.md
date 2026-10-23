# Dữ liệu đầu vào của kiểm thử đầu cuối trên hệ thống thật (QA-03)

`he-thong.json` mô tả dữ liệu mà các hành trình trong `frontend/e2e/hanh-trinh/` dựng qua API thật
(APISIX → mission-core) trước khi chạy, bằng chính danh tính của tài khoản thử theo vai trò:

- **Kỳ** (`ky`): dùng kỳ đang mở bao trùm hôm nay; nếu chưa có thì tài khoản quản trị đơn vị tạo kỳ
  tháng hiện tại với mã `E2E-<năm-tháng>`.
- **Nhiệm vụ** (`nhiemVu`): nhiệm vụ đột xuất do người giao (`quanly`) soạn cho người chủ trì (`canbo`)
  rồi giao; tiêu đề bắt đầu bằng tiền tố của lần chạy (`{MA}`, lấy từ `E2E_RUN_ID` hoặc sinh theo thời
  gian) để dọn và để hai lần chạy không đụng nhau.
- **Tiến độ** (`tienDo`): số nấc tăng trên thanh trượt và nội dung "Kết quả đã làm".

Đơn vị, hồ sơ cán bộ và claim tenant của tài khoản thử không tạo ở đây mà lấy từ seed realm và tổ chức
(QA-01). Danh mục công việc không cần cho hành trình mẫu nên chưa dựng. Tệp chỉ chứa dữ liệu thử, không
có họ tên, số định danh hay thông tin cá nhân thật, không chứa địa chỉ máy chủ hay mật khẩu.

## Hành trình kế hoạch, giao việc, tiến độ, minh chứng (QA-05)

`qt04-07.json` là dữ liệu của các hành trình trong `frontend/e2e/hanh-trinh/qt04-07/`, dùng chung cho
chế độ giả lập và hệ thống thật:

- **Kế hoạch** (`keHoach`): dòng việc ngoài danh mục, nội dung sửa sau khi bị trả lại, ý kiến trả lại,
  ý kiến duyệt và lý do điều chỉnh.
- **Giao việc** (`giaoViec`): tên, sản phẩm, minh chứng yêu cầu và tiêu chuẩn chất lượng của nhiệm vụ
  giao từ kế hoạch.
- **Văn bản chỉ đạo** (`vanBan`): trích yếu của văn bản đến dùng để tạo nhiệm vụ.
- **Tiến độ** (`tienDo`): ghi chú cập nhật, nội dung điểm nghẽn và cách xử lý, lý do gia hạn, số ngày
  gia hạn thường và số ngày vượt ngưỡng (đi qua luồng duyệt nhiều bước).
- **Minh chứng** (`minhChung`): tên tệp, ý kiến yêu cầu bổ sung, nhận xét nghiệm thu.
- **Tệp thử mã độc** (`eicar`): chuỗi thử EICAR chuẩn tách ba phần để công cụ quét trên máy làm việc
  không cách ly chính tệp dữ liệu; hành trình ghép lại lúc chạy.

Chạy ở chế độ giả lập: `npx playwright test -c e2e/hanh-trinh/qt04-07/playwright.gia-lap.config.ts`
(sau `npm run e2e:prepare`); trên hệ thống thật các tệp này chạy cùng bộ QA-03 với `E2E_MODE=he-thong`.

## Chạy thật QA-05 và QA-06 trên bộ hạ tầng dev

Ngày 23/10/2026 (B4) hai bộ hành trình được chạy thật trên bộ compose dev dựng trong máy làm việc, theo trình tự
chung ở mục dưới. Lời gọi API của kịch bản khớp hợp đồng. Ngoài trình tự đó, hai bộ này cần thêm:

- **clamd cho minh chứng.** Compose có dịch vụ `clamav` và evidence-service bật quét (`EVIDENCE_SCAN_ENABLED`);
  thiếu clamd thì tệp tải lên mãi chờ quét, HT-20 và HT-21 không qua được.
- **Chính sách OPA thật.** OPA gắn `backend/opa/okrkpi`; bản khởi tạo `infra/dev/opa/policies` chỉ từ chối mặc định.
- **Cán bộ thử là đảng viên.** Luồng Đảng chỉ lập hồ sơ cho đảng viên, luồng Nhà nước còn giữ trạng thái chưa lập
  (wiki trang 14 mục 59(e)), nên `org-du-lieu-thu.sql` đặt `is_party_member` cho năm tài khoản thuộc diện đánh giá.

Sửa ở kịch bản và web trong lượt này: token được bắt qua `page.route` ở endpoint token (Chrome bỏ thân phản hồi khi
trang chuyển tuyến ngay sau khi đổi mã); hành trình kế hoạch (HT-11, HT-12) dùng kỳ quý, vì máy chủ lấy kỳ quý
hiện hành khi màn hình kế hoạch không chọn kỳ; hành trình khoá kỳ tạo kỳ trong năm 2020..2024, vì máy chủ không
nhận kỳ trước năm 2020 (`ck_period__bucket`); web không gọi làm mới token khi chưa có refresh token (trước đây 401
lúc chưa đăng nhập xong làm Keycloak trả 400 và web báo hết phiên).

Khoảng trống phía máy chủ còn chặn hành trình (ghi ngày 23/10/2026):

- Đã sửa trong lượt sau (23/10/2026): `GET /catalog/items` nay gắn `SCR-0401.xem` (phạm vi đơn vị) và `GET /periods`
  chỉ xét dòng API `periods` của ma trận, nên cán bộ, quản lý, lãnh đạo đọc được kỳ và danh mục (ca kiểm
  `backend/opa/okrkpi/doc_chung_test.rego`). Tenant thử chưa có mục danh mục nào, nên HT-11 và HT-13 vẫn cần
  mục danh mục được tạo trước.
- Chưa có API tạo Policy Pack, nên `seed-org.sh` nạp gói mẫu luồng Đảng bằng `infra/dev/postgres/seed/policy-goi-mau.sql`
  (sinh từ `data/policy-pack` bằng `data/policy-pack/sinh_seed_dev.py`, wiki trang 14); compose có thêm
  `cel-js-runner` để lần chạy ca vàng đối chiếu được hai engine. Còn chặn HT-37: đọc danh sách bảng quyết định
  trả 500 (bảng tiêu chí chung có đầu ra ngoài thang mức) và phiên bản chưa có quy tắc cho thẻ Công thức.
  HT-25 đến HT-27 cần một phiên bản đã phát hành gắn vào kỳ, tức là HT-37 đi hết trước.
- esign-service và document-service tự tạo `outbox_event` ở V1, trùng migration chung V0_x mà `MigrationRunner`
  thêm vào, nên hai service không triển khai được và ký số (HT-27) trả 503.
- Compose chưa có kafka-ui, nên bước kiểm sự kiện ghi "Chờ".

## Dựng đủ hệ thống thật trên một máy để chạy QA-03, QA-04

Ngày 23/10/2026 (B1) bộ QA-04 (`hanh-trinh/qt00-03/`) và hành trình mẫu QA-03 (`qt04-07/ht17-cap-nhat-tien-do.spec.ts`)
đã chạy ở chế độ `E2E_MODE=he-thong` trên hạ tầng `infra/dev` dựng trong một container sạch, với mission-core,
org-service, tenant-registry, integration-gateway, notification-service, OPA (`OPA_ENABLED=true`), Keycloak và
APISIX thật. Trình tự dưới đây dùng chung cho server dev và máy trạm; các bước nạp dữ liệu đều chạy lại được.

1. Dựng theo `infra/dev/README.md` mục 3.2: `gen-env.sh`, bật hạ tầng, `deploy-backend.sh` (bước seed của nó
   đăng ký lược đồ Avro, nạp tổ chức và tenant thử, chạy `org-republish`, tạo kỳ hiện hành và phân vùng theo kỳ),
   rồi nạp UI Pack mặc định. `seed-org.sh` nạp thêm phân công kiêm nhiệm thứ hai của `quanly` (HT-02) và thư viện
   căn cứ thử `okr.basis` (bản này chưa có API ghi căn cứ, thiếu thì không soạn được mục tiêu).
2. Realm `mission` đã nhập trước ngày 23/10/2026 thiếu quyền Admin API của client `org-service` (vai trò
   `view-users`, `manage-users` của `realm-management`, mapper đưa vai trò đó vào token) và chính sách thuộc tính
   `ADMIN_EDIT`, nên chọn phân công (`PUT /org/me/context`) trả 503. Keycloak chỉ nhập realm ở lần đầu, nên realm
   cũ cần thêm ba mục này bằng `kcadm.sh` hoặc dựng lại realm từ `infra/dev/keycloak/realm-mission.json`.
3. Trong `frontend/`: `npm run e2e:prepare`, rồi chạy với các biến dưới đây.

| Biến | Giá trị | Ghi chú |
|---|---|---|
| `E2E_MODE` | `he-thong` | Hoặc chạy `npm run e2e:he-thong` |
| `E2E_APISIX_URL` | `http://127.0.0.1:35000` | APISIX dev, trực tiếp hoặc qua hầm SSH |
| `E2E_KC_URL` | trùng từng ký tự `KEYCLOAK_PUBLIC_URL` | Mặc định `http://127.0.0.1:35080`; web nhận Keycloak http ở `127.0.0.1` như `localhost` |
| `KC_TEST_USER_PASSWORD`, `KC_TEST_OTP_SECRET` | giá trị trong `infra/dev/.env` của chính hạ tầng | Không ghi vào repo |
| `E2E_KAFKA_UI_URL`, `E2E_KAFKA_CLUSTER` | để trống | Compose dev chưa có kafka-ui, ca kiểm sự kiện ghi "Chờ" |

Mã OTP của một tài khoản không dùng lại được trong cùng bước 30 giây, nên kịch bản đăng nhập chờ sang bước mới khi
một tài khoản đăng nhập hai lần liền nhau; nhập sai nhiều lần làm Keycloak khoá tạm tài khoản thử dùng chung. Tuỳ
chọn chữ lớn lưu theo người dùng ở máy chủ, nên một lần chạy có thể bắt đầu ở chế độ cao tuổi do lần trước để lại.

Kết quả lượt chạy cuối ngày 23/10/2026: HT-01, HT-02, HT-05 (ca phủ định), HT-06 và hai ca HT-17 đạt. HT-03,
HT-04 và HT-05 (ca khởi tạo tenant) bỏ qua theo thiết kế của bộ; HT-07 ghi "Chờ" vì org-service chưa gửi token
dịch vụ khi gọi integration-gateway và dev chưa có nguồn nhân sự giả lập; HT-08 và HT-09 ghi "Chờ" vì kỳ thử chưa có
mục tiêu đã duyệt của đơn vị cấp trên (realm dev không có tài khoản thử ở cấp Sở để lập sẵn).
