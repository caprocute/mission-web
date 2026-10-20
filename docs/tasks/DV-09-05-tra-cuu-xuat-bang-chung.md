# DV-09-05 · QT-13/SCR-1302 API tra cứu sổ cái, xuất bằng chứng có ký và công cụ `ledger-verify`

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-09-05` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B6 |
| Cỡ việc | L |
| Phụ thuộc | DV-09-03; DV-09-04 (đính kèm kết quả kiểm tra vào gói xuất) nên gộp trước (bổ sung) |
| Truy vết | QT-13 (BR-13-12, MSG-13-07) → SCR-1302 → API `GET /api/v1/ledger/entries`, `GET /api/v1/ledger/entries/{id}`, `POST /api/v1/ledger/exports`, `GET /api/v1/oversight/ledger/proofs` → SVC-09 → bảng `ledger.ledger_entry`, `ledger.merkle_seal`, `ledger.cell_seal`, `ledger.anchor_receipt`, `ledger.verify_run` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Cho cơ quan kiểm tra tra cứu lịch sử thay đổi của một đối tượng trong sổ cái, xem bản ghi kèm niêm phong và bản neo, xuất gói bằng chứng ký bằng khoá của ledger-service, và kiểm chứng gói đó hoàn toàn ngoại tuyến bằng công cụ dòng lệnh Go `ledger-verify`. Khi xong, màn hình SCR-1302 chạy với API thật và một gói xuất bất kỳ được `ledger-verify` kết luận "HỢP LỆ" hoặc chỉ đúng chỗ sai.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` SCR-1302 (L1078–1127: bảng trường, bảng chức năng, MSG-13-05..07), QT-13 "Quy tắc nghiệp vụ" (L999, BR-13-12).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Công cụ kiểm chứng độc lập" (L2611) và "Niêm phong Merkle, dấu thời gian và bản neo" (L2601).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "Niêm phong Merkle và dấu thời gian" (L4128, bằng chứng tồn tại), từ điển các bảng `ledger` (L3596–3692).
- `contracts/openapi/modules/oversight.yaml` operation `listLedgerEntries`, `getLedgerEntry`, `createLedgerExport`, `getLedgerInclusionProof`; `contracts/openapi/modules/jobs.yaml`.
- `wiki/15a-ma-tran-phan-quyen.md` các dòng SCR-1302; bản trích `extract/tl02b-may-chu.md` QT-13 (BE-Q13-10).

## 3. Phạm vi công việc

- Hiện thực `listLedgerEntries` lọc theo `aggregateType`, `aggregateId`, `from`, `to`, `eventType`, `chainPartition`, phân trang con trỏ `limit` ≤ 200, mặc định 90 ngày, từ chối khoảng quá 2 năm bằng 422 `LEDGER-422-RANGE-TOO-LONG`; trường C2 trong `metadata` trả dạng che.
- Hiện thực `getLedgerEntry` trả chi tiết bản ghi, các băm (hex), niêm phong chứa bản ghi (`merkle_seal`, `cell_seal`) và biên nhận bản neo (`anchor_receipt`); bản ghi chưa niêm phong ghi rõ trạng thái.
- Hiện thực `getLedgerInclusionProof` trả bằng chứng bao hàm: các bản ghi từ sự kiện tới lần niêm phong kế tiếp trong chuỗi, đường đi Merkle tenant → cell, chữ ký và token TSA; sự kiện chưa niêm phong trả 409 `LEDGER-409-NOT-SEALED`.
- Hiện thực `createLedgerExport` (202 + `Location: /api/v1/jobs/{id}`, `Idempotency-Key`): job sinh gói `tar` gồm bản ghi trong phạm vi (JSON chuẩn hoá RFC 8785), niêm phong, bản neo, chứng thư khoá niêm phong và TSA, kết quả `verify_run` gần nhất, tệp kê khai ký bằng khoá của ledger-service; lưu gói vào kho đối tượng, thông báo xong theo MSG-13-07.
- Viết `services-go/ledger/cmd/ledger-verify`: chạy ngoại tuyến năm bước của TL-01 (tính lại `entry_hash` và liên kết `prev_hash` không thiếu `seq_no`; dựng lại gốc tenant; kiểm đường đi tới gốc cell; kiểm chữ ký gốc và token RFC 3161 theo chuỗi chứng thư; đối chiếu bản neo nếu có `--anchors`), in kết luận tiếng Việt "HỢP LỆ" hoặc vị trí sai, mã thoát khác 0 khi sai; không phụ thuộc mạng hay CSDL.
- Mọi lần tra cứu, xem, xuất được ghi thành sự kiện kiểm toán vào sổ cái qua `sys.audit.v1`; kiểm quyền vai trò bằng token và phạm vi đơn vị bằng OPA (`appraiser` chỉ trong phạm vi đơn vị, `inspector` theo tenant).
- Viết `README.md` cho `ledger-verify` (cách dùng, định dạng gói) để công bố cho cơ quan thanh tra (TL-01 yêu cầu mã nguồn công bố được).
- Gợi ý tách nếu quá lớn: (a) ba API tra cứu và bằng chứng bao hàm; (b) job xuất + `ledger-verify`.

## 4. Ngoài phạm vi

- Màn hình SCR-1302 phía web (WEB-043) và hiển thị "giá trị trước và sau" ngoài phần `metadata` rút gọn mà sổ cái có.
- Gửi bản neo ra ngoài (DV-13-09); kiểm tra toàn vẹn chạy theo lịch (DV-09-04).

## 5. Tệp được phép sửa

- `services-go/ledger/**`
- `data/golden/ledger-canon/**` (thêm gói xuất mẫu nhỏ cho kiểm thử `ledger-verify`)

## 6. Tiêu chí hoàn thành

- `cd services-go/ledger && go test ./...` xanh, gồm kiểm thử tích hợp Testcontainers và kiểm thử hợp đồng: phản hồi của bốn operation khớp lược đồ trong `oversight.yaml`; 403 với `member`; 422 khi quá 2 năm.
- Kiểm thử đầu cuối cục bộ: sinh sổ cái thử, niêm phong, xuất gói, `ledger-verify` báo "HỢP LỆ"; sửa một byte trong gói thì `ledger-verify` báo đúng chuỗi và `seq_no` sai và thoát mã khác 0.
- `ledger-verify` dựng được thành tệp chạy tĩnh cho Linux và Windows (`GOOS=windows`), không gọi mạng (kiểm bằng chạy trong mạng bị chặn).
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
