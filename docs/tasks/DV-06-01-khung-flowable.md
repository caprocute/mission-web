# DV-06-01 · SVC-06/ADR-04 Khung workflow-service: Flowable 7 nhúng Spring Boot, CSDL riêng, lịch sử mức audit, tách API và async executor

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-06-01` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B6 |
| Cỡ việc | L |
| Phụ thuộc | NEN-019; NEN-031 (vai trò CSDL) nên có trước (suy luận) |
| Truy vết | ADR-04 tầng 2, tầng 3 → SVC-06 workflow-service → CSDL riêng của engine, bảng `ACT_*`, `FLW_*`; TL-01 chương 8 "Chuyên đề G2"; TL-04 WI-31 (PERF-11) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Dựng workflow-service chạy Flowable 7 nhúng trong Spring Boot 3.5 với cấu hình chuẩn của TL-01 cho quy mô hàng nghìn tenant: CSDL riêng, DDL engine quản lý bằng Flyway thay vì tự cập nhật, lịch sử mức `audit`, dọn lịch sử định kỳ, cùng một image chạy hai vai trò Deployment `workflow-api` (không bật async executor) và `workflow-executor` (chỉ chạy job). Khi xong, các task DV-06 sau chỉ việc thêm bảng, API và mẫu BPMN.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SVC-06 workflow-service" (dòng 1048–1070) và chương "Chuyên đề G2: BPMN ở quy mô hàng trăm, hàng nghìn tenant" (dòng 1815–2159), đặc biệt "Cấu hình Flowable cụ thể" (dòng 2025–2078, YAML dòng 2029–2060) và "Chia theo cell" (dòng 2084–2113).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` mục "SVC-06 workflow-service" (dòng 488) và "Schema flowable và saga (SVC-06)" (dòng 3227–3240, tinh chỉnh `ACT_*`).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` mục "Co giãn workflow-service và async executor của Flowable" (dòng 1502–1565).
- Wiki: `wiki/12-ha-tang-dev-va-trien-khai.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 3. Phạm vi công việc

- Sinh dịch vụ bằng `scripts/new-service.sh`, thêm Flowable 7 Spring Boot starter (process, DMN), tắt IDM và event registry; giao diện API sinh từ tag Workflow của `contracts/openapi/modules/platform.yaml`.
- Đặt cấu hình theo TL-01: `database-schema-update: false`, `history-level: audit`, `async-executor-activate: ${WF_ASYNC_ENABLED:false}`, `definition-cache-limit: 200`, executor core 16 / max 32 / queue 256, 64 job mỗi lần lấy, acquire wait 1 s / 5 s, lock 5 phút, `dmn.history-enabled: false`, Hikari 20 kết nối, `connection-timeout` 2000 ms.
- Viết `FlowableEngineTuning`: `StrongUuidGenerator`, bulk insert 100, bật cache thông tin định nghĩa, lịch dọn lịch sử chạy lúc 2 giờ sáng.
- Đưa DDL Flowable vào Flyway của dịch vụ; thêm chỉ mục `ACT_RU_TASK(TENANT_ID_, ASSIGNEE_)`, `(TENANT_ID_, CREATE_TIME_)`, `ACT_RU_VARIABLE(PROC_INST_ID_, NAME_)`, đặt `fillfactor=70` cho `ACT_RU_EXECUTION`, `ACT_RU_TASK`.
- Chart Helm hai Deployment cùng image: `workflow-api` (HPA CPU + RPS) và `workflow-executor` (ScaledObject KEDA min 2 / max 10, trigger chỉ số `flowable_async_jobs_due_unlocked` ngưỡng 400, kích hoạt 50, CPU 75%); cấu hình executor theo TL-04 dòng 1511–1531; xuất chỉ số đếm job đến hạn bằng Micrometer từ truy vấn có giới hạn mỗi 15 giây trên bản sao đọc.
- Kiểm thử: Testcontainers PostgreSQL chạy Flyway sạch và engine khởi động không tự sửa schema; một tiến trình mẫu tối giản chạy được ở chế độ executor bật; `workflow-api` không lấy job khi cờ tắt.
- Điểm lệch cần lưu ý: TL-01 gọi CSDL là `flowable` và dọn lịch sử > 90 ngày; TL-03 gọi CSDL `workflow` ở cụm `pg-wf` và giữ lịch sử 400 ngày; TL-04 dùng `pg-flowable` và Hikari 10 cho executor. Chọn tên schema `flowable` (baseline mục 9 và hợp đồng dùng `flowable.*`), thời gian giữ lịch sử là tham số mặc định 400 ngày theo TL-03 (thiết kế CSDL, có ràng buộc lưu giữ), Hikari theo vai trò Deployment; ghi vào wiki trang 14.
- Gợi ý tách: (a) dịch vụ, cấu hình engine, Flyway, chỉ mục; (b) chart hai Deployment, KEDA, chỉ số.

## 4. Ngoài phạm vi

- Bảng `wf_*`, hộp việc (DV-06-02); mẫu BPMN (DV-06-03, DV-06-04); khung saga (DV-06-05).
- Cài KEDA, Prometheus Adapter (INF-16).

## 5. Tệp được phép sửa

- `backend/services/workflow-service/**`
- `backend/pom.xml` (thêm mô-đun)
- `deploy/charts/workflow-service/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/workflow-service -am verify` chạy xanh gồm Testcontainers và ArchUnit.
- `helm lint deploy/charts/workflow-service` và `helm template | kubeconform -strict` không lỗi; mỗi Deployment chỉ có một bộ co giãn (WI-26).
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
