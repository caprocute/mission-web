# DV-02-08 · QT-00/BR-00-01 Mã khôi phục MFA: cấp, dùng một lần, thu hồi

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-02-08` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 |
| Luồng đề xuất | B5 |
| Cỡ việc | S |
| Phụ thuộc | DV-02-03 (luồng MFA và step-up), NEN-043 (thao tác `/iam/users/{personId}/mfa-recovery-codes*`); DV-02-05 (sự kiện `iam.auth.event.v1`), DV-09-02 (sổ cái) (bổ sung) |
| Truy vết | QT-00 ngoại lệ "Người dùng mất thiết bị SmartOTP" → SCR-0001 → API đề xuất trong NEN-043: `POST /api/v1/iam/users/{personId}/mfa-recovery-codes` (`issueMfaRecoveryCode`), `POST .../revoke` (`revokeMfaRecoveryCodes`) → SVC-02 iam (Keycloak 26, authenticator SPI) → `iam.auth.event.v1`, `sys.audit.v1` → sổ cái SVC-09; BR-00-01, BR-00-06 |
| Trạng thái | Chưa bắt đầu (phần API chờ NEN-043 gộp) |

## 1. Mục tiêu

TL-02a quy định khi người dùng mất thiết bị SmartOTP và không còn phương thức MFA nào, quản trị tenant cấp một mã khôi phục dùng một lần sau khi xác minh qua đơn vị, và việc cấp mã được ghi sổ cái. Task này hiện thực đúng luồng đó: quản trị tenant cấp mã ở AAL2, người dùng nhập mã ở bước MFA của Keycloak đúng một lần rồi bắt buộc đăng ký lại yếu tố MFA, mọi lần cấp, dùng, thu hồi đều để lại vết không sửa được. Khi xong, cán bộ mất điện thoại vào lại được hệ thống trong ngày mà không hạ mức bảo đảm xác thực của vai trò đặc quyền.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-00 "Quy tắc nghiệp vụ" BR-00-01 (L357), "Ngoại lệ và xử lý" (L371: mã khôi phục một lần, xác minh qua đơn vị, ghi sổ cái).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` SVC-02 (L995–1005: cấu hình bảo mật, MFA bắt buộc theo vai trò), "Xác thực và định danh" (L3010–3016).
- `contracts/openapi/modules/platform.yaml` sau NEN-043 (hai thao tác `/iam/*`).
- Thẻ `docs/tasks/DV-02-03-mfa-step-up.md` (luồng xác thực, ghi chú "mã khôi phục cần task riêng"), `DV-02-05-su-kien-keycloak.md`, `NEN-043-sua-hop-dong-nen-tang.md`; `wiki/07-xac-thuc-phan-quyen.md`.

## 3. Phạm vi công việc

- Hiện thực `issueMfaRecoveryCode` trong dịch vụ `iam`: chỉ `tenant_admin` cùng tenant, phiên AAL2 (BR-00-01), không tự cấp cho chính mình (tách vai trò, suy luận); bắt buộc trường lý do và số văn bản hay người xác minh của đơn vị; sinh mã ngẫu nhiên đủ entropy (ví dụ 16 ký tự chữ số và chữ, suy luận), chỉ trả về một lần trong phản hồi, lưu băm Argon2id hoặc PBKDF2 dưới dạng credential của người dùng trong Keycloak, hạn dùng ngắn (24 giờ, suy luận, tham số hoá); cấp mã mới thì thu hồi mã cũ.
- Hiện thực `revokeMfaRecoveryCodes` (quản trị tenant hoặc chính người dùng sau khi đã đăng ký lại MFA).
- Viết authenticator SPI "Mã khôi phục" làm lựa chọn "Phương thức khác" trong luồng MFA của DV-02-03: nhập đúng thì xoá credential (dùng một lần), đặt required action đăng ký lại OTP hoặc WebAuthn, phiên tạo ra ở mức AAL1 cho tới khi đăng ký lại xong (không thoả step-up AAL2 bằng mã khôi phục, suy luận để giữ BR-00-01); nhập sai tính vào bộ đếm khoá tài khoản sau 5 lần sai của realm.
- Phát `iam.auth.event.v1` cho các lần cấp, dùng, dùng sai, thu hồi qua event listener của DV-02-05, và sự kiện `sys.audit.v1` mang tác nhân quản trị, người được cấp, lý do để SVC-09 ghi sổ cái; không ghi mã hay băm vào nhật ký.
- Thông báo cho người dùng qua kênh đã đăng ký khác (email công vụ) khi có mã được cấp cho tài khoản của họ (EVT-21, suy luận).
- Kiểm thử Testcontainers Keycloak 26: cấp, dùng một lần, dùng lại bị từ chối, hết hạn bị từ chối, quản trị ở AAL1 bị 403, quản trị tenant khác bị 403; sự kiện kiểm toán có đủ trường.

## 4. Ngoài phạm vi

- Luồng MFA, step-up, cấu hình LoA (DV-02-03); màn hình quản trị cấp mã (WEB); quy trình xác minh danh tính tại đơn vị (nghiệp vụ, ngoài hệ thống).

## 5. Tệp được phép sửa

- `backend/services/iam/**` (API cấp và thu hồi, authenticator SPI, kiểm thử)
- `deploy/charts/iam/**` (bổ sung bước vào luồng xác thực trong mẫu realm)
- `wiki/07-xac-thuc-phan-quyen.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/iam -am verify` chạy xanh gồm các ca kiểm thử mục 3 và kiểm thử hợp đồng cho hai `operationId`.
- Kiểm thử chứng minh mã chỉ xuất hiện trong đúng một phản hồi HTTP, không có trong CSDL dạng rõ, nhật ký hay sự kiện.
- `helm lint deploy/charts/iam` không lỗi; báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
