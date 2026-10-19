# MOB-01 · SVC-18 Khung Flutter: token thiết kế sang ThemeData, đăng nhập OIDC PKCE, client API sinh từ hợp đồng

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh MOB-01` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 3 – quy mô quốc gia (ứng dụng di động) |
| Luồng đề xuất | B10 |
| Cỡ việc | M (≤ 1 ngày) |
| Phụ thuộc | NEN-006 (bổ sung hiển nhiên: DV-02-01 để có client Keycloak cho ứng dụng di động; CORE-08-11 nếu đọc UI Pack từ máy chủ) |
| Truy vết | SVC-18; ADR-10, ADR-08, ADR-05; QT-00 → API `GET /api/v1/org/me` (`getMyOrgProfile`), `PUT /api/v1/org/me/context` (`setMyContext`), `GET /api/v1/ui/packs/current` (`getCurrentUiPack`) → SVC-02 (Keycloak 26, realm `mission`), MOD-08 → bảng `config.ui_pack_version` (đọc) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Có dự án Flutter trong `mobile/` chạy được trên Android và iOS: đăng nhập bằng OIDC Code + PKCE qua trình duyệt hệ thống, giữ token an toàn, gọi API qua lớp client sinh từ `contracts/openapi/` với đủ quy ước của baseline mục 13, và dựng giao diện từ cùng bộ token thiết kế với web. Mọi task MOB sau dựng màn hình trên khung này, nên khung phải chốt cấu trúc thư mục, quy ước lỗi, ngôn ngữ và điều hướng.

## 2. Đầu vào bắt buộc đọc

- `docs/quy-dinh/DI-DONG.md` (bắt buộc).
- TL-01 `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md`, mục "SVC-18 mobile-app" (dòng 1220) và chương giao diện theo tenant (token, UI Pack).
- TL-02a `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md`, mục "Thiết bị di động" (năm mục điều hướng dưới), "Khả năng truy cập", "Quy ước về nội dung do AI tạo"; QT-00 (đăng nhập, chọn đơn vị và vai trò).
- TL-04 `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md`, danh mục SEC (phần xác thực, phiên, thiết bị).
- Hợp đồng `contracts/openapi/common/common.yaml` (Problem, Idempotency-Key, If-Match, phân trang), `contracts/openapi/modules/org.yaml`, `config.yaml`; `contracts/openapi/README.md` (cách gộp bundle).
- `wiki/06-quy-uoc-api.md`, `wiki/07-xac-thuc-phan-quyen.md`, `wiki/08-bao-mat-frontend.md`, `wiki/03-giao-dien-theo-tenant.md`; token trong `frontend/projects/design-system`.

## 3. Phạm vi công việc

- Tạo dự án Flutter (bản ổn định ghi trong `mobile/pubspec.yaml`) với cấu trúc `lib/core` (cấu hình, xác thực, mạng, giao diện), `lib/features/<tính năng>`, ba môi trường dev, staging, prod đọc cấu hình lúc build từ tệp mẫu không chứa giá trị thật.
- Sinh client Dart từ bundle OpenAPI bằng openapi-generator (trình sinh Dart chọn và nêu lý do), có lệnh sinh lại và kiểm tra trong CI rằng client khớp hợp đồng.
- Viết lớp mạng chung: gắn Bearer, `Idempotency-Key` UUIDv7 cho lệnh ghi, `If-Match` cho cập nhật, `traceparent`; đọc lỗi RFC 9457 thành thông báo tiếng Việt, xử lý 401 (làm mới token), 409 (yêu cầu tải lại), 429/503 có `Retry-After`.
- Đăng nhập OIDC Code + PKCE qua AppAuth (trình duyệt hệ thống), token trong Keychain/Keystore, đăng xuất qua `end_session_endpoint`; chọn đơn vị và vai trò qua `setMyContext` rồi làm mới token; hiển thị yêu cầu xác thực bổ sung khi máy chủ đòi step-up (`acr`).
- Viết bộ chuyển token W3C Design Tokens (UI Pack mặc định của DL-06 hoặc `getCurrentUiPack`) sang `ThemeData`, có bộ phụ "elder" để MOB-08 dùng.
- Dựng khung điều hướng năm mục "Hôm nay", "Việc", "OKR", "Minh chứng", "Tôi"; văn bản tiếng Việt có dấu qua tệp ARB, không in mã SCR, mã trạng thái thô hay tên API lên màn hình.
- Viết tiện ích chặn chụp màn hình cho màn hình có dữ liệu C2 và khoá ứng dụng bằng sinh trắc học khi người dùng bật.

## 4. Ngoài phạm vi

- Lưu trữ ngoại tuyến và hàng đợi đồng bộ (MOB-02); các màn hình nghiệp vụ (MOB-03..MOB-08); thông báo đẩy (MOB-09); pipeline phát hành (MOB-10).

## 5. Tệp được phép sửa

- `mobile/**`
- `wiki/` (một trang mới về quy ước ứng dụng di động, số trang theo mục lục lúc làm, và dòng mục lục trong `wiki/README.md`)

## 6. Tiêu chí hoàn thành

- `cd mobile && flutter analyze` sạch, `flutter test` xanh (bộ chuyển token, lớp mạng, đọc lỗi RFC 9457, sinh khoá idempotency).
- Kiểm thử tích hợp với máy chủ giả lập: luồng đăng nhập PKCE (Keycloak dev hoặc Keycloak trong Testcontainers), gọi `getMyOrgProfile` thành công, lỗi 409 hiện thông báo đúng.
- Chạy trên trình giả lập Android và iOS (ghi rõ nền tảng nào chưa chạy được), không còn token hay dữ liệu C2 trong bộ nhớ dạng rõ ngoài Keychain/Keystore.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki có trang quy ước di động.

## 7. Báo cáo

Theo AGENTS.md mục 6.
