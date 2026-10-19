# LEG-09 · Đợt 1/SVC-02, SVC-04 Hệ cũ đăng nhập qua Keycloak và đọc tổ chức qua org-service, ngừng truy vấn bảng `VHR_*`

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh LEG-09` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 – định danh, tổ chức và tích hợp |
| Luồng đề xuất | B11 |
| Cỡ việc | L (> 1 ngày, nên tách: xem cuối mục 3) |
| Phụ thuộc | LEG-06, DV-02-03, DV-04-04 (bổ sung hiển nhiên: nhóm DV-13 cho kết nối `JDBC_RO` đọc `VHR_ORG`, `VHR_EMPLOYEE` phát EVT-14..EVT-16) |
| Truy vết | S06, S12, S14, K01, K02, L01, L03; ADR-20; TL-01 "Lộ trình chuyển đổi…", mục "Đợt 1" → API `GET /api/v1/org/units/tree` (`getOrgTree`), `GET /api/v1/org/units/{id}` (`getOrgUnit`), `GET /api/v1/org/persons` (`listOrgPersons`), `GET /api/v1/org/persons/{id}/assignments` (`listPersonAssignments`) → SVC-02, SVC-04, SVC-13 → bảng `org.org_unit`, `org.person`, `org.assignment`; Oracle `VHR_ORG`, `VHR_EMPLOYEE`, `USER_TOKENS` → EVT-14, EVT-15, EVT-16 |
| Trạng thái | Bị chặn bởi bên ngoài: chưa có quyền truy cập hệ cũ |

## 1. Mục tiêu

Hệ cũ không còn tự xác thực và không còn đọc thẳng bảng nhân sự, tổ chức của vOffice: người dùng đăng nhập hệ cũ qua Keycloak (OIDC, có MFA cho vai trò quản trị và lãnh đạo), hệ cũ đọc cây tổ chức và phân công qua API của org-service, và integration-gateway là thành phần duy nhất có tài khoản đọc vOffice. Điều kiện chuyển bước đợt 1: không còn truy vấn mới vào `VHR_*` trong hai tuần liên tiếp, 100% người dùng đăng nhập qua Keycloak.

## 2. Đầu vào bắt buộc đọc

- `docs/quy-dinh/BACKEND.md`, `docs/quy-dinh/HA-TANG.md`.
- TL-01 `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md`: "Lộ trình chuyển đổi theo Strangler Fig", mục "Đợt 1: định danh, tổ chức và cổng tích hợp" (dòng 3162); mục "SVC-04 org-service" (thay việc đọc `VHR_ORG`, `VHR_EMPLOYEE`).
- TL-03 `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md`, "Di trú từ hệ hiện tại theo mẫu Strangler Fig" (giai đoạn 1 và 3: `JDBC_RO`, chuyển xác thực từ `USER_TOKENS`).
- TL-04 `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md`, "Đợt 1: định danh, tổ chức và tích hợp" (dòng 3929: cách đo bằng audit Oracle, `pg_stat_statements`).
- Hợp đồng `contracts/openapi/modules/org.yaml`; `wiki/07-xac-thuc-phan-quyen.md`, `wiki/15-phan-quyen.md`.
- Đầu vào người dùng cần cấp:
  - Quyền ghi các repo hệ cũ (cả giao diện ZK cũ cho phần đăng nhập, đăng xuất).
  - Đăng ký client OIDC cho hệ cũ trên realm Keycloak của môi trường thử và production; quyết định nhóm người dùng chuyển trước.
  - Quyền bật audit truy vấn trên Oracle (hoặc DBA thực hiện) để đo truy vấn vào `VHR_*`.
  - Phương án chuyển mật khẩu hoặc buộc đặt lại mật khẩu khi chuyển từ `USER_TOKENS` sang Keycloak (người dùng quyết định).

## 3. Phạm vi công việc

- Thay cơ chế đăng nhập của hệ cũ bằng OIDC Code + PKCE với Keycloak; backend hệ cũ thành resource server kiểm JWT qua JWKS; đăng xuất qua `end_session_endpoint`; ngừng ghi, đọc `USER_TOKENS`.
- Ánh xạ vai trò hệ cũ sang vai trò trong ma trận phân quyền (`wiki/15a`), không tự đặt danh sách vai trò mới; bảo đảm MFA bật cho vai trò quản trị và lãnh đạo (DV-02-03).
- Thay mọi truy vấn `VHR_ORG`, `VHR_EMPLOYEE` trong hệ cũ (danh sách từ LEG-01, DL-09) bằng client gọi API org-service có timeout, circuit breaker, bộ đệm ngắn; chuyển định danh người, đơn vị qua `integ.mapping_xref`.
- Thu hồi quyền đọc `VHR_*` của tài khoản Oracle hệ cũ sau khi chạy song song ổn định; chỉ tài khoản của integration-gateway còn quyền đọc vOffice.
- Thiết lập đo: audit Oracle hoặc giám sát truy vấn báo mọi câu mới chạm `VHR_`; bảng Grafana tỷ lệ đăng nhập qua Keycloak.
- Chuyển theo nhóm người dùng, có cờ quay về đăng nhập cũ trong thời gian chuyển tiếp.
- Gợi ý tách: LEG-09a đăng nhập Keycloak; LEG-09b đọc tổ chức qua org-service; LEG-09c thu hồi quyền `VHR_*` và đo hai tuần.

## 4. Ngoài phạm vi

- Realm, MFA, org-service, connector vOffice phía nền mới (DV-02-xx, DV-04-xx, DV-13-xx); chuyển thông báo `NOTIFICATION`/`SMS_MASTER` (LEG-11).

## 5. Tệp được phép sửa

- Mã xác thực, client tổ chức, cấu hình trong các repo hệ cũ (đường dẫn do người dùng cấp)
- `deploy/environments/<môi-trường>/legacy/**`
- `ops/runbooks/he-cu-dang-nhap-keycloak.md`

## 6. Tiêu chí hoàn thành

- Kiểm thử của các repo hệ cũ xanh, có kiểm thử tích hợp đăng nhập với Keycloak (Testcontainers) và client org-service với máy chủ giả lập theo `org.yaml`.
- Trên production: 100% đăng nhập qua Keycloak trong một tuần; MFA bật cho mọi vai trò quản trị, lãnh đạo.
- Giám sát Oracle hai tuần liên tiếp không có truy vấn mới chạm `VHR_*` từ tài khoản hệ cũ.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
