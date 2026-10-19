# LEG-12 · ADR-20 Strangler cho các chức năng tiếp theo: giao việc, đánh giá, bảng điều hành

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh LEG-12` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 – lõi nghiệp vụ trên nền tảng mới (bảng điều hành cuối đợt 2 hoặc đợt 3) |
| Luồng đề xuất | B11 |
| Cỡ việc | L (bắt buộc tách theo chức năng: (a) đăng ký và giao việc; (b) đánh giá và xếp loại; (c) bảng điều hành; mỗi phần một lần chuyển tuyến, một task con) |
| Phụ thuộc | LEG-10 (khung tuyến kép, bộ sinh danh sách tenant, mẫu runbook), CORE-02-14, CORE-04-07, DV-15-04; DL-09 (ánh xạ bảng hệ cũ), DL-12 (job đối soát R8), DV-13-01 (`mapping_xref`) (bổ sung) |
| Truy vết | ADR-20; TL-01 "Chuyển dần theo Strangler Fig với APISIX" (thứ tự: danh mục và cấu hình → đăng ký và giao việc → đánh giá và xếp loại → bảng điều hành) → API (a) `/api/v1/tasks*`, `/api/v1/plans*`; (b) `/api/v1/appraisals*`, `/api/v1/dossiers*`; (c) `/api/v1/analytics*` → SVC-01, MOD-02, MOD-04, MOD-06, SVC-15, SVC-13, SVC-03 → bảng `task.*`, `appraisal.*`, `dossier.*`, `integ.mapping_xref`, `config.feature_flag`; Oracle hệ cũ |
| Trạng thái | Bị chặn bởi bên ngoài: chưa có quyền truy cập hệ cũ (như LEG-10) |

## 1. Mục tiêu

LEG-10 dựng cơ chế chuyển tuyến theo tenant và chuyển chức năng đầu tiên là danh mục công việc. Task này áp dụng cùng mẫu cho ba chức năng còn lại theo đúng thứ tự TL-01: đăng ký và giao việc, đánh giá và xếp loại, bảng điều hành. Mỗi chức năng có cặp tuyến APISIX riêng, đồng bộ hai chiều dữ liệu của chức năng đó trong thời gian chuyển tiếp, đối soát R8 bằng không trước khi mở rộng danh sách tenant, và kế hoạch đóng băng mã cũ. Khi xong, một tenant thí điểm dùng nền mới cho toàn bộ luồng giao việc, đánh giá và bảng điều hành, còn tenant khác vẫn ở hệ cũ mà không thấy khác biệt.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` "Chuyển dần theo Strangler Fig với APISIX" (L1428–1459: thứ tự chức năng, `priority: 10` với `$jwt_tenant`, đồng bộ hai chiều, xoá mã cũ), "ADR-20. Strangler Fig với APISIX và lớp chống ô nhiễm" (L3582).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "Di trú từ hệ hiện tại theo mẫu Strangler Fig" (Debezium Oracle LogMiner chiều cũ sang mới, SVC-13 ghi ngược) và chương đối soát (R8).
- Hợp đồng: `contracts/openapi/modules/tasks.yaml`, `appraisals.yaml`, `dossiers.yaml`, `platform.yaml` (`/analytics*`, `/registry/*`), `config.yaml` (`/config/feature-flags*`).
- Thẻ `docs/tasks/LEG-10-strangler-theo-tenant.md` (mẫu áp dụng, runbook), `LEG-11-chuyen-tenant-tat-he-cu.md`, `DL-09-*.md`, `DL-12-job-doi-soat.md`, `CORE-04-07-*.md`.
- Đầu vào người dùng cần cấp: quyền đọc, ghi các repo và CSDL hệ cũ liên quan từng chức năng; danh sách tenant thí điểm và lịch chuyển theo kỳ đánh giá.

## 3. Phạm vi công việc

