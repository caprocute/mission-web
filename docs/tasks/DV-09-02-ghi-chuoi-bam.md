# DV-09-02 · SVC-09/ADR-26 Ghi chuỗi băm 64 phân vùng theo tenant, chuẩn hoá RFC 8785

@@HDR@@

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B6 |
| Cỡ việc | L |
| Phụ thuộc | DV-09-01; cần INF-10 hoặc NEN-020 (Kafka dev có topic `sys.ledger.append.v1` 64 phân vùng) để chạy kiểm thử đầu cuối (bổ sung) |
| Truy vết | QT-13 (BR-13-09) → SVC-09 → bảng `ledger.ledger_entry`, `ledger.chain_head` → topic `sys.ledger.append.v1`, EVT-01..05, 07..09, 11..13, 18, 22, 25, `sys.audit.v1` → ADR-09, ADR-26 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hiện thực ledger-ingester: tiêu thụ mọi sự kiện có giá trị pháp lý, chuẩn hoá tải trọng theo RFC 8785 với chuỗi NFC, chọn chuỗi `hash(aggregate_id) mod 64`, nối vào chuỗi băm của tenant theo lô, idempotent theo `event_id`. Khi xong, mỗi sự kiện pháp lý xuất hiện đúng một lần trong sổ cái trong ≤ 30 giây (SLO-LEDGER), và cùng một tải trọng cho cùng `payload_hash` ở Go, Java và TypeScript.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` chương "Sổ cái bất biến": "Mục tiêu và phạm vi" (L4090), "Cấu trúc chuỗi băm" (L4096), "Ghi một bản ghi" (L4110); từ điển `ledger.chain_head` (L3618).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Định dạng băm chuẩn hoá" (L2597) và ADR-26 (L3642).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-13 mục "Quy tắc nghiệp vụ" (L999, BR-13-09) và "Ngoại lệ và xử lý" (L1016, bản ghi "đánh dấu sự cố").
- `TL-04-trien-khai-van-hanh.md` mục "SLO và ngân sách lỗi" (L1666, SLO-LEDGER) và "Kịch bản riêng cho góp ý G3" (L1810, PERF-16).
- `00-co-so-thiet-ke-chung.md` mục 7 (danh mục EVT); bản trích `extract/tl03-phan2.md` mục B2, `extract/tl02b-may-chu.md` QT-13 (BE-Q13-07).

## 3. Phạm vi công việc

- Viết gói `canon` trong `services-go/ledger/internal/canon`: chuẩn hoá JSON RFC 8785 (khoá sắp theo điểm mã UTF-16, số dạng ngắn nhất ECMAScript), chuẩn hoá NFC mọi chuỗi trước khi băm, dấu thời gian `YYYY-MM-DDThh:mm:ss.ffffffZ`, số tiền/điểm dạng chuỗi thập phân; hàm chọn chuỗi lấy 64 bit thấp của SHA-256(`aggregate_id`) mod 64.
- Lập bộ vector kiểm thử dùng chung `data/golden/ledger-canon/*.json` (đầu vào, chuỗi chuẩn hoá, `payload_hash`, `metadata_hash`, `entry_hash`, số chuỗi), có ca tiếng Việt tổ hợp dấu khác nhau cho cùng kết quả; các bản Java và TypeScript sau này kiểm cùng bộ vector.
- Viết consumer franz-go nhóm `ledger-ingester` đọc `sys.ledger.append.v1` (khoá `tenant:partition`), mỗi consumer sở hữu một tập chuỗi; ghi lô tối đa 200 bản ghi trong một giao dịch: `SELECT … FOR UPDATE` dòng `chain_head`, tính `metadata_hash` (tenant, chuỗi, `seq_no`, loại, đối tượng, chủ thể, `occurred_at`, `recorded_at`), `entry_hash = SHA-256(prev_hash || payload_hash || metadata_hash)`, chèn lô, cập nhật `chain_head`, rồi mới xác nhận offset.
- Bản ghi đầu chuỗi có `prev_hash` 32 byte 0; bản ghi trùng `event_id` bị UQ chặn và bỏ qua không làm hỏng lô (dùng `ON CONFLICT DO NOTHING` theo khoá UQ).
- Hiện thực bản ghi đặc biệt `TENANT_MOVED_IN` nối tiếp `entry_hash` cuối của cell nguồn và bản ghi "đánh dấu sự cố" mở phân đoạn mới sau khi phá chuỗi (TL-02b ngoại lệ QT-13), chỉ gọi được qua lệnh quản trị có ghi nhật ký kiểm toán.
- Không lưu tải trọng C2: `metadata` chỉ chứa trạng thái trước/sau rút gọn ở mức C1 và mã tham chiếu; kiểm thử chặn khi phong bì sự kiện có `classification` C2 mà `metadata` chứa trường ngoài danh sách cho phép.
- Xuất metric độ trễ outbox → `ledger_entry` (p99) và số bản ghi/giây theo chuỗi; khi một phân vùng bị khoá ghi do phá chuỗi (BR-13-11), consumer dừng phân vùng đó và báo lỗi, không ghi tiếp.
- Gợi ý tách nếu quá lớn: (a) gói `canon` + vector; (b) consumer và luồng ghi lô.

## 4. Ngoài phạm vi

- Cấu hình Debezium định tuyến outbox sang `sys.ledger.append.v1` (INF-10, NEN-011) và bản Java của thư viện chuẩn hoá trong `vt-platform-starter` (NEN-011 dùng bộ vector của task này).
- Niêm phong Merkle (DV-09-03), kiểm tra toàn vẹn (DV-09-04).

## 5. Tệp được phép sửa

- `services-go/ledger/**`
- `data/golden/ledger-canon/**`
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd services-go/ledger && go test ./...` xanh, gồm kiểm thử tích hợp Testcontainers PostgreSQL 17 + Kafka (Redpanda hoặc Kafka KRaft): gửi 10.000 sự kiện có 5% trùng `event_id`, sổ cái có đúng số bản ghi duy nhất, `seq_no` liên tục không lỗ trong mọi chuỗi, `prev_hash` khớp `entry_hash` liền trước.
- Toàn bộ vector trong `data/golden/ledger-canon/` cho kết quả khớp; kiểm thử cùng đối tượng luôn rơi vào cùng chuỗi.
- Đo cục bộ đạt ≥ 3.000 bản ghi/giây với lô 200 (đỉnh ngày chốt kỳ theo TL-03); độ trễ p99 từ lúc sự kiện vào topic tới khi có trong `ledger_entry` ≤ 5 giây ở tải thử (tiêu chí PERF-16, kiểm đầy đủ ở QA-10).
- Khởi động lại consumer giữa lô không sinh trùng hay lỗ `seq_no`.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
