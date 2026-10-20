# DV-15-07 · QT-14/EVT-20 Khung job phát hiện bất thường hằng đêm và dấu hiệu 1, 2, phát EVT-20

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-15-07` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 3 |
| Luồng đề xuất | B7 |
| Cỡ việc | M |
| Phụ thuộc | DV-15-01; DV-15-02 (dữ liệu CDC đã giả danh hoá), NEN-007 (lược đồ Avro EVT-20) (bổ sung) |
| Truy vết | QT-14 bước 1 (BR-14-01, BR-14-06) → SCR-1401 (gián tiếp), SCR-0902, SCR-0904 → SVC-15 job phát hiện → bảng ClickHouse `anomaly_out`, ngưỡng trong `config.tenant_setting` → EVT-20 `analytics.anomaly.detected.v1` → MOD-07 `oversight.anomaly_alert` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Dựng khung job phát hiện bất thường chạy 02:00 hằng đêm theo cell trên ClickHouse: đọc ngưỡng của tenant, chạy các luật SQL, ghi kết quả vào bảng `anomaly_out`, và một producer nhỏ phát EVT-20 với `alertKey` tất định làm `event_id` để chạy lại không tạo cảnh báo trùng. Task hiện thực luôn hai dấu hiệu đầu tiên: tự chấm cao hơn thẩm định có hệ thống và xếp loại đồng loạt, cào bằng.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-14 "Thông tin chung" (L1131, bảng tám dấu hiệu, ngưỡng mặc định), "Mô tả các bước" (L1171), "Quy tắc nghiệp vụ" (L1202), "Ngoại lệ và xử lý" (L1216).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` bảng sự kiện (L4460: EVT-20 `analytics.anomaly.detected.v1`, khoá `tenant:subjectId`, trường `alertKey`, `signalCode`, `severity`, `subjectType`, `subjectId`, `evidenceChain`, `modelVersion`) và đoạn ngoại lệ outbox (L4468).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` "Tám dấu hiệu bất thường" (L3107).
- Lược đồ EVT-20 trong `contracts/events/avro/` do NEN-007 tạo; `contracts/openapi/modules/config.yaml` `getAnomalyThresholds`.
- Bản trích `extract/tl02b-may-chu.md` QT-14 (BE-Q14-06).

## 3. Phạm vi công việc

- Tạo `services-py/anomaly-job/` (Python, suy luận về vị trí và ngôn ngữ theo baseline SVC-15 có Python) gồm bộ chạy luật, lịch 02:00 theo cell (CronJob), đọc ngưỡng phiên bản hiện hành qua `getAnomalyThresholds` bằng tài khoản dịch vụ.
- Tạo bảng ClickHouse `anomaly_out` (tenant, dấu hiệu, đối tượng, kỳ, mức Cao/Vừa/Thấp, chuỗi bằng chứng chỉ chứa mã, `alertKey`, `modelVersion`, thời điểm phát) trong `analytics/clickhouse/`.
- Tính `alertKey` tất định bằng băm của (tenant, dấu hiệu, đối tượng, cửa sổ thời gian); luật ghi `anomaly_out` bằng `INSERT ... SELECT` luỹ đẳng theo khoá này.
- Producer đọc dòng chưa phát của `anomaly_out`, phát EVT-20 lên Kafka với `event_id = alertKey`, khoá `tenant:subjectId`, Avro theo sổ đăng ký; đánh dấu đã phát sau khi Kafka xác nhận.
- Dấu hiệu 1: mức tự đề xuất cao hơn mức thẩm định ≥ 3 quý liên tiếp, hoặc điểm tự tính cao hơn điểm sau thẩm định ≥ 5 điểm trong 2 quý (ngưỡng mặc định TL-02b).
- Dấu hiệu 2: tỷ lệ HTXSNV của đơn vị vượt ngưỡng khối, hoặc ≥ 90% cán bộ cùng một mức, hoặc độ lệch chuẩn điểm thấp bất thường.
- Chỉ đọc bảng fact/dim chứa C0, C1 và C2 đã giả danh hoá (BR-14-06); kiểm thử chặn truy vấn tới cột ngoài danh sách cho phép.
- Điểm lệch cần lưu ý: ngưỡng ví dụ ở TL-01 L3107 (ví dụ "chênh trên 15 điểm ở 2 kỳ", "độ lệch chuẩn dưới 2 điểm") khác ngưỡng mặc định ở TL-02b QT-14; task dùng ngưỡng TL-02b (mới hơn, có bảng ngưỡng cấu hình) và ghi vào `wiki/14`.

## 4. Ngoài phạm vi

- Dấu hiệu 3, 5, 7 (DV-15-08); 6, 8 và tự đóng (DV-15-09); dấu hiệu 4 phát tức thời ở evidence-service; consumer EVT-20 ở MOD-07 (CORE-07).

## 5. Tệp được phép sửa

- `services-py/anomaly-job/**`
- `analytics/clickhouse/**` (chỉ bảng `anomaly_out`)
- `data/golden/anomaly/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd services-py/anomaly-job && ruff check . && mypy app && pytest` xanh; `pip-audit` không lỗ hổng mức cao.
- Kiểm thử tích hợp Testcontainers ClickHouse + Kafka với bộ dữ liệu vàng `data/golden/anomaly/`: dấu hiệu 1 và 2 phát đúng số cảnh báo kỳ vọng; chạy lại job hai lần không sinh `event_id` mới.
- Thông điệp EVT-20 khớp lược đồ Avro; không thông điệp nào chứa tên người hay nội dung C2 rõ.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
