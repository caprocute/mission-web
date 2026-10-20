# CORE-08-17 · QT-16/SCR-1602 Lưu định nghĩa dashboard `config.dashboard_def` và cổng cho analytics-api

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-08-17` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B2 |
| Cỡ việc | S |
| Phụ thuộc | CORE-08-01 (bảng `config.dashboard_def`); NEN-006, NEN-013, NEN-014 (sinh giao diện, If-Match, OPA) (bổ sung) |
| Truy vết | QT-16 bước 6–7 → SCR-1602 → API công bố `/api/v1/analytics/dashboards*` (owner SVC-15, DV-15-04) → cổng dịch vụ đề xuất `/api/v1/config/dashboard-defs*` (`roles: [service_client]`, chưa có trong hợp đồng) → MOD-08 → bảng `config.dashboard_def` → EVT-21 (do SVC-15 phát khi chia sẻ) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

TL-03 đặt định nghĩa dashboard tự dựng ở schema `config` của mission-core (cần RLS và sao lưu cùng dữ liệu tenant), còn hợp đồng giao năm thao tác dashboard cho analytics-api (SVC-15) và TL-03 chương 2 không cho một service ghi schema của module khác. Task này làm phần của MOD-08: kho định nghĩa dashboard có kiểm quy tắc trường của SCR-1602 và một cổng gọi từ dịch vụ để analytics-api đọc, ghi định nghĩa thay vì kết nối thẳng CSDL. Khi xong, DV-15-04 hiện thực được `listDashboards`, `createDashboard`, `getDashboard`, `updateDashboard`, `shareDashboard` mà dữ liệu vẫn nằm đúng chủ sở hữu.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "Bảng config.dashboard_def (bổ sung)" (L1661–1675), chương 2 sở hữu dữ liệu theo service (L143–151).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-16 bước 6–7 (L1649–1650), bảng trường SCR-1602 (L1748–1756: tên ≤ 100 ký tự không trùng trong không gian người dùng, tối đa 20 widget, tối đa 2 chiều, chia sẻ chỉ nhóm trong tenant).
- `contracts/openapi/modules/platform.yaml` năm thao tác `/analytics/dashboards*` (lược đồ dashboard, `share_scope` `RIENG`, `DON_VI`, `TENANT`); tiền lệ thao tác dịch vụ `resolvePolicyPack` trong `policies.yaml` (`roles: [service_client]`).
- Thẻ `docs/tasks/DV-15-04-analytics-api.md` (điểm lệch về chủ sở hữu), `CORE-08-01-migration-schema-config.md`.

## 3. Phạm vi công việc

- Bước đầu, trước khi viết mã: thêm vào `contracts/openapi/modules/config.yaml` nhóm thao tác dịch vụ `GET/POST /config/dashboard-defs`, `GET/PUT /config/dashboard-defs/{id}` (`listDashboardDefs`, `createDashboardDef`, `getDashboardDef`, `replaceDashboardDef`), `owner: MOD-08`, `roles: [service_client]`, nhận mã người dùng thực hiện trong thân hoặc header uỷ quyền; chỉ thêm, chạy `npm run lint` và `openapi-diff`, chờ người phụ trách hợp đồng duyệt.
- Hiện thực domain `DashboardDefinition` trong `config`: kiểm tiêu đề bắt buộc ≤ 100 ký tự và không trùng theo (`tenant_id`, `owner_user_id`, `title`); `layout_json` tối đa 20 widget, mỗi widget đúng một chỉ số và tối đa 2 chiều; `share_scope` thuộc `RIENG`, `DON_VI`, `TENANT`, `shared_unit_ids` bắt buộc khi `DON_VI`; tên chỉ số, chiều chỉ kiểm cú pháp (danh mục ngữ nghĩa là của Cube, analytics-api kiểm, suy luận).
- Ghi qua `If-Match` theo `row_version`, chỉ người tạo được sửa (theo mô tả `updateDashboard`); danh sách trả dashboard của chính người dùng và dashboard được chia sẻ theo phạm vi đơn vị của người đó (lọc trên cây đơn vị qua `org_replica`).
- Công bố cổng trong mô-đun `config.api.DashboardDefinitionApi` để module khác (nếu cần) dùng in-process, và lớp REST cho thao tác dịch vụ ở trên, xác thực bằng token client credentials của analytics-api (OPA kiểm `service_client` và tenant khớp).
- Không lưu dữ liệu số liệu trong bảng, không lưu C2; ghi sổ cái khi đổi phạm vi chia sẻ (sự kiện kiểm toán `sys.audit.v1` qua outbox, suy luận).
- Kiểm thử tích hợp Testcontainers: tạo, sửa, chia sẻ; vi phạm từng quy tắc trường trả 422 có `errors[]`; `If-Match` lệch trả 409; hai tenant không thấy dashboard của nhau; người khác người tạo sửa bị 403.

## 4. Ngoài phạm vi

- Năm thao tác công bố, tính số liệu từ Cube, phát EVT-21 khi chia sẻ (DV-15-04); màn hình SCR-1602 (WEB).
- Migration tạo bảng (CORE-08-01).

## 5. Tệp được phép sửa

- `contracts/openapi/modules/config.yaml` (chỉ thêm nhóm `/config/dashboard-defs*` sau khi được duyệt)
- `backend/mission-core/src/main/java/vn/viettel/okrkpi/config/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/config/**`
- `frontend/projects/api-client/src/lib/generated/**` (chỉ sinh lại bằng `npm run api:gen`)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd contracts && npm run lint` xanh, `openapi-diff` chỉ báo thêm thao tác; `cd frontend && npm run api:check` xanh.
- `cd backend && mvn -q -pl mission-core -am verify` chạy xanh gồm kiểm thử mục 3, ArchUnit/Modulith (không module nào ngoài `config` truy cập bảng), kiểm thử hợp đồng cho bốn `operationId` mới.
- Báo cáo nêu rõ phương án chủ sở hữu đã chọn và kết quả duyệt hợp đồng; báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
