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

Ngày 23/10/2026 (B4) hai bộ hành trình được chạy thật trên bộ compose dev dựng trong máy làm việc. Lời gọi API của
kịch bản khớp hợp đồng; mã người dùng Keycloak của tài khoản thử trùng mã cán bộ của
`infra/dev/postgres/seed/org-du-lieu-thu.sql`, nên kịch bản dùng `sub` làm mã người giao và người chủ trì là đúng.
Các bước dưới đây là những gì môi trường cần để hành trình chạy tới phần nghiệp vụ.

### Trình tự chuẩn bị

1. Bật profile `core`, `security`, `events`, chạy `create-topics.sh`, rồi `deploy-backend.sh` cho apisix,
   mission-core, org-service, workflow-service, scoring-service, evidence-service, tenant-registry và
   notification-service (clamav bật kèm evidence-service).
2. `infra/dev/scripts/dang-ky-luoc-do.sh`: đăng ký lược đồ Avro vào Apicurio (compose không có Job của chart).
   Lượt hai đăng ký dạng lớp sinh mã, vì bộ ghi tra lược đồ theo nội dung lớp (xem "Khoảng trống" bên dưới).
3. `infra/dev/scripts/tao-phan-vung-ky.sh`: tạo trước phân vùng theo kỳ từ năm 2020 đến năm sau.
4. `infra/dev/scripts/seed-org.sh`: nạp tổ chức thử (năm tài khoản là đảng viên), cell và tenant thử của
   tenant-registry, rồi phát lại sang `org_replica` của mission-core và workflow-service.
5. `data/seed/ui-pack/nap_ui_pack.py` với token của `quantri` (và `quantri2` để phát hành): không có UI Pack đã
   phát hành thì `GET /ui/layouts/home` trả 404 và trang chủ ghi lỗi.
6. Đặt `KEYCLOAK_PUBLIC_URL=http://localhost:35080` trong `infra/dev/.env` rồi tạo lại apisix và các service.
   Thư viện OIDC của web chỉ cho phép `http` với `localhost` (`requireHttps: remoteOnly`), nên issuer dạng
   `http://127.0.0.1:...` làm đăng nhập dừng ngay; `E2E_KC_URL` phải trùng từng ký tự với giá trị này.
7. `npm run e2e:prepare`, rồi `E2E_MODE=he-thong E2E_APISIX_URL=http://127.0.0.1:35000
   E2E_KC_URL=http://localhost:35080 npx playwright test -c e2e/playwright.config.ts --grep "@qa-05|@qa-06"`
   (kèm `KC_TEST_USER_PASSWORD`, `KC_TEST_OTP_SECRET`).

### Sửa ở kịch bản và web trong lượt này

- Realm không cho dùng lại một mã OTP; kịch bản đăng nhập cùng tài khoản nhiều lần trong 30 giây nên mã thứ hai bị
  coi là sai, vài lần sai làm Keycloak khoá tạm tài khoản. `dang-nhap-keycloak.ts` chờ sang bước TOTP mới.
- Token được bắt qua `page.route` ở endpoint token thay vì đọc thân phản hồi, vì Chrome bỏ thân phản hồi khi trang
  chuyển tuyến ngay sau đổi mã.
- Web gọi làm mới token khi nhận 401 trước lúc đăng nhập xong (chưa có refresh token), Keycloak trả 400 và web báo
  hết phiên. `SessionService.handleUnauthorized` bỏ qua khi chưa có refresh token; shell chỉ nạp cờ tính năng sau
  khi phiên sẵn sàng.
- Hành trình kế hoạch (HT-11, HT-12) dùng kỳ quý, vì máy chủ lấy kỳ quý hiện hành khi màn hình kế hoạch không chọn
  kỳ. Hành trình khoá kỳ tạo kỳ trong năm 2020..2024, vì máy chủ không nhận kỳ trước năm 2020.

### Khoảng trống phía máy chủ còn chặn hành trình

- **Chưa có tác vụ tạo phân vùng khi tạo kỳ.** Bảng nghiệp vụ phân vùng theo `period_bucket` không có phân vùng
  mặc định, và không mã nào gọi `fn_create_period_partitions` khi tạo kỳ; ghi kế hoạch hay nhiệm vụ vào kỳ mới
  lỗi 500. Dev tạm dùng `tao-phan-vung-ky.sh`.
- **Danh mục áp dụng bị từ chối với mọi vai trò.** `GET /catalog/items` gắn chức năng `SCR-0503.xem`, mà ma trận
  chỉ cấp cho `member` phạm vi bản thân, trong khi mục danh mục không có người sở hữu; OPA trả `out_of_scope` cho
  cả cán bộ lẫn quản lý. HT-11 và HT-13 (giao việc từ kế hoạch, chọn mục danh mục) dừng ở đây.
- **Cán bộ không xem được danh sách kỳ.** `GET /periods` gắn `SCR-0801.xem` (màn hình cấu hình kỳ), nên vai trò
  `member` bị từ chối dù ma trận API cho phép.
- **Lược đồ Avro lệch giữa tệp .avsc và lớp sinh mã.** Chart đăng ký tệp .avsc có tham chiếu, còn bộ ghi tra theo
  nội dung lớp sinh mã (`avro.java.string`, kiểu lồng viết liền) với AUTO_REGISTER tắt, nên mọi bộ ghi Avro báo
  "Could not resolve artifact reference by content" ở cụm mới. Dev tạm đăng ký thêm dạng lớp.
- **Chưa có API tạo Policy Pack.** Hợp đồng chỉ có tạo phiên bản cho gói đã tồn tại; `importPack` không có nơi
  gọi. Không dựng được gói cho luồng Đảng nên HT-25 đến HT-27 vẫn ghi "chờ".
- **esign-service và document-service không chạy được migration qua `MigrationRunner`.** Hai service tự tạo
  `outbox_event` ở V1, trùng migration chung V0_x; deploy-backend dừng ở hai service này nên ký số (HT-27) trả 503.
- Compose chưa có kafka-ui, nên bước kiểm sự kiện ghi "chờ".
