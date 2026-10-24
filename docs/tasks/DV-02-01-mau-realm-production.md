# DV-02-01 · SVC-02/ADR-08 Mẫu realm production theo cell: Organizations, PKCE, mật khẩu, khoá tài khoản, thời hạn phiên

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-02-01` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 |
| Luồng đề xuất | B5 |
| Cỡ việc | M |
| Phụ thuộc | Không có task bắt buộc; kế thừa realm dev `infra/dev/keycloak/realm-mission.json` của WEB-056 |
| Truy vết | QT-00 → SCR-0001 → OIDC `/realms/<realm>/protocol/openid-connect/*` → SVC-02 iam (Keycloak 26) → schema `iam` (bảng Keycloak, không sửa); BR-00-05, MSG-00-01, MSG-00-02; ADR-08; NFR-08 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Có một mẫu realm Keycloak dùng cho mọi cell production, khai báo dạng mã, nhập được tự động khi dựng cell: một realm mỗi cell, mỗi tenant là một Organization (ADR-08), client OIDC Authorization Code + PKCE cho web và di động, chính sách mật khẩu, khoá tài khoản sau 5 lần sai trong 15 phút (BR-00-05), thời hạn token và phiên theo TL-01. Khi xong, dựng một Keycloak trắng và nạp mẫu là có realm đúng cấu hình, các task DV-02 khác chỉ việc thêm mapper, authenticator và extension lên trên.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SVC-02 iam" (dòng 987–1005, đặc biệt đoạn "Cấu hình bảo mật chính") và chương "Bảo mật" (dòng 3002).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-00 (dòng 291–474): bảng BR (dòng 353–364), trạng thái phiên (dòng 336–351), SCR-0001 và bảng MSG (dòng 379–424).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` đoạn "SEC-13 đến SEC-17, SEC-54" (dòng 2862: `keycloak-config-cli` từ kho GitOps, khoá cố định, Argon2id, thời hạn token) và mục "Cắt tải và giới hạn tốc độ ở APISIX" (dòng 1596: discovery realm của cổng).
- `infra/dev/keycloak/realm-mission.json`, `docs/tasks/WEB-056-dong-bo-realm-keycloak.md`, `docs/phan-quyen/ma-tran.json`.
- Wiki: `wiki/07-xac-thuc-phan-quyen.md`, `wiki/15-phan-quyen.md`, `wiki/15a-ma-tran-phan-quyen.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 3. Phạm vi công việc

- Tạo mẫu realm production dạng tệp cấu hình cho `keycloak-config-cli` trong kho GitOps (TL-04 dòng 2862: không chỉnh tay), có biến thay thế cho tên miền, khoá và cell, gồm mười một realm role theo `ma-tran.json` (không tự thêm vai trò), bật Organizations, tắt đăng ký tự do.
- Khai báo client công khai cho web và di động: Authorization Code + PKCE S256 bắt buộc, kiểm `state`, `nonce`, redirect URI theo biến, không bật implicit hay direct grant; client `apisix-gateway` chỉ dùng để kiểm token.
- Đặt token và phiên: access token ký ES256, refresh token xoay vòng và thu hồi khi dùng lại, SSO tối đa 10 giờ, nhàn rỗi 30 phút; thời hạn access và refresh theo lựa chọn ở dòng điểm lệch cuối mục này, khai báo bằng biến.
- Bật brute force detection: khoá 15 phút sau 5 lần sai trong 15 phút (BR-00-05) và khoá cố định sau 20 lần sai trong 24 giờ (TL-04 dòng 2862), gửi sự kiện để DV-02-05 thông báo; nội dung trang đăng nhập dùng đúng văn bản MSG-00-01, MSG-00-02 (theme thuộc WEB-020).
- Chính sách mật khẩu: băm Argon2id (TL-04; TL-01 cho phép PBKDF2-SHA512 210.000 vòng hoặc Argon2), độ dài và độ phức tạp theo ASVS L2, cấm dùng lại mật khẩu gần nhất (suy luận theo NFR-08).
- Viết Job Helm chạy `keycloak-config-cli` trong chart `iam` để áp mẫu và kiểm thử Testcontainers Keycloak: nạp mẫu, đăng nhập Code + PKCE thành công, 6 lần sai liên tiếp làm tài khoản bị khoá, token có `alg` ES256 và thời hạn đúng.
- Điểm lệch cần lưu ý: tên realm khác nhau giữa nguồn (TL-01 `okrkpi`, TL-04 `platform`, dev `mission`); thời hạn token lệch ba nơi (TL-02a QT-00 dòng 307: token 5 phút, làm mới 30 phút trượt; TL-01: access 15 phút, SSO 10 giờ, nhàn rỗi 30 phút; TL-04 dòng 2862: access 5 phút, refresh 8 giờ, SSO 10 giờ). Chọn TL-04 (tài liệu mới nhất, gắn với hồ sơ cấp độ 4) cho access và refresh token, giữ nhàn rỗi 30 phút của TL-01, để tên realm là biến Helm và ghi cả hai điểm vào `wiki/14-quyet-dinh-va-cau-hoi-mo.md` để người phụ trách chốt.

## 4. Ngoài phạm vi

- Mapper claim ngữ cảnh và API chọn phân công (DV-02-02); MFA, step-up (DV-02-03); VNeID (DV-02-04); event listener (DV-02-05); đồng bộ người dùng (DV-02-06).
- Theme giao diện đăng nhập (WEB-020); triển khai Keycloak lên cụm (INF).

## 5. Tệp được phép sửa

- `deploy/charts/iam/**` (mẫu realm, Job nạp realm, values)
- `deploy/environments/*/iam/**` (chỉ biến và tham chiếu bí mật)
- `backend/services/iam/src/test/**` (kiểm thử Testcontainers cho mẫu realm)
- `wiki/07-xac-thuc-phan-quyen.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `helm lint deploy/charts/iam` và `helm template | kubeconform -strict` không lỗi.
- `cd backend && mvn -q -pl services/iam -am verify` chạy xanh với kiểm thử Testcontainers Keycloak 26: đăng nhập PKCE đạt, thiếu `code_verifier` bị từ chối, khoá tài khoản sau 5 lần sai, token ES256 có thời hạn đúng giá trị đã chọn.
- So sánh tự động danh sách realm role trong mẫu với `docs/phan-quyen/ma-tran.json` không lệch.
- Không có mật khẩu, client secret hay khoá ký thật trong repo.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
