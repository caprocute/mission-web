# DV-07-04 · SVC-07 Tính dần theo sự kiện EVT-06, EVT-08, EVT-13, EVT-22, phát EVT-10

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-07-04` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B4 |
| Cỡ việc | M |
| Phụ thuộc | DV-07-03, NEN-012 |
| Truy vết | QT-07 bước 11 → SVC-07 (consumer group `scoring-incremental`) → tiêu thụ EVT-06 `task.task.statuschanged.v1`, EVT-08 `product.evidence.accepted.v1`, EVT-13 `policy.pack.published.v1`, EVT-22 `oversight.period.reopened.v1` → bảng `scoring.score_job` (`TAM_TINH`, `TINH_LAI_HAU_KIEM`), `scoring.score_result` → phát EVT-10 `scoring.score.computed.v1`; TL-04 WI-28 (PERF-19) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Điểm nhiệm vụ được tính ngay khi minh chứng được xác nhận hoặc trạng thái nhiệm vụ đổi, không đợi cuối kỳ: consumer nhận sự kiện, đọc đầu vào qua API của lõi, tính và phát EVT-10 trong ≤ 1 phút (TL-02a QT-07 bước 11). Khi xong, cán bộ thấy điểm tạm tính cập nhật trong ngày, consumer co giãn theo độ trễ Kafka, sự kiện trùng không tính hai lần.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SVC-07 scoring-service" (dòng 1072–1084) và "Tích hợp hướng sự kiện" (dòng 1232; mẫu `EvidenceAcceptedConsumer` dòng 1251–1270).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-07 bước 11 (dòng 1895), điểm tính dần (dòng 164).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-13: tính lại trong phạm vi bằng `policy_version` gốc ≤ 15 phút (dòng 975, 982).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` mục "KEDA theo độ trễ consumer Kafka" (dòng 1250–1326).
- Baseline `00-co-so-thiet-ke-chung.md` mục 7, 8.
- Hợp đồng: `contracts/openapi/modules/appraisals.yaml` `listAppraisalScoringInputs`; lược đồ Avro EVT-06, EVT-08, EVT-10, EVT-13, EVT-22.
- Wiki: `wiki/04-ma-dinh-danh-va-truy-vet.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 3. Phạm vi công việc

- Viết consumer theo NEN-012 (inbox idempotent, thử lại có backoff, DLQ) cho bốn sự kiện, group `scoring-incremental`, khoá phân vùng `tenantId:aggregateId` để giữ thứ tự theo đối tượng.
- EVT-08, EVT-06: xác định hồ sơ và nhiệm vụ bị ảnh hưởng, đọc đầu vào qua API nội bộ của lõi (không đọc CSDL lõi), tạo hoặc cập nhật `score_job` loại `TAM_TINH`, tính bằng chuỗi DV-07-03 với phiên bản ghim của kỳ, phát EVT-10 qua outbox; bỏ qua nếu `input_hash` không đổi.
- EVT-13: chuyển cho cơ chế nạp của DV-07-02 và xếp tính lại các hồ sơ kỳ đang mở nếu phiên bản áp cho kỳ đó.
- EVT-22: tạo `score_job` loại `TINH_LAI_HAU_KIEM` chỉ cho hồ sơ trong phạm vi mở lại, dùng `policy_version` gốc, giữ ảnh chụp cũ (BR-13-06), mục tiêu ≤ 15 phút.
- Cấu hình ScaledObject KEDA theo TL-04 WI-28: trigger lag `product.evidence.accepted.v1` 500 (kích hoạt 10), `scoring.recompute.requested.v1` 200 (kích hoạt 1), CPU 80%, min 2, max 24, TLS tới Kafka bằng `TriggerAuthentication`.
- Kiểm thử Testcontainers Kafka + Apicurio + PostgreSQL: EVT-08 dẫn tới EVT-10 đúng một lần; sự kiện trùng bị bỏ; EVT-22 chỉ tính lại hồ sơ trong phạm vi; lỗi đọc đầu vào thử lại rồi vào DLQ.
- Điểm lệch cần lưu ý: TL-01 ghi tối đa 12 pod × 8 luồng, TL-04 ghi max 24; chọn TL-04 (cấu hình triển khai), để giá trị trong values. Tên DLQ khác nhau giữa TL-01 dòng 1246 và 1281; dùng quy ước NEN-012 đã chọn. Topic `scoring.recompute.requested.v1` chỉ có ở TL-04, không phải EVT; giữ là topic kỹ thuật.

## 4. Ngoài phạm vi

- Tính lại hàng loạt cuối kỳ (DV-07-06); consumer EVT-10 ở MOD-04 (CORE-04-02).

## 5. Tệp được phép sửa

- `backend/services/scoring-service/**`
- `deploy/charts/scoring-service/**` (ScaledObject, TriggerAuthentication)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl services/scoring-service -am verify` chạy xanh gồm Testcontainers bốn ca ở mục 3.
- Đo độ trễ từ EVT-08 tới EVT-10 trong kiểm thử, p95 ≤ 1 phút; ghi số đo.
- `helm lint deploy/charts/scoring-service` không lỗi; Deployment chỉ có một bộ co giãn.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
