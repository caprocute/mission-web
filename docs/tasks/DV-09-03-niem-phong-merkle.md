# DV-09-03 · SVC-09/EVT-19 Niêm phong Merkle hai cấp mỗi giờ, dấu thời gian RFC 3161, ký và bản neo

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-09-03` để có prompt đầy đủ.

> **Cập nhật 24/10/2026 (wiki 14, mục "Quyết định điều chỉnh phạm vi của chủ dự án"):** ứng dụng di động (SVC-18, MOB-01..10) và đo tải, diễn tập DR (QA-08..QA-12, QA-14, QA-15) đã bỏ khỏi phạm vi; các chỗ nhắc tới chúng trong thẻ này chỉ còn giá trị lịch sử.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B6 |
| Cỡ việc | L |
| Phụ thuộc | DV-09-02, DV-10-03 |
| Truy vết | QT-13 (BR-13-09), QT-08 bước 6 saga chốt kỳ → SVC-09 (+ SVC-10 ký) → bảng `ledger.merkle_seal`, `ledger.cell_seal`, `ledger.anchor_receipt`, `ledger.chain_head`, `ledger.outbox_event` → EVT-19 `ledger.seal.created.v1` → ADR-09, ADR-26 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hiện thực job niêm phong: mỗi cửa sổ (1 giờ; 5 phút trong 3 ngày cuối kỳ; đột xuất khi saga chốt kỳ yêu cầu) dựng cây Merkle mức tenant từ đầu 64 chuỗi, dựng cây mức cell từ gốc các tenant, ký gốc cell một lần bằng khoá niêm phong trong HSM, lấy token TSA RFC 3161 một lần, ghi `cell_seal` và mọi `merkle_seal` trong một giao dịch rồi phát EVT-19. Khi xong, mọi bản ghi sổ cái được chứng minh thời điểm bởi bên thứ ba và bản neo hằng ngày sẵn sàng gửi ra ngoài.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Niêm phong Merkle, dấu thời gian và bản neo" (L2601) và ADR-26 (L3642).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "DDL mẫu: ledger.merkle_seal" (L3547), từ điển `merkle_seal`, `cell_seal`, `anchor_receipt` (L3631–3665), "Niêm phong Merkle và dấu thời gian" (L4128), "Saga điều phối chốt kỳ" (L4359, bước 6).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-13 "Ngoại lệ và xử lý" (L1016, TSA lỗi).
- `TL-04-trien-khai-van-hanh.md` "Kịch bản riêng cho góp ý G3" (L1810, PERF-16).
- `00-co-so-thiet-ke-chung.md` mục 7 (EVT-19); bản trích `extract/tl02b-may-chu.md` QT-13 (BE-Q13-08), `extract/tl03-phan2.md` mục A7, B2.

## 3. Phạm vi công việc

- Viết gói `internal/seal` tính lá `SHA-256(0x01 || entry_hash)` và nút `SHA-256(0x02 || trái || phải)`; gốc tenant lấy từ `entry_hash` cuối của 64 chuỗi có bản ghi mới trong cửa sổ, gốc các tenant làm lá cây mức cell; lưu `chain_ranges` (khoảng `seq_no` mỗi chuỗi), `tenant_root`, `cell_leaf_index` và đường đi Merkle tenant → cell.
- Ký gốc cell (kèm `prev_cell_root`) bằng khoá niêm phong ECDSA P-256 của cell qua esign-service (nhà cung cấp HSM của DV-10-03); lưu `signer_cert_sha256`, `signature`; lấy token TSA RFC 3161 cho gốc đã ký, lưu `tsa_token` (DER) và `tsa_gen_time`.
- Thực hiện theo đúng thứ tự TL-03: dựng cây, ký, lấy TSA, rồi chèn `cell_seal` và mọi `merkle_seal` trong một giao dịch, cập nhật `chain_head.last_sealed_seq_no`, ghi outbox EVT-19 cùng giao dịch.
- Lập lịch cửa sổ: mặc định 1 giờ, 5 phút trong 3 ngày cuối kỳ (đọc lịch kỳ từ bản sao cấu hình hoặc tham số, suy luận), và lệnh niêm phong đột xuất cho bước 6 saga chốt kỳ, idempotent theo khoá cửa sổ (UQ `tenant_id`,`window_start`).
- Khi TSA lỗi: gộp vào cửa sổ kế tiếp, quá 24 giờ chưa có token thì cảnh báo; khi TSA trở lại, gắn dấu thời gian bù và đánh dấu "dấu thời gian trễ". Điểm lệch cần lưu ý: TL-03 (L3594) chỉ ghi "gộp vào cửa sổ kế tiếp", TL-02b (L1016) ghi "niêm phong ký trước, gắn dấu thời gian bù"; task hiện thực cách của TL-02b vì giữ được chữ ký đúng giờ và vẫn thoả TL-03, ghi vào `wiki/14`.
- Viết job neo hằng ngày: chọn gốc cell cuối ngày, tạo yêu cầu bản neo cho ít nhất hai đích độc lập và bucket Object Lock; lưu biên nhận vào `anchor_receipt` khi SVC-13 xác nhận (việc gửi ra ngoài thuộc DV-13-09).
- Điểm lệch cần lưu ý: BS-01 (BS-L74) nói "niêm phong 5 phút một lần" trong cao điểm còn BR-13-09 ghi "mỗi giờ"; task hiện thực cả hai theo TL-01 (1 giờ thường, 5 phút trong 3 ngày cuối kỳ). Lời gọi ký từ ledger sang esign không có trong hợp đồng OpenAPI công khai; dùng giao diện nội bộ có mTLS (suy luận) và ghi rõ trong báo cáo.

## 4. Ngoài phạm vi

- Gửi bản neo tới bên thứ ba và nhận biên nhận (DV-13-09); kiểm tra sau niêm phong (DV-09-04); bằng chứng bao hàm cho API (DV-09-05).
- Nhà cung cấp HSM và TSA trong esign-service (DV-10-03).

## 5. Tệp được phép sửa

- `services-go/ledger/**`
- `contracts/events/avro/` chỉ để đọc; không sửa lược đồ EVT-19 (thuộc NEN-007)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd services-go/ledger && go test ./...` xanh, gồm kiểm thử tích hợp với PostgreSQL 17, Kafka và esign-service giả lập (SoftHSM + TSA giả lập RFC 3161): một cửa sổ có 3 tenant sinh đúng 1 `cell_seal`, 3 `merkle_seal` và 1 EVT-19 trong outbox.
- Kiểm thử chứng minh đường đi Merkle tái dựng gốc cell từ gốc tenant; chữ ký và token TSA kiểm được bằng thư viện chuẩn (`openssl ts -verify` hoặc tương đương) trên chứng thư thử.
- Niêm phong 1 giờ ở tải PERF-16 xong ≤ 60 giây, chu kỳ 5 phút ≤ 20 giây (đo cục bộ, kiểm đầy đủ ở QA-10); chạy lại cùng cửa sổ không tạo niêm phong trùng.
- Giả lập TSA lỗi 2 cửa sổ: cửa sổ thứ ba có token bù và cờ "dấu thời gian trễ"; quá 24 giờ phát metric cảnh báo.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki trang 14 ghi các điểm lệch đã chọn.

## 7. Báo cáo

Theo AGENTS.md mục 6.
