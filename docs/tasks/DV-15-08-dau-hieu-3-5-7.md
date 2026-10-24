# DV-15-08 · QT-14 Dấu hiệu bất thường 3, 5, 7: mục tiêu quá dễ, cập nhật dồn cuối kỳ, chẻ nhỏ công việc

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-15-08` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 3 |
| Luồng đề xuất | B7 |
| Cỡ việc | M |
| Phụ thuộc | DV-15-07 |
| Truy vết | QT-14 bước 1, 3 (BR-14-01, BR-14-06, BR-14-09) → SCR-1401, cây OKR (dấu hiệu 3), SCR-0601 cho quản lý (dấu hiệu 5) → SVC-15 job phát hiện → ClickHouse `fact_okr_checkin`, `fact_task`, `fact_task_progress`, `anomaly_out` → EVT-20 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Bổ sung ba luật vào khung job phát hiện của DV-15-07: mục tiêu đặt quá dễ (dấu hiệu 3), cập nhật dồn vào cuối kỳ (dấu hiệu 5) và chẻ nhỏ công việc (dấu hiệu 7), mỗi luật có ngưỡng đọc từ cấu hình tenant, mức Cao/Vừa/Thấp, giải thích ngắn bằng tiếng Việt và chuỗi bằng chứng chỉ chứa mã. Khi xong, ba dấu hiệu phát EVT-20 đúng với bộ dữ liệu vàng và không phát lại khi chạy lại.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-14 "Thông tin chung" (L1131, bảng tám dấu hiệu, dòng 3, 5, 7), "Quy tắc nghiệp vụ" (L1202, BR-14-01, BR-14-06, BR-14-09), "Ngoại lệ và xử lý" (L1216).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` "Tám dấu hiệu bất thường" (L3107).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "Mô hình sao trong ClickHouse" (L4580).
- `contracts/openapi/modules/config.yaml` `getAnomalyThresholds` (khoá ngưỡng của từng dấu hiệu).
- Bản trích `extract/tl02b-may-chu.md` QT-14 (BE-Q14-07).

## 3. Phạm vi công việc

- Dấu hiệu 3: KR đạt ≥ 100% ngay trong 2 tuần đầu kỳ, hoặc chỉ tiêu thấp hơn kết quả kỳ trước ≥ 20%; đối tượng là KR hoặc mục tiêu O; giải thích nêu tỷ lệ đạt và thời điểm.
- Dấu hiệu 5: ≥ 60% lượt cập nhật tiến độ của đơn vị dồn vào 3 ngày cuối kỳ hoặc cuối tuần; ngày làm việc tính theo lịch `config.holiday_calendar` đã có trong `dim_period` (suy luận về nơi lưu lịch trong kho).
- Dấu hiệu 7: số nhiệm vụ của một người tăng ≥ 50% so với kỳ trước trong khi tổng khối lượng không đổi, hoặc nhiều nhiệm vụ cùng sản phẩm, cùng hạn; đối tượng là người (mã giả danh) hoặc kế hoạch.
- Mỗi luật là một tệp SQL có tham số ngưỡng, kèm bộ ca kiểm thử dương và âm; mức Cao/Vừa/Thấp xác định theo độ vượt ngưỡng (cách chia mức là suy luận, ghi rõ trong mã và wiki).
- Ghi `modelVersion` theo phiên bản luật để BR-14-09 theo dõi tỷ lệ xác nhận hợp lệ của từng phiên bản ngưỡng.
- Điểm lệch cần lưu ý: TL-01 ghi ngưỡng ví dụ khác (dấu hiệu 3 "trên 80% KR đạt trước giữa kỳ", dấu hiệu 7 "số nhiệm vụ gấp 3 trung bình 4 kỳ"); task dùng ngưỡng mặc định TL-02b như DV-15-07.

## 4. Ngoài phạm vi

- Khung job, producer EVT-20 (DV-15-07); dấu hiệu 6, 8 (DV-15-09); hành động tự động trên cây OKR hay SCR-0601 (MOD-07, CORE-07).

## 5. Tệp được phép sửa

- `services-py/anomaly-job/**`
- `data/golden/anomaly/**`

## 6. Tiêu chí hoàn thành

- `cd services-py/anomaly-job && ruff check . && mypy app && pytest` xanh.
- Kiểm thử tích hợp Testcontainers ClickHouse + Kafka: mỗi dấu hiệu có ít nhất ba ca dương và ba ca âm trong `data/golden/anomaly/`, số EVT-20 phát đúng kỳ vọng; đổi ngưỡng tenant làm thay đổi kết quả tương ứng.
- Thời gian chạy ba luật trên dữ liệu mẫu cỡ một tỉnh được ghi lại (mục tiêu xong trong cửa sổ đêm, suy luận ≤ 30 phút).
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
