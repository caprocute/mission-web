# DV-03-04 · QT-01 Saga cấp phát tenant phần 1: CSDL hoặc schema, đăng ký pool, KEK trong HSM

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-03-04` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 |
| Luồng đề xuất | B5 |
| Cỡ việc | L |
| Phụ thuộc | DV-03-03, DV-06-05; INF-11 (cụm PostgreSQL của cell), INF-13 (OpenBao, HSM) cho kiểm thử trên cụm (suy luận) |
| Truy vết | QT-01 bước 4 → SCR-0102 → `POST /api/v1/config/tenants/{id}/provision` (`provisionConfigTenant`, 202, job `PROVISION`) → SVC-03 khởi tạo, SVC-06 điều phối → bảng `registry.tenant`, `registry.tenant_cell`, `flowable.saga_instance`, `flowable.saga_step`; ADR-07, ADR-17, ADR-30; NFR-07 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Khi quản trị nền tảng bấm "Tiếp tục" ở cuối bước 3 của wizard, hệ thống khởi chạy saga cấp phát và tự làm phần hạ tầng dữ liệu: với bridge tạo CSDL logic riêng và chạy Flyway, với pool đăng ký `tenant_id` và tạo phân vùng, với mọi mô hình tạo KEK `kek-<tenant>` và khoá HMAC chỉ mục mù trong HSM qua OpenBao. Mỗi bước idempotent, thử lại tối đa 3 lần có lùi dần, lỗi thì bù trừ ngược để tenant về `KHOI_TAO`. Khi xong, ngân sách thời gian đạt ≤ 5 phút cho pool và ≤ 2 giờ cho bridge (TL-02a).

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-01 bước 4 (dòng 512), luồng saga và bù trừ (dòng 519), ngoại lệ saga lỗi (dòng 568), MSG-01-06, MSG-01-07.
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Tiếp nhận tenant" (dòng 2722) và ADR-30 "DEK theo tenant, tháng và mục đích với chỉ mục mù" (dòng 3682).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` mục "Mô hình thuê bao và phân bổ cơ sở dữ liệu theo cell" (dòng 619–684), "Định tuyến tới đúng CSDL" (dòng 756), "Schema flowable và saga (SVC-06)" (dòng 3227) và "Mã hoá dữ liệu" (dòng 4142).
- Hợp đồng: `contracts/openapi/modules/config.yaml` `provisionConfigTenant`; `contracts/openapi/modules/jobs.yaml` `getJob`.
- Wiki: `wiki/08-bao-mat-frontend.md` (phân lớp dữ liệu), `wiki/12-ha-tang-dev-va-trien-khai.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 3. Phạm vi công việc

- Hiện thực `provisionConfigTenant` (Idempotency-Key, `If-Match`, AAL2): kiểm cấu hình bước 1–3 hợp lệ, chuyển `KHOI_TAO` → `DANG_CAP_PHAT`, khởi chạy saga cấp phát bằng khung saga của DV-06-05, trả 202 với job loại `PROVISION`.
- Bước "CSDL": với bridge tạo CSDL logic `td_<code>` trên cụm của cell, tạo vai trò `svc_/ro_/mig_/cdc_` theo quy ước, chạy Flyway mọi schema; với pool chỉ đăng ký tenant và gọi `fn_create_period_partitions` cho phân vùng của tenant (suy luận theo TL-01 "Tiếp nhận tenant": phân vùng LIST cho pool); ghi `registry.tenant_cell` (`database_name`, `valid_from`).
- Bước "Khoá": tạo KEK `kek-<tenant>` trong HSM qua OpenBao Transit và khoá HMAC chỉ mục mù; ghi `registry.tenant.kek_ref`; silo bắt buộc KEK riêng (TL-02a dòng 634).
- Mỗi bước có hành động bù trừ: xoá CSDL còn rỗng, huỷ khoá chưa dùng, gỡ đăng ký pool; bù trừ chạy theo thứ tự ngược và đưa tenant về `KHOI_TAO`, ghi lý do để giao diện hiện MSG-01-07.
- Bước thực thi là external worker trong tenant-registry (Go) lấy việc từ workflow-service; lệnh tạo CSDL đi qua tài khoản quản trị riêng có quyền hẹp, không dùng tài khoản ứng dụng.
- Kiểm thử: Testcontainers PostgreSQL + OpenBao dev cho bước CSDL và khoá; ca lỗi giữa chừng chứng minh bù trừ đủ và tenant về `KHOI_TAO`; gọi lại cùng `Idempotency-Key` không tạo saga thứ hai; đo thời gian pool.
- Điểm lệch cần lưu ý: bảng `flowable.saga_instance` của TL-03 chỉ có `saga_type` `CHOT_KY`, `MO_LAI_HAU_KIEM`, `TOT_NGHIEP_TENANT`, `DONG_GOI_HO_SO`, không có giá trị cho cấp phát tenant, trong khi TL-02a và hợp đồng ghi saga cấp phát do SVC-06 điều phối; không tự đặt giá trị, hỏi người phụ trách chốt (dùng một giá trị hiện có hay bổ sung giá trị mới bằng migration mở rộng), ghi vào wiki trang 14 trước khi viết migration.
- Gợi ý tách nếu quá lớn: (a) bước CSDL bridge và pool; (b) bước khoá và bù trừ.

## 4. Ngoài phạm vi

- Organization Keycloak, tên miền, TLS, sao chép gói, lịch nghỉ, kích hoạt (DV-03-05).
- Khung saga dùng chung (DV-06-05); dựng cụm PostgreSQL và OpenBao (INF-11, INF-13).

## 5. Tệp được phép sửa

- `services-go/tenant-registry/**`
- `backend/services/workflow-service/src/main/resources/**` (chỉ định nghĩa saga cấp phát nếu khung DV-06-05 yêu cầu khai báo ở đây)
- `deploy/charts/tenant-registry/**`
- `ops/runbooks/` (thêm mục xử lý saga cấp phát kẹt)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd services-go/tenant-registry && go test ./... && govulncheck ./...` chạy xanh với Testcontainers; nếu sửa workflow-service thì `cd backend && mvn -q -pl services/workflow-service -am verify` cũng xanh.
- Ca lỗi ở mỗi bước đều bù trừ sạch (không còn CSDL rỗng, khoá treo); đo thời gian saga pool ≤ 5 phút trên môi trường kiểm thử.
- Không có khoá hay mật khẩu CSDL nào trong nhật ký hoặc trong biến saga (biến tiến trình chỉ mang id).
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
