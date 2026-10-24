# DV-03-06 · QT-01/SCR-0102 Khai báo IdP của tenant và kiểm kết nối OIDC, SAML, LDAP

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-03-06` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 |
| Luồng đề xuất | B5 |
| Cỡ việc | S |
| Phụ thuộc | DV-03-03 |
| Truy vết | QT-01 bước 5 → SCR-0102 → `PATCH /api/v1/config/tenants/{id}` (bước 4 IdP), `POST /api/v1/config/tenants/{id}/idp/test` (`testConfigTenantIdp`) → SVC-03 → bảng `registry.tenant_idp`; ADR-08 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Quản trị tenant khai báo IdP của cơ quan mình (OIDC, SAML, LDAP) ở bước IdP của wizard và bấm "Kiểm tra kết nối" để biết cấu hình có dùng được không trước khi kích hoạt. Khi xong, cấu hình IdP được lưu đúng bảng, kết quả kiểm tra được ghi lại để bước kiểm tra tổng thể của DV-03-05 đọc, và không bí mật nào của IdP lộ ra phản hồi API hay nhật ký.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-01 bước 5 và SCR-0102 (dòng 610–654), tích hợp IdP (dòng 746).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` bảng `registry.tenant_idp` (dòng 1003–1015).
- Hợp đồng: `contracts/openapi/modules/config.yaml` `updateConfigTenant` (phần IdP), `testConfigTenantIdp`.
- Wiki: `wiki/07-xac-thuc-phan-quyen.md`, `wiki/15a-ma-tran-phan-quyen.md` (dòng SCR-0102), `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 3. Phạm vi công việc

- Lưu cấu hình IdP vào `registry.tenant_idp` (`idp_alias`, `protocol`, `issuer`, `is_vneid`, `email_domains`, `status`); bí mật client hoặc chứng chỉ ký SAML lưu trong OpenBao, bảng chỉ giữ tham chiếu.
- Hiện thực `testConfigTenantIdp` cho OIDC: tải tài liệu discovery của `issuer`, kiểm JWKS, kiểm endpoint, thời gian phản hồi; không đổi trạng thái tenant, ghi kết quả và thời điểm kiểm để bước 6 đọc.
- Kiểm SAML: tải và kiểm metadata (chữ ký, chứng chỉ còn hạn, endpoint SSO).
- Kiểm LDAP: kết nối LDAPS, bind bằng tài khoản dịch vụ chỉ đọc, tìm thử một mục; chỉ chấp nhận kết nối có TLS.
- Chặn gọi tới địa chỉ nội bộ khi kiểm (chống SSRF): chỉ cho phép đích HTTPS hoặc LDAPS công khai theo danh sách cho phép của môi trường.
- Kiểm thử: máy chủ OIDC và SAML giả trong kiểm thử, LDAP giả bằng container; ca discovery hỏng, chứng chỉ hết hạn, đích nội bộ bị chặn.
- Điểm lệch cần lưu ý: TL-02a ghi IdP cơ quan gồm OIDC, SAML, LDAP, nhưng TL-03 chỉ cho `tenant_idp.protocol` hai giá trị `OIDC`, `SAML`. Không sửa CHECK trong task này; nếu cần lưu LDAP, đề nghị bổ sung giá trị bằng migration mở rộng và ghi vào wiki trang 14 để người phụ trách chốt, trong lúc chờ chỉ hiện thực kiểm kết nối LDAP mà không lưu dòng LDAP.

## 4. Ngoài phạm vi

- Gắn IdP vào Organization Keycloak (DV-03-05); VNeID (DV-02-04).
- Màn hình bước IdP (WEB-017).

## 5. Tệp được phép sửa

- `services-go/tenant-registry/**`
- `deploy/charts/tenant-registry/**` (NetworkPolicy egress cho kiểm IdP)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd services-go/tenant-registry && go test ./... && govulncheck ./...` chạy xanh; kiểm thử hợp đồng `testConfigTenantIdp` đạt.
- Phản hồi và nhật ký không chứa client secret hay khoá riêng.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
