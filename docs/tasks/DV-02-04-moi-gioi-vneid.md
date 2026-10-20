# DV-02-04 · QT-00 Môi giới VNeID: state, nonce, PKCE, liên kết danh tính bằng HMAC

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-02-04` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 |
| Luồng đề xuất | B5 |
| Cỡ việc | L |
| Phụ thuộc | DV-02-01; cần `org.person.national_id_bidx` của DV-04-01 và khoá HMAC chỉ mục mù của NEN-015 để liên kết danh tính (suy luận) |
| Truy vết | QT-00 → SCR-0001 → OIDC broker `/realms/<realm>/broker/vneid/*` → SVC-02 iam → bảng `org.person` (`national_id_bidx`), `registry.tenant_idp` (`is_vneid`); BR-00-03, BR-00-04; MSG-00-03, MSG-00-04; ADR-08; vấn đề L03 của hệ hiện tại |
| Trạng thái | Chưa bắt đầu (làm với bản giả lập; nối hệ thật khi có môi trường) |

## 1. Mục tiêu

Cán bộ đăng nhập bằng VNeID qua Keycloak identity brokering, có kiểm `state`, `nonce` và PKCE S256 (khắc phục L03), chỉ chấp nhận tài khoản mức 2, và được liên kết với đúng một bản ghi người trong tenant dựa trên số định danh đã băm HMAC, không bao giờ lưu số định danh dạng rõ và không tự tạo tài khoản khi không khớp. Khi xong, luồng chạy đầu cuối với một IdP giả lập theo đặc tả VNeID và sẵn sàng chuyển sang môi trường thử thật bằng cấu hình.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-00 (dòng 291–474): bước 3–4, ánh xạ danh tính ngoài (dòng 334), ngoại lệ VNeID ngừng (dòng 370), BR-00-03, BR-00-04, MSG-00-03, MSG-00-04.
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SVC-02 iam" (dòng 995) và bảng bộ kết nối (dòng 1421–1426, đoạn về VNeID và L03).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` bảng `org.person` (dòng 1159–1180) và mục "Chỉ mục mù" (dòng 4160).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` dòng 2862 (VNeID mức 2 coi như đạt MFA).
- Hợp đồng: `contracts/openapi/modules/platform.yaml` `resolveTenantCell` (phương thức đăng nhập bật cho tenant).
- Wiki: `wiki/07-xac-thuc-phan-quyen.md`, `wiki/08-bao-mat-frontend.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 3. Phạm vi công việc

- Khai báo Identity Provider OIDC `vneid` trong mẫu realm: Authorization Code + PKCE S256, kiểm `state` và `nonce`, `clientAuthMethod` theo đặc tả VNeID (biến cấu hình), không lưu token VNeID.
- Viết Identity Provider mapper hoặc authenticator SPI đọc mức tài khoản từ claim VNeID; mức 1 bị từ chối với MSG-00-03 (BR-00-03).
- Viết authenticator "liên kết danh tính": tính HMAC-SHA-256 cắt 16 byte của số định danh bằng khoá chỉ mục mù của tenant (lấy qua OpenBao, không đặt trong cấu hình), tra `org.person.national_id_bidx` qua API nội bộ chỉ đọc của org-service; khớp đúng một người thì liên kết, không khớp thì dừng và không tạo tài khoản (TL-02a dòng 334).
- Chặn liên kết trùng: một danh tính ngoài chỉ gắn một bản ghi người trong tenant (BR-00-04); thử liên kết lần hai với người khác bị từ chối và ghi kiểm toán.
- Bọc lời gọi VNeID bằng bộ ngắt mạch 10 giây; khi mở mạch, trang đăng nhập nhận lỗi để hiển thị MSG-00-04 và gợi ý tài khoản cơ quan.
- Viết IdP giả lập (container OIDC tối giản trong kiểm thử) phát claim mức 1 và mức 2, và bộ kiểm thử Testcontainers cho: thiếu `state` bị từ chối, `nonce` lệch bị từ chối, mức 1 bị chặn, mức 2 khớp người được liên kết, không khớp không tạo tài khoản, liên kết trùng bị chặn.
- Gợi ý tách nếu quá lớn: (a) cấu hình broker + kiểm mức + bộ ngắt mạch; (b) liên kết danh tính HMAC + chặn trùng.
- Điểm lệch cần lưu ý: nguồn không đặc tả tên claim mức tài khoản và số định danh của VNeID; đặt tên claim bằng biến cấu hình và ghi giả định "(suy luận)" vào wiki trang 14 cho tới khi có tài liệu tích hợp chính thức.

## 4. Ngoài phạm vi

- IdP cơ quan OIDC, SAML, LDAP do tenant khai báo (DV-03-06).
- Mã hoá trường và khoá HMAC (NEN-015); theme trang đăng nhập (WEB-020).
- Nối và kiểm thử với VNeID thật khi có môi trường thử; khi đó chỉ đổi cấu hình kết nối, không đổi mã.

## 5. Tệp được phép sửa

- `backend/services/iam/**` (SPI mapper, authenticator, kiểm thử, IdP giả lập)
- `backend/services/org-service/src/**` (chỉ endpoint nội bộ tra người theo `national_id_bidx`, nếu chưa có)
- `deploy/charts/iam/**`
- `wiki/07-xac-thuc-phan-quyen.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/iam -am verify` chạy xanh với sáu ca kiểm thử ở mục 3; SpotBugs + Find Security Bugs không có lỗi mức cao.
- Rà mã và nhật ký kiểm thử: không có số định danh dạng rõ trong log, bảng hay thuộc tính người dùng Keycloak.
- Địa chỉ, client và chứng thư của VNeID nằm trong cấu hình realm và giá trị triển khai (bí mật qua OpenBao), không nằm trong mã; đổi từ IdP giả lập sang VNeID thật chỉ đổi cấu hình kết nối, không đổi mã.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
