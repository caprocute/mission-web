# DV-03-05 · QT-01 Saga cấp phát tenant phần 2: Organization Keycloak, tên miền và TLS, Policy Pack và UI Pack, lịch mặc định, kích hoạt

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-03-05` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 |
| Luồng đề xuất | B5 |
| Cỡ việc | L |
| Phụ thuộc | DV-03-04, DV-02-01 |
| Truy vết | QT-01 bước 4–9 → SCR-0102 → `POST /api/v1/config/tenants/{id}/activate` (`activateConfigTenant`), tiếp nối saga của `provisionConfigTenant` → SVC-03, SVC-02, SVC-06 → bảng `registry.tenant_domain`, `registry.tenant_idp`, `policy.policy_binding`, `config.ui_pack_version`, `config.holiday_calendar` → EVT-21; BR-01-03, BR-01-04, BR-01-10; MSG-01-05, MSG-01-06, MSG-01-12 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hoàn tất saga cấp phát sau phần dữ liệu: tạo Organization của tenant trong Keycloak của cell, đăng ký tên miền và cấp chứng chỉ TLS tự động, sao chép Policy Pack và UI Pack mặc định của khối xuống cell, tạo lịch nghỉ mặc định, rồi đưa tenant sang `DANG_CAU_HINH`; sau khi tenant cấu hình xong, kiểm tra tổng thể và kích hoạt có MFA. Khi xong, một tenant pool đi từ khai báo tới `DANG_VAN_HANH` không cần thao tác tay ngoài wizard, đáp ứng NFR-07 (≤ 1 ngày).

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-01 bước 4–9 (dòng 512–517), saga và bù trừ (dòng 519, 568), BR-01-03, BR-01-04, BR-01-10, ngoại lệ RPO (dòng 563), SCR-0102 (dòng 610–654).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Tiếp nhận tenant" (dòng 2722: thứ tự bước, rate limit, smoke test, `sys.tenant.provisioned.v1`) và mục "Cô lập tài nguyên và giới hạn theo tenant" (dòng 2726).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` mục "GeoDNS và định tuyến tenant tới cell" (dòng 166–193: CNAME tenant do OPS-07 tạo tự động).
- Hợp đồng: `contracts/openapi/modules/config.yaml` `provisionConfigTenant`, `activateConfigTenant` (mã `CONFIG-422-HIGH-DISCREPANCY`, `CONFIG-409-TENANT-STATE`).
- Wiki: `wiki/03-giao-dien-theo-tenant.md`, `wiki/07-xac-thuc-phan-quyen.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 3. Phạm vi công việc

- Bước "Organization": tạo Organization Keycloak cho tenant qua Admin API mạng quản trị, gắn IdP liên kết đã khai báo ở `registry.tenant_idp`; bù trừ là xoá Organization.
- Bước "Tên miền": ghi `registry.tenant_domain`, yêu cầu tạo bản ghi CNAME tới điểm vào cell (theo OPS-07) và chứng chỉ TLS qua cert-manager; ghi `tls_cert_ref`, `verified_at`; bù trừ là thu hồi tên miền.
- Bước "Gói": gắn Policy Pack theo từng luồng của tenant vào `policy.policy_binding` của cell qua API nội bộ của MOD-05; chỉ nhận phiên bản `DA_PHAT_HANH` đạt 100% test case vàng (BR-01-03, MSG-01-05); tenant hai luồng phải đủ một gói mỗi luồng (BR-01-04); gắn UI Pack mặc định khối.
- Bước "Lịch": tạo `config.holiday_calendar` mặc định qua API nội bộ MOD-08; cấu hình hạn mức `registry.tenant_quota` cho cổng; chạy smoke test; khi xong chuyển `DANG_CAU_HINH`, gửi thư đầu mối (EVT-21, MSG-01-06) và phát `sys.tenant.provisioned.v1` qua outbox.
- Hiện thực `activateConfigTenant` (AAL2, Idempotency-Key): kiểm tổng thể (dung lượng, tên miền, chứng chỉ, Policy Pack đạt test vàng, IdP hoạt động theo kết quả DV-03-06, lệch mức Cao = 0 lấy từ org-service), chặn với MSG-01-12 khi còn lệch Cao (BR-01-10), cảnh báo hoặc chặn khi độ trễ bản sao dự phòng vượt RPO 15 phút; chuyển `SAN_SANG` → `DANG_VAN_HANH` (hoặc `TAM_NGUNG` → `DANG_VAN_HANH`), phát EVT-21.
- Kiểm thử: Testcontainers Keycloak cho bước Organization; mô phỏng MOD-05, MOD-08, org-service bằng máy chủ giả theo hợp đồng; ca bù trừ toàn bộ từ bước cuối; ca kích hoạt bị chặn vì lệch Cao.
- Điểm lệch cần lưu ý: sự kiện `sys.tenant.provisioned.v1` (TL-01) là sự kiện kỹ thuật, không thuộc danh mục EVT của baseline và hợp đồng ghi "không phát EVT nghiệp vụ"; giữ là topic kỹ thuật, không gán mã EVT.
- Gợi ý tách nếu quá lớn: (a) Organization + tên miền + TLS; (b) gói + lịch + smoke test; (c) kích hoạt và kiểm tra tổng thể.

## 4. Ngoài phạm vi

- Phần CSDL và khoá (DV-03-04); khai báo và thử IdP (DV-03-06); nhập cây tổ chức và đối soát (DV-04-05, DV-04-07).
- Màn hình wizard (WEB-017).

## 5. Tệp được phép sửa

- `services-go/tenant-registry/**`
- `deploy/charts/tenant-registry/**`
- `ops/runbooks/` (mục xử lý kích hoạt bị chặn, chứng chỉ chưa cấp)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd services-go/tenant-registry && go test ./... && govulncheck ./...` chạy xanh; kiểm thử hợp đồng `activateConfigTenant` đạt.
- Kịch bản đầu cuối trên môi trường kiểm thử: tenant pool đi từ `KHOI_TAO` tới `DANG_CAU_HINH` ≤ 5 phút; ca lỗi ở bước gói bù trừ sạch về `KHOI_TAO`.
- Kích hoạt khi còn lệch Cao trả 422 đúng mã; kích hoạt không có AAL2 trả 401.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
