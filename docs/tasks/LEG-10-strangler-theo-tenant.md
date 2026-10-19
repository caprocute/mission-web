# LEG-10 · ADR-20 Chuyển tuyến theo Strangler Fig theo route và theo tenant, bắt đầu từ danh mục công việc

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh LEG-10` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 – lõi nghiệp vụ trên nền tảng mới |
| Luồng đề xuất | B11 |
| Cỡ việc | M (≤ 1 ngày cho khung và chức năng đầu tiên; mỗi chức năng sau là một task riêng) |
| Phụ thuộc | LEG-09, CORE-02-03 (bổ sung hiển nhiên: DV-01-01 cho ApisixRoute và claim tenant, DV-13-01 cho `mapping_xref`, DL-09 cho ánh xạ bảng danh mục) |
| Truy vết | ADR-20; TL-01 "Chuyển dần theo Strangler Fig với APISIX" → API `/api/v1/catalog/*` (`listWorkCatalogs`, `listCatalogVersionItems`, `addCatalogItem`, `updateCatalogItem`) → SVC-01, MOD-02, SVC-13, SVC-03 → bảng `task.work_catalog`, `task.catalog_item`, `integ.mapping_xref`, `config.feature_flag`; Oracle (bảng cấu hình, danh mục của hệ cũ) |
| Trạng thái | Bị chặn bởi bên ngoài: chưa có quyền truy cập hệ cũ |

## 1. Mục tiêu

Có cơ chế chuyển từng chức năng từ hệ cũ sang nền mới theo tenant mà không gián đoạn: cùng một đường dẫn có hai tuyến APISIX, tuyến mới ưu tiên cao chỉ khớp tenant đã chuyển, tuyến cũ cho phần còn lại; danh sách tenant sinh từ tenant-registry và cờ tính năng, cập nhật qua Argo CD; dữ liệu đồng bộ hai chiều trong thời gian chuyển tiếp. Chức năng đầu tiên chuyển là danh mục công việc, đúng thứ tự TL-01 (danh mục và cấu hình → đăng ký và giao việc → đánh giá và xếp loại → bảng điều hành).

## 2. Đầu vào bắt buộc đọc

- `docs/quy-dinh/BACKEND.md`, `docs/quy-dinh/HA-TANG.md`.
- TL-01 `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md`: "Chuyển dần theo Strangler Fig với APISIX" (dòng 1428–1459: ví dụ `strangler-catalog`, `priority: 10` với biến `jwt_tenant`, upstream `backend-missions-legacy`, đồng bộ hai chiều, xoá mã cũ), "ADR-20".
- TL-03 `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md`, "Di trú từ hệ hiện tại theo mẫu Strangler Fig" (giai đoạn 2: Debezium Oracle LogMiner chiều cũ sang mới, SVC-13 ghi ngược).
- Hợp đồng `contracts/openapi/modules/tasks.yaml` (nhóm `/catalog*`), `contracts/openapi/modules/platform.yaml` (`/registry/*`), `contracts/openapi/modules/config.yaml` (`/config/feature-flags*`).
- Kết quả DL-09 (ánh xạ bảng danh mục cũ), LEG-06 (APISIX trước hệ cũ).
- Đầu vào người dùng cần cấp:
  - Quyền ghi các repo hệ cũ (nếu cần thêm điểm ghi ngược hoặc đổi đường dẫn API cũ) và quyền bật LogMiner hoặc supplemental logging trên Oracle cho Debezium.
  - Danh sách tenant thí điểm và lịch chuyển do người dùng chốt.

## 3. Phạm vi công việc

- Viết cặp `ApisixRoute` cho `/api/v1/catalog/*`: tuyến mới `priority: 10` khớp `jwt_tenant` thuộc danh sách tenant đã chuyển, trỏ `mission-core`; tuyến cũ `priority: 1` trỏ `backend-missions-legacy`; nếu đường dẫn API cũ khác, thêm lớp chuyển đổi ở APISIX hoặc integration-gateway (ghi rõ).
- Viết công cụ sinh danh sách tenant từ tenant-registry và cờ tính năng (`config.feature_flag`), đưa vào giá trị Argo CD; thay đổi danh sách là một commit GitOps có duyệt.
- Viết connector đồng bộ hai chiều cho danh mục công việc trong integration-gateway: chiều cũ sang mới bằng Debezium Oracle (LogMiner), chiều mới sang cũ bằng ghi ngược; dùng `mapping_xref` để chống vòng lặp và khử trùng.
- Chạy song song một kỳ cho tenant thí điểm, so sánh dữ liệu hai phía bằng job R8 (DL-12) theo bảng danh mục.
- Viết runbook chuyển một tenant (thêm vào danh sách, kiểm chứng, quay lui bằng cách bỏ tenant khỏi danh sách) và mẫu áp dụng cho các chức năng tiếp theo.
- Lập kế hoạch xoá mã cũ và đóng băng bảng khi mọi tenant của chức năng đã chuyển và ổn định một kỳ đánh giá (K8), chỉ lập kế hoạch, không xoá trong task này.

## 4. Ngoài phạm vi

- Chuyển các chức năng tiếp theo (đăng ký và giao việc, đánh giá, bảng điều hành): mỗi chức năng một task theo mẫu này; chuyển tenant theo nhóm và tắt hệ cũ (LEG-11).

## 5. Tệp được phép sửa

- `deploy/environments/<môi-trường>/strangler/**` (ApisixRoute, danh sách tenant)
- `backend/services/integration-gateway/**/strangler/**` (connector đồng bộ hai chiều danh mục) và kiểm thử
- Mã hệ cũ liên quan điểm ghi ngược (đường dẫn do người dùng cấp)
- `ops/runbooks/strangler-chuyen-tenant.md`

## 6. Tiêu chí hoàn thành

- `helm template` và `kubeconform` cho route mới không lỗi; kiểm thử định tuyến: token tenant đã chuyển vào `mission-core`, tenant khác vào hệ cũ.
- `cd backend && mvn -q -pl services/integration-gateway -am verify` xanh, có kiểm thử Testcontainers (Oracle Free, PostgreSQL 17, Kafka) chứng minh sửa một mục ở mỗi phía được đồng bộ sang phía kia đúng một lần, không vòng lặp.
- Trên môi trường thử: chuyển rồi quay lui một tenant không mất dữ liệu; R8 cho bảng danh mục = 0.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
