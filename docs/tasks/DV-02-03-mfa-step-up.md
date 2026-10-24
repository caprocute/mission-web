# DV-02-03 · QT-00 MFA theo vai trò, thiết bị, rủi ro; step-up `acr` và `auth_time`

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-02-03` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 |
| Luồng đề xuất | B5 |
| Cỡ việc | M |
| Phụ thuộc | DV-02-01 |
| Truy vết | QT-00 bước 4, 6 → SCR-0001 → OIDC (tham số `acr_values`, `max_age`) → SVC-02 iam; BR-00-01, BR-00-02, BR-00-06; MSG-00-05, MSG-00-08; NFR-08 (ASVS L3 cho xác thực) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Keycloak quyết định khi nào phải xác thực nhiều lớp: luôn với các vai trò yêu cầu AAL2, với thiết bị hoặc vị trí mới, và khi điểm rủi ro cao; khi người dùng làm thao tác nhạy cảm, client xin bước lên và nhận token có `acr` cao cùng `auth_time` mới. Khi xong, máy chủ nghiệp vụ chỉ cần kiểm `acr` và `auth_time` trong token (BR-00-02: trong 15 phút) để chặn duyệt có ký số, nghiệm thu, khoá kỳ, mở lại kỳ.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-00 bước 4 và 6 (dòng 327–329), trạng thái phiên `AAL1`/`AAL2` (dòng 336–351), BR-00-01, BR-00-02, BR-00-06 (dòng 353–364), phương thức SmartOTP, FIDO2/WebAuthn (dòng 328), MSG-00-05, MSG-00-08.
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SVC-02 iam" đoạn "Cấu hình bảo mật chính" và chương "Bảo mật" (dòng 3002; ví dụ chính sách "Nghiệm thu cần nâng bậc xác thực" dòng 3051).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` bảng SEC (dòng 2537–2541, SEC-13, SEC-16, SEC-17) và đoạn dòng 2862 (MFA cho mọi người dùng ở cấp độ 4, VNeID mức 2 coi như đã đạt MFA, không có nhánh bỏ qua MFA – sửa S06).
- Hợp đồng: `contracts/openapi/common/common.yaml` phản hồi 401 `insufficient_user_authentication`.
- Wiki: `wiki/07-xac-thuc-phan-quyen.md`, `wiki/15-phan-quyen.md` mục step-up, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 3. Phạm vi công việc

- Cấu hình bậc xác thực (Level of Authentication) trong luồng browser của Keycloak 26: bậc 1 mật khẩu hoặc IdP, bậc 2 TOTP (SmartOTP) hoặc WebAuthn; ánh xạ `acr` `aal1`, `aal2`; hỗ trợ `acr_values` và `max_age` để client xin bước lên.
- Không để luồng đăng nhập có nhánh bỏ qua MFA (S06); tài khoản đăng nhập bằng VNeID mức 2 được coi là đã đạt bậc 2 (TL-04 dòng 2862). Bắt buộc bậc 2 ngay khi đăng nhập với người dùng có vai trò `platform_admin`, `tenant_admin`, `appraiser`, `decider`, `second_approver`, `inspector` (BR-00-01 và danh sách TL-01).
- Viết authenticator SPI "thiết bị mới hoặc rủi ro": nhận diện thiết bị bằng cookie thiết bị ký và dấu vân tay thô, vị trí theo dải IP; thiết bị hoặc vị trí mới bắt buộc bậc 2, ghi sự kiện kiểm toán và yêu cầu thông báo MSG-00-08 (BR-00-06); điểm rủi ro là tham số cấu hình, công thức đơn giản ghi rõ (suy luận vì TL không đặc tả công thức).
- Bảo đảm `auth_time` cập nhật khi bước lên và token mang `acr` đúng bậc vừa đạt; phiên AAL2 hạ về AAL1 sau 15 phút kể từ lần MFA (TL-02a trạng thái phiên).
- Viết kiểm thử Testcontainers Keycloak: vai trò AAL2 không vào được khi chỉ có mật khẩu; thiết bị mới bị hỏi OTP; xin `acr_values=aal2` trả token `acr=aal2` với `auth_time` mới.
- Điểm lệch cần lưu ý: TL-01 ghi step-up "trong 5 phút gần nhất cho thao tác ký và quyết định", BR-00-02 ghi "MFA trong 15 phút"; BR là quy tắc nghiệp vụ được ma trận và hợp đồng dùng nên chọn 15 phút làm mặc định, để giá trị là tham số và ghi vào wiki trang 14. Thêm: TL-04 dòng 2862 yêu cầu MFA với mọi người dùng ở cấp độ 4, rộng hơn danh sách vai trò của TL-01; làm cờ cấu hình theo tenant (mặc định bật cho tenant cấp độ 4) và ghi vào wiki trang 14.

## 4. Ngoài phạm vi

- Bộ lọc kiểm `acr`/`auth_time` trong mission-core và trả 401 (NEN-014).
- Mã khôi phục một lần khi mất SmartOTP (TL-02a dòng 371): hợp đồng chưa có API, cần task riêng.
- Đẩy sự kiện đăng nhập sang Kafka (DV-02-05).

## 5. Tệp được phép sửa

- `backend/services/iam/**` (authenticator SPI, kiểm thử)
- `deploy/charts/iam/**` (luồng xác thực, bậc LoA trong mẫu realm)
- `wiki/07-xac-thuc-phan-quyen.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/iam -am verify` chạy xanh với ba ca Testcontainers ở mục 3; SpotBugs + Find Security Bugs không có lỗi mức cao.
- `helm lint deploy/charts/iam` không lỗi; mẫu realm nạp được sau thay đổi.
- Không ghi mã OTP, bí mật TOTP hay khoá thiết bị vào nhật ký.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
