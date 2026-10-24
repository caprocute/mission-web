# DV-06-11 · SVC-06/G2 Chiếu lịch sử tiến trình sang kho phân tích và chỉ số giám sát Flowable

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-06-11` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B6 |
| Cỡ việc | M |
| Phụ thuộc | DV-06-02, DV-15-01 |
| Truy vết | TL-01 chương 8 G2 (giám sát 11 chỉ số) → SVC-06, SVC-15 → bảng `ACT_HI_*` (nguồn CDC), ClickHouse `analytics_<cell>`; PERF-11; NFR-10 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Vận hành biết engine Flowable của từng cell khoẻ hay sắp nghẽn trước khi người dùng thấy chậm, và lịch sử tiến trình được giữ cho phân tích dù bảng `ACT_HI_*` được dọn định kỳ. Khi xong, mười một chỉ số giám sát của TL-01 có trên Prometheus với ngưỡng cảnh báo, và Debezium đọc `ACT_HI_*` đưa sang ClickHouse trước khi job dọn lịch sử chạy.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` chương G2: đoạn giám sát (dòng 2115–2133, bảng 11 chỉ số và ngưỡng), tối ưu CSDL engine và Debezium đọc `ACT_HI_*` (dòng 2080).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "Schema flowable và saga (SVC-06)" (dòng 3227–3240) và CDC sang ClickHouse (mục "Cơ chế đồng bộ dữ liệu").
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` mục "Co giãn workflow-service và async executor của Flowable" (dòng 1502–1565).
- Wiki: `wiki/09-hieu-nang-va-trai-nghiem.md`, `wiki/12-ha-tang-dev-va-trien-khai.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 3. Phạm vi công việc

- Xuất bằng Micrometer đủ 11 chỉ số: job đến hạn chưa khoá, trễ lấy job p95, deadletter mỗi giờ, tỉ lệ `FlowableOptimisticLockingException`, trễ API hoàn thành p95, trễ external worker p95, số dòng `ACT_RU_EXECUTION`, tăng `ACT_HI_*` so với trung bình 30 ngày, tỉ lệ trượt cache định nghĩa, mức dùng pool kết nối, số định nghĩa hoạt động; truy vấn đếm có giới hạn, chạy trên bản sao đọc, chu kỳ 15 giây.
- Viết `PrometheusRule` với hai mức ngưỡng cảnh báo cho từng chỉ số theo bảng TL-01 (ví dụ job đến hạn chưa khoá > 2.000 / > 10.000 trong 5 phút; deadletter > 10 / > 100 mỗi giờ; số định nghĩa > 300 / > 1.000) và dashboard Grafana cho workflow-service.
- Cấu hình connector Debezium đọc `ACT_HI_PROCINST`, `ACT_HI_TASKINST`, `ACT_HI_ACTINST` (chỉ cột không chứa dữ liệu nghiệp vụ C2) sang topic phân tích; bảng đích và materialized view trong ClickHouse theo mô hình của DV-15-01.
- Bảo đảm thứ tự: job dọn lịch sử chỉ xoá bản ghi đã được xác nhận có trong ClickHouse (kiểm bằng độ trễ connector hoặc mốc đã chép, suy luận).
- Kiểm thử: kiểm thử đơn vị cho bộ đếm; Testcontainers PostgreSQL + Kafka + Debezium đưa một tiến trình hoàn tất sang topic; `promtool check rules` cho tệp cảnh báo.

## 4. Ngoài phạm vi

- Dựng ClickHouse, Kafka Connect (INF-10, INF-11, DV-15-01); cảnh báo chung theo tốc độ tiêu ngân sách lỗi (INF-15).

## 5. Tệp được phép sửa

- `backend/services/workflow-service/**`
- `deploy/charts/workflow-service/**` (PrometheusRule, dashboard, connector Debezium)
- `analytics/` (chỉ bảng đích và materialized view lịch sử tiến trình)
- `wiki/12-ha-tang-dev-va-trien-khai.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/workflow-service -am verify` chạy xanh; `promtool check rules` không lỗi; `helm lint` không lỗi.
- Một tiến trình hoàn tất xuất hiện trong bảng ClickHouse thử nghiệm trong p99 ≤ 60 giây (TL-03 CDC sang ClickHouse).
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