- (a) Đăng ký và giao việc: cặp `ApisixRoute` cho `/api/v1/tasks*` và `/api/v1/plans*` (tuyến mới `priority: 10` khớp tenant đã chuyển, tuyến cũ `priority: 1`); connector hai chiều cho nhiệm vụ, phân công, tiến độ, kế hoạch quý qua integration-gateway với `mapping_xref` chống vòng lặp; chuyển từ đầu một kỳ để không có nhiệm vụ nửa ở hai hệ (suy luận, xác nhận với người dùng).
- (b) Đánh giá và xếp loại: tuyến cho `/api/v1/appraisals*`, `/api/v1/dossiers*`; đồng bộ chiều cũ sang mới hồ sơ, điểm, xếp loại của kỳ đang mở; kỳ đã khoá ở hệ cũ chỉ đọc (dựa DL-11); kiểm điểm số tính lại ở nền mới khớp hệ cũ theo bộ ca vàng trước khi chuyển (đối soát điểm từng hồ sơ, chênh lệch phải giải thích được).
- (c) Bảng điều hành: tuyến cho `/api/v1/analytics*` và tài nguyên tĩnh của remote `fe-dashboard`; không cần đồng bộ ngược vì chỉ đọc, nhưng số liệu chỉ đúng khi (a) và (b) đã chuyển cho tenant đó; chặn bật tuyến (c) cho tenant chưa hoàn tất (a), (b) bằng kiểm tra trong bộ sinh danh sách tenant.
- Mỗi chức năng: chạy song song ít nhất một kỳ với tenant thí điểm, job R8 (DL-12) bằng không cho bảng của chức năng; runbook chuyển và quay lui (bỏ tenant khỏi danh sách) theo mẫu LEG-10; kế hoạch đóng băng bảng và xoá mã cũ khi mọi tenant đã chuyển và ổn định một kỳ (chỉ lập kế hoạch).
- Thứ tự phụ thuộc giữa ba phần: không bật (b) trước (a) cho cùng tenant; danh sách tenant mỗi chức năng là một commit GitOps có duyệt.
- Điểm lệch cần lưu ý: nếu đường dẫn và lược đồ API hệ cũ khác hợp đồng mới thì chuyển đổi ở lớp chống ô nhiễm của integration-gateway (ADR-20), không sửa hợp đồng mới cho khớp hệ cũ.

## 4. Ngoài phạm vi

- Danh mục công việc và khung tuyến kép (LEG-10); chuyển tenant theo nhóm và tắt hệ cũ (LEG-11); di trú dữ liệu lịch sử (DL-09, DL-10, DL-11).

## 5. Tệp được phép sửa

- `deploy/environments/<môi-trường>/strangler/**` (ApisixRoute, danh sách tenant theo chức năng)
- `backend/services/integration-gateway/**/strangler/**` (connector đồng bộ hai chiều từng chức năng) và kiểm thử
- Mã hệ cũ liên quan điểm ghi ngược (đường dẫn do người dùng cấp)
- `ops/runbooks/strangler-chuyen-tenant.md` (thêm mục cho từng chức năng)

## 6. Tiêu chí hoàn thành

- Với mỗi phần: `helm template` và `kubeconform` cho tuyến mới không lỗi; kiểm thử định tuyến token tenant đã chuyển vào nền mới, tenant khác vào hệ cũ.
- `cd backend && mvn -q -pl services/integration-gateway -am verify` xanh, có Testcontainers (Oracle Free, PostgreSQL 17, Kafka) chứng minh sửa một bản ghi ở mỗi phía được đồng bộ sang phía kia đúng một lần, không vòng lặp.
- Trên môi trường thử: chuyển rồi quay lui một tenant không mất dữ liệu; R8 bằng không cho bảng của chức năng; điểm hồ sơ tính ở nền mới khớp hệ cũ theo bộ ca vàng (phần b).
- Báo cáo cuối task theo AGENTS.md mục 6 cho từng task con.

## 7. Báo cáo

Theo AGENTS.md mục 6.
