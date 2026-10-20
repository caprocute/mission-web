# DL-12 · TL-03 ch.17 Job đối soát R1–R7 và bốn job kiểm tra sổ cái

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DL-12` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 – lõi nghiệp vụ trên nền tảng mới |
| Luồng đề xuất | B11 |
| Cỡ việc | L (> 1 ngày, nên tách: xem cuối mục 3) |
| Phụ thuộc | CORE-04-02, DV-09-04, DV-15-02 |
| Truy vết | TL-03 "Đối soát và kiểm tra nhất quán", "Sổ cái bất biến › Kiểm tra toàn vẹn"; NFR-10 → SVC-05 (`org_replica`), SVC-07, SVC-08, SVC-09, SVC-15 → bảng `<schema>.reconcile_run`, `org_replica.reconcile_run`, `ledger.verify_run`, `scoring.score_result`, `appraisal.score_snapshot`, `evidence.object_version`, `<schema>.outbox_event` → topic `org.bootstrap.v1` (phát lại R1), EVT-20 (khi sổ cái lỗi, do DV-09-04 phát) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Có lưới an toàn phát hiện chỗ outbox, Kafka, inbox hay CDC làm lệch dữ liệu giữa các service: bảy job đối soát R1–R7 chạy đúng tần suất, ghi `reconcile_run`, xuất chỉ số Prometheus và cảnh báo khi lệch, cùng bốn loại kiểm tra toàn vẹn sổ cái được lập lịch và có cảnh báo. Không có các job này thì sai lệch chỉ bị phát hiện khi người dùng khiếu nại.

## 2. Đầu vào bắt buộc đọc

- TL-03 `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md`: chương "Đối soát và kiểm tra nhất quán" (dòng 4644–4663, bảng R1–R8, băm tổng hợp XOR; R8 Oracle ↔ PostgreSQL không áp dụng vì hệ mới không chuyển dữ liệu từ hệ cũ); "Sổ cái bất biến", mục "Kiểm tra toàn vẹn" (bốn loại: đầu chuỗi 15 phút, sau niêm phong, toàn bộ hằng tuần 1/4 tenant, tệp lưu trữ hằng quý); bảng `org_replica.reconcile_run` (mục "Schema org_replica").
- TL-04 `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md`: chương "Quan sát hệ thống" (quy ước chỉ số, cảnh báo) và `_ghi-chu-TL-04.md` (bảng `scoring.reconcile_diff`, `ledger.verify_run`).
- `docs/quy-dinh/BACKEND.md`, `docs/quy-dinh/HA-TANG.md`; `wiki/14-quyet-dinh-va-cau-hoi-mo.md` (TL-03 chưa có DDL `reconcile_run` dùng chung).

## 3. Phạm vi công việc

- Viết khung dùng chung trong `backend/libs/reconcile/`: mô hình `reconcile_run` (theo mẫu `org_replica.reconcile_run`), hàm băm tổng hợp XOR SHA-256 dòng chuẩn hoá không phụ thuộc thứ tự, chia bucket, chỉ số Prometheus (số lần chạy, số lệch, thời gian), mẫu cảnh báo.
- Thêm migration `reconcile_run` cho các schema chưa có (expand-only), giữ tên bảng thống nhất; ghi vào wiki trang 14 rằng DDL này do task bổ sung.
- R1 `org` ↔ `org_replica`: 256 bucket băm, đi sâu bucket lệch, phát lại qua `org.bootstrap.v1`, hằng đêm.
- R2 `ledger_entry` ↔ bảng nguồn: mẫu 1% đối tượng đổi trong ngày, tính lại `payload_hash`, lệch thì cảnh báo SOC mức cao, hằng đêm.
- R3 `score_result` ↔ `score_snapshot` khớp `input_hash`, `result_hash` sau mỗi saga chốt kỳ, lệch thì chặn ký số hồ sơ đó; R4 tính lại 0,5% kết quả đã chốt hằng tuần.
- R5 PostgreSQL ↔ ClickHouse đếm và tổng kiểm theo (tenant, kỳ, đơn vị) hằng giờ; R6 `object_version` ↔ Ceph mẫu 0,1% mỗi ngày, toàn bộ mỗi quý; R7 `outbox_event` ↔ Kafka đếm theo giờ, lệch thì phát lại từ outbox. R8 Oracle ↔ PostgreSQL trong bảng TL-03 không làm, vì Mission là hệ mới độc lập, không chuyển dữ liệu từ Oracle (quyết định ngày 20/10/2026).
- Bốn kiểm tra sổ cái do DV-09-04 hiện thực: task này chỉ bảo đảm chúng được lập lịch đúng tần suất, có chỉ số và cảnh báo chung; nếu DV-09-04 đã làm đủ thì ghi "đã có" trong báo cáo, không làm lại.
- Lập lịch bằng CronJob trong chart của từng service và viết runbook xử lý từng loại lệch.
- Gợi ý tách: DL-12a khung, R1, R7; DL-12b R2, R3, R4; DL-12c R5, R6 và nối bốn kiểm tra sổ cái.

## 4. Ngoài phạm vi

- Thuật toán kiểm chứng sổ cái và EVT-20 (DV-09-04); connector CDC phân tích (DV-15-02).
- Bảng điều khiển Grafana tổng hợp (nhóm INF quan sát).

## 5. Tệp được phép sửa

- `backend/libs/reconcile/**`, khai báo mô-đun trong `backend/pom.xml`
- `backend/mission-core/src/main/java/vn/viettel/okrkpi/*/infrastructure/reconcile/**` và kiểm thử tương ứng
- `backend/mission-core/src/main/resources/db/migration/<schema>/` (chỉ migration thêm `reconcile_run`)
- `backend/services/scoring-service/**/reconcile/**`, `backend/services/evidence-service/**/reconcile/**`
- `services-go/ledger/` (chỉ nối chỉ số và lịch, nếu thiếu)
- `deploy/charts/*/templates/*reconcile*`, `ops/runbooks/doi-soat-*.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q verify` cho các mô-đun bị đụng tới xanh; `go test ./...` trong `services-go/ledger` xanh nếu có sửa.
- Mỗi job R1–R7 có kiểm thử tích hợp Testcontainers: cố ý tạo một dòng lệch thì job phát hiện, ghi `reconcile_run` có số lệch đúng và thực hiện hành động xử lý theo bảng TL-03.
- Băm tổng hợp cho cùng kết quả khi đảo thứ tự dòng (kiểm thử thuộc tính).
- `helm lint` và `helm template` các chart có CronJob mới không lỗi; chỉ số xuất hiện ở endpoint Prometheus khi chạy thử.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
